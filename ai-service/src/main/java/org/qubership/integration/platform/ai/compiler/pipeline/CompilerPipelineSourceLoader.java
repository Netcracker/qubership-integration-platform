package org.qubership.integration.platform.ai.compiler.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Loads canonical compiler pipeline sources used to compile schema-v2 indexes. */
public final class CompilerPipelineSourceLoader {

  static final String RUNTIME_DEPENDENCY_MODEL = "runtime-dependency-model.yaml";
  static final String SKILL_CATALOG = "skill-catalog.yaml";
  static final String GENERATOR_PACKAGES = "generator-packages.yaml";
  static final String ARTIFACT_SCHEMAS = "artifact-schemas.yaml";

  private static final String RUNTIME_DEPENDENCY_MODEL_PATH =
      "knowledge/runtime-substrate/runtime-dependency-model.yaml";
  private static final String SKILL_CATALOG_PATH = "skills/skill-catalog.yaml";
  private static final String GENERATOR_PACKAGES_PATH =
      "knowledge/runtime-substrate/generator-packages.yaml";
  private static final String ARTIFACT_SCHEMAS_PATH = "product-pipelines/artifact-schemas.yaml";
  private static final String APM_SKILLS_ROOT = ".apm/skills";
  private static final String ADDON_SKILLS_ROOT = "skills";

  /** Immutable bundle of canonical pipeline sources for one index build. */
  public record SourceSet(
      String runtimeDependencyModelYaml,
      String skillCatalogYaml,
      String generatorPackagesYaml,
      String artifactSchemasYaml,
      Map<String, String> skillContentsById,
      Map<String, String> addonContentsById,
      Map<String, String> skillSha256ById,
      Map<String, String> addonSha256ById,
      Map<String, String> sourceDigests) {

    public SourceSet {
      skillContentsById =
          skillContentsById == null ? Map.of() : Map.copyOf(skillContentsById);
      addonContentsById =
          addonContentsById == null ? Map.of() : Map.copyOf(addonContentsById);
      skillSha256ById = skillSha256ById == null ? Map.of() : Map.copyOf(skillSha256ById);
      addonSha256ById = addonSha256ById == null ? Map.of() : Map.copyOf(addonSha256ById);
      sourceDigests = sourceDigests == null ? Map.of() : Map.copyOf(sourceDigests);
    }
  }

  public SourceSet load(Path packRoot, Path addonRoot) {
    if (packRoot == null) {
      throw new IllegalArgumentException("packRoot is required");
    }
    Path normalizedPack = packRoot.toAbsolutePath().normalize();
    Path dependencyModel = requireFile(normalizedPack, RUNTIME_DEPENDENCY_MODEL_PATH);
    Path skillCatalog = requireFile(normalizedPack, SKILL_CATALOG_PATH);
    Path generatorPackages = requireFile(normalizedPack, GENERATOR_PACKAGES_PATH);
    Path artifactSchemas = requireFile(normalizedPack, ARTIFACT_SCHEMAS_PATH);

    String dependencyYaml = read(dependencyModel);
    String catalogYaml = read(skillCatalog);
    String packagesYaml = read(generatorPackages);
    String schemasYaml = read(artifactSchemas);

    Map<String, String> skillContents = new LinkedHashMap<>();
    Map<String, String> skillSha = new LinkedHashMap<>();
    loadSkills(normalizedPack.resolve(APM_SKILLS_ROOT), skillContents, skillSha);

    Map<String, String> addonContents = new LinkedHashMap<>();
    Map<String, String> addonSha = new LinkedHashMap<>();
    if (addonRoot != null) {
      loadAddons(addonRoot.toAbsolutePath().normalize(), addonContents, addonSha);
    }

    Map<String, String> digests = new LinkedHashMap<>();
    digests.put(RUNTIME_DEPENDENCY_MODEL, sha256(dependencyYaml));
    digests.put(SKILL_CATALOG, sha256(catalogYaml));
    digests.put(GENERATOR_PACKAGES, sha256(packagesYaml));
    digests.put(ARTIFACT_SCHEMAS, sha256(schemasYaml));

    return new SourceSet(
        dependencyYaml,
        catalogYaml,
        packagesYaml,
        schemasYaml,
        skillContents,
        addonContents,
        skillSha,
        addonSha,
        digests);
  }

  private static void loadSkills(
      Path skillsRoot, Map<String, String> contents, Map<String, String> digests) {
    if (!Files.isDirectory(skillsRoot)) {
      return;
    }
    try (Stream<Path> children = Files.list(skillsRoot)) {
      children
          .filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                  return;
                }
                String skillId = dir.getFileName().toString();
                String content = read(skillMd);
                contents.put(skillId, content);
                digests.put(skillId, sha256(content));
              });
    } catch (IOException e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to scan skill sources under " + skillsRoot);
    }
  }

  private static void loadAddons(
      Path addonRoot, Map<String, String> contents, Map<String, String> digests) {
    Path skillsDir = addonRoot.resolve(ADDON_SKILLS_ROOT);
    if (!Files.isDirectory(skillsDir)) {
      return;
    }
    try (Stream<Path> files = Files.list(skillsDir)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".addon.md"))
          .sorted()
          .forEach(
              file -> {
                String fileName = file.getFileName().toString();
                String skillId =
                    fileName.substring(0, fileName.length() - ".addon.md".length());
                String content = read(file);
                contents.put(skillId, content);
                digests.put(skillId, sha256(content));
              });
    } catch (IOException e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to scan addon sources under " + skillsDir);
    }
  }

  private static Path requireFile(Path packRoot, String relativePath) {
    Path file = packRoot.resolve(relativePath);
    if (!Files.isRegularFile(file)) {
      throw new CompilerPipelineIndexParseException(
          "Missing canonical pipeline source: " + relativePath);
    }
    return file;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CompilerPipelineIndexParseException("Failed to read pipeline source: " + file);
    }
  }

  static String sha256(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  static String normalizeSkillId(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.trim().toLowerCase(Locale.ROOT);
  }
}
