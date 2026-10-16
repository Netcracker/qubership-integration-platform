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
public final class QipKnowledgePackBuildGenerator {

  private static final ObjectMapper MANIFEST_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private QipKnowledgePackBuildGenerator() {}

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
    QipKnowledgeExportValidator.validatePack(packRoot);
    QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
    QipKnowledgePackIngestionResult result = ingestionService.ingest(packRoot);
    ingestionService.writeArtifacts(result, outputRoot, addonPackRoot);
    Path versionDir =
        QipKnowledgePackIndexLoader.resolveVersionDir(
            outputRoot, result.manifest().version());
    if (addonPackRoot != null) {
      CompilerSkillAddonBuildSupport.materialize(addonPackRoot, versionDir);
    }
    stampCompilerContractPin(versionDir);
  }

  static void stampCompilerContractPin(Path versionDir) throws IOException {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    Path manifestFile = versionDir.resolve(QipKnowledgePackIndexLoader.MANIFEST_FILE);
    if (!Files.isRegularFile(manifestFile)) {
      throw new IllegalStateException("QIP knowledge manifest is missing: " + manifestFile);
    }
    QipKnowledgePackManifest manifest =
        MANIFEST_MAPPER.readValue(manifestFile.toFile(), QipKnowledgePackManifest.class);
    QipKnowledgePackManifest stamped =
        manifest.withCompilerContractPin(
            contract.contractVersion(), contract.sha256(), addonDigests(versionDir));
    MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), stamped);
  }

  private static Map<String, String> addonDigests(Path versionDir) throws IOException {
    Path skillsDir =
        versionDir.resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR).resolve("skills");
    if (!Files.isDirectory(skillsDir)) {
      return Map.of();
    }
    Map<String, String> digests = new LinkedHashMap<>();
    try (Stream<Path> stream = Files.list(skillsDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".addon.md"))
          .sorted()
          .forEach(
              file -> {
                String fileName = file.getFileName().toString();
                String skillId = fileName.substring(0, fileName.length() - ".addon.md".length());
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
    String fromProperty = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
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
