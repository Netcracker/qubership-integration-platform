package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;

/** Build-time entrypoint that ingests a QIP skill pack and writes classpath indexes. */
public final class CipKnowledgePackBuildGenerator {

  private static final ObjectMapper MANIFEST_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private CipKnowledgePackBuildGenerator() {}

  public static void main(String[] args) throws Exception {
    generate(resolvePackRoot(args), resolveOutputRoot(args), resolveAddonRoot());
  }

  public static void generate(Path packRoot, Path outputRoot) throws Exception {
    generate(packRoot, outputRoot, resolveAddonRoot());
  }

  public static void generate(Path packRoot, Path outputRoot, Path addonPackRoot) throws Exception {
    if (!Files.isDirectory(packRoot)) {
      throw new IllegalArgumentException(
          "QIP skill pack root must be a directory: " + packRoot);
    }
    validateSourceManifest(packRoot);
    String export = System.getProperty("qip.knowledge.export");
    Path exportRoot = export != null && !export.isBlank() ? Path.of(export)
        : Files.isRegularFile(packRoot.resolve("runtime/generator-contracts.json"))
            ? packRoot.resolveSibling("rocky-knowledge") : packRoot.resolve("knowledge-export");
    CipKnowledgeExportValidator.validate(exportRoot);
    CipKnowledgePackIngestionService ingestionService = new CipKnowledgePackIngestionService();
    CipKnowledgePackIngestionResult result = ingestionService.ingest(packRoot);
    ingestionService.writeArtifacts(result, outputRoot, addonPackRoot);
    Path versionDir =
        CipKnowledgePackIndexLoader.resolveVersionDir(
            outputRoot, result.manifest().version());
    if (addonPackRoot != null) {
      CompilerSkillAddonBuildSupport.materialize(addonPackRoot, versionDir);
    }
    stampCompilerContractPin(versionDir);
  }

  private static void validateSourceManifest(Path packRoot) throws Exception {
    Path source = packRoot.resolve("manifest.json");
    if (!Files.isRegularFile(source)) return;
    var manifest = MANIFEST_MAPPER.readTree(source.toFile());
    var files = manifest.path("files").fields();
    Path root = packRoot.toAbsolutePath().normalize();
    while (files.hasNext()) {
      var entry = files.next();
      Path path = root.resolve(entry.getKey()).normalize();
      if (!path.startsWith(root) || !Files.isRegularFile(path)
          || !entry.getValue().path("sha256").asText().equals(CompilerSkillAddonDocument.sha256(
              Files.readString(path, StandardCharsets.UTF_8)))) {
        throw new IllegalArgumentException("Rocky source checksum mismatch: " + entry.getKey());
      }
    }
    for (String directory : java.util.List.of("skills", "runtime")) {
      Path directoryPath = root.resolve(directory);
      if (!Files.isDirectory(directoryPath)) continue;
      try (Stream<Path> paths = Files.walk(directoryPath)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          String relative = root.relativize(path).toString().replace('\\', '/');
          if (!path.getFileName().toString().startsWith(".") && !manifest.path("files").has(relative)) {
            throw new IllegalArgumentException("Undeclared Rocky source: " + relative);
          }
        }
      }
    }
  }

  static void stampCompilerContractPin(Path versionDir) throws IOException {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    Path manifestFile = versionDir.resolve(CipKnowledgePackIndexLoader.MANIFEST_FILE);
    if (!Files.isRegularFile(manifestFile)) {
      throw new IllegalStateException("QIP knowledge manifest is missing: " + manifestFile);
    }
    CipKnowledgePackManifest manifest =
        MANIFEST_MAPPER.readValue(manifestFile.toFile(), CipKnowledgePackManifest.class);
    CipKnowledgePackManifest stamped =
        manifest.withCompilerContractPin(
            contract.contractVersion(), contract.sha256(), addonDigests(versionDir));
    MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), stamped);
  }

  private static Map<String, String> addonDigests(Path versionDir) throws IOException {
    Path skillsDir =
        versionDir.resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR).resolve("skills");
    boolean standalone = Files.isDirectory(versionDir.resolve(CompilerSkillAddonBuildSupport.RUNTIME_DIR));
    if (standalone) {
      skillsDir = versionDir.resolve(CompilerSkillAddonBuildSupport.RUNTIME_DIR).resolve("skills");
    }
    if (!Files.isDirectory(skillsDir)) {
      return Map.of();
    }
    Map<String, String> digests = new LinkedHashMap<>();
    try (Stream<Path> stream = Files.list(skillsDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(standalone ? ".json" : ".addon.md"))
          .sorted()
          .forEach(
              file -> {
                String fileName = file.getFileName().toString();
                String skillId = fileName.substring(0, fileName.length() - (standalone ? 5 : 9));
                try {
                  String content = Files.readString(file, StandardCharsets.UTF_8);
                  digests.put(skillId, CompilerSkillAddonDocument.sha256(content));
                } catch (IOException e) {
                  throw new IllegalStateException("Failed to hash addon: " + file, e);
                }
              });
    }
    return Map.copyOf(digests);
  }

  private static Path resolvePackRoot(String[] args) {
    String fromArg = args.length > 0 ? args[0] : null;
    String fromProperty = System.getProperty("qip.ai.qipknowledge.pack-root");
    String raw = firstNonBlank(fromArg, fromProperty);
    if (raw == null) {
      throw new IllegalArgumentException(
          "packRoot is required. Pass it as the first argument or set -Dqip.ai.qipknowledge.pack-root");
    }
    return Path.of(raw).normalize().toAbsolutePath();
  }

  private static Path resolveOutputRoot(String[] args) {
    String fromArg = args.length > 1 ? args[1] : null;
    String fromProperty = System.getProperty("qip.ai.qipknowledge.output-root");
    String raw = firstNonBlank(fromArg, fromProperty);
    if (raw == null) {
      throw new IllegalArgumentException(
          "outputRoot is required. Pass it as the second argument or set -Dqip.ai.qipknowledge.output-root");
    }
    return Path.of(raw).normalize().toAbsolutePath();
  }

  private static Path resolveAddonRoot() {
    String fromProperty = System.getProperty("qip.ai.qipknowledge.skill-metadata-root",
        System.getProperty("qip.ai.qipknowledge.addon-pack-root"));
    if (fromProperty == null || fromProperty.isBlank()) {
      return null;
    }
    Path path = Path.of(fromProperty).normalize().toAbsolutePath();
    return Files.isDirectory(path) ? path : null;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }
}
