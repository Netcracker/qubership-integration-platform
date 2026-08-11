package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Copies a compiler skill addon pack into build output and writes an addon index. */
public final class CompilerSkillAddonBuildSupport {

  public static final String ADDONS_DIR = "addons";
  public static final String ADDON_INDEX_FILE = "addon-index.json";

  private static final ObjectMapper INDEX_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final AddonRuntimeMetadataParser METADATA_PARSER =
      new AddonRuntimeMetadataParser();

  private CompilerSkillAddonBuildSupport() {}

  public static void materialize(Path addonPackRoot, Path versionOutputDir) throws IOException {
    Path addonsDir = versionOutputDir.resolve(ADDONS_DIR);
    Files.createDirectories(addonsDir);
    if (addonPackRoot == null || !Files.isDirectory(addonPackRoot)) {
      writeIndex(addonsDir, CompilerSkillAddonIndex.empty());
      return;
    }

    copyTree(addonPackRoot, addonsDir);
    writeIndex(addonsDir, buildIndex(addonsDir));
  }

  static CompilerSkillAddonIndex buildIndex(Path addonsDir) throws IOException {
    List<String> globalDocuments = listRelativeFiles(addonsDir, "global", ".md");
    List<String> globalDataDocuments = listRelativeFiles(addonsDir, "global", ".yaml");
    Map<String, CompilerSkillAddonIndex.CompilerSkillAddonSkillIndex> skills = new LinkedHashMap<>();

    Path skillsDir = addonsDir.resolve("skills");
    if (Files.isDirectory(skillsDir)) {
      List<String> skillFiles;
      try (Stream<Path> stream = Files.list(skillsDir)) {
        skillFiles =
            stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".addon.md"))
                .sorted()
                .toList();
      }
      for (String fileName : skillFiles) {
        String skillId = fileName.substring(0, fileName.length() - ".addon.md".length());
        String addonDocument = "skills/" + fileName;
        AddonRuntimeMetadata runtimeMetadata =
            readRuntimeMetadata(addonsDir.resolve(addonDocument), skillId);
        List<String> examples = listRelativeFiles(addonsDir, "examples/" + skillId, ".json");
        skills.put(
            skillId,
            new CompilerSkillAddonIndex.CompilerSkillAddonSkillIndex(
                addonDocument, examples, runtimeMetadata));
      }
    }

    return new CompilerSkillAddonIndex(
        List.copyOf(globalDocuments), List.copyOf(globalDataDocuments), Map.copyOf(skills));
  }

  private static List<String> listRelativeFiles(
      Path addonsDir, String subdirectory, String suffix) throws IOException {
    Path directory = addonsDir.resolve(subdirectory);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(directory)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
          .sorted()
          .forEach(
              path ->
                  paths.add(addonsDir.relativize(path).toString().replace('\\', '/')));
    }
    return List.copyOf(paths);
  }

  private static void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
    try (Stream<Path> stream = Files.walk(sourceRoot, FileVisitOption.FOLLOW_LINKS)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              source -> {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                try {
                  if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                  } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                  }
                } catch (IOException e) {
                  throw new IllegalStateException("Failed to copy addon file: " + source, e);
                }
              });
    }
  }

  private static void writeIndex(Path addonsDir, CompilerSkillAddonIndex index) throws IOException {
    INDEX_MAPPER
        .writerWithDefaultPrettyPrinter()
        .writeValue(addonsDir.resolve(ADDON_INDEX_FILE).toFile(), index);
  }

  static String readText(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static AddonRuntimeMetadata readRuntimeMetadata(Path addonFile, String skillId)
      throws IOException {
    AddonRuntimeMetadata metadata =
        METADATA_PARSER.parseAddonContent(readText(addonFile), addonFile.toString());
    if (metadata == null) {
      return null;
    }
    if (metadata.captureTool() == null && !allowsMissingCaptureTool(skillId)) {
      throw new IllegalArgumentException(
          "Compiler skill addon must define runtime.capture.tool: " + skillId);
    }
    return metadata;
  }

  /**
   * Skills without an LLM capture tool: Java adapters, process-report design skills, and
   * validators.
   */
  private static boolean allowsMissingCaptureTool(String skillId) {
    return "cip-chain-assembler".equals(skillId)
        || "cip-design-planner".equals(skillId)
        || "cip-design-executor".equals(skillId)
        || (skillId != null && skillId.endsWith("-validator"));
  }
}
