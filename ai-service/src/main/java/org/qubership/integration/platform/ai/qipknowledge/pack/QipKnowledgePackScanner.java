package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Deterministic filesystem scanner for a CIP QIP knowledge pack. */
public class QipKnowledgePackScanner {

  private static final List<String> PACK_SCAN_ROOTS =
      List.of(
          "skills",
          "skill-specifications",
          "compiler-runtime-package",
          "generated",
          "product-pipelines",
          "knowledge");
  private static final String APM_SKILLS_ROOT = "apm/package/.apm/skills";
  private static final String PACK_APM_SKILLS_ROOT = ".apm/skills";
  public QipKnowledgePackScanResult scan(Path packRoot) {
    if (packRoot == null) {
      throw new IllegalArgumentException("packRoot is required");
    }
    Path normalizedRoot = packRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalizedRoot)) {
      throw new IllegalArgumentException("packRoot must be an existing directory: " + normalizedRoot);
    }

    QipKnowledgePackVersion version = QipKnowledgePackVersion.fromPath(normalizedRoot);
    List<ScannedQipKnowledgeFile> files = new ArrayList<>();

    for (String scanRoot : PACK_SCAN_ROOTS) {
      Path root = normalizedRoot.resolve(scanRoot);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(path -> !isHiddenPath(path, normalizedRoot))
            .sorted(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()))
            .forEach(path -> files.add(toScannedFile(normalizedRoot, path)));
      } catch (IOException e) {
        throw new IllegalStateException("Failed to scan QIP knowledge pack at " + root, e);
      }
    }
    scanMissingApmSkills(normalizedRoot, APM_SKILLS_ROOT, files);
    scanMissingApmSkills(normalizedRoot, PACK_APM_SKILLS_ROOT, files);

    files.sort(Comparator.comparing(ScannedQipKnowledgeFile::relativePath));
    return new QipKnowledgePackScanResult(normalizedRoot, version, List.copyOf(files));
  }

  private static void scanMissingApmSkills(
      Path normalizedRoot, String apmSkillsRoot, List<ScannedQipKnowledgeFile> files) {
    Path root = normalizedRoot.resolve(apmSkillsRoot);
    if (!Files.isDirectory(root)) {
      return;
    }

    Set<String> existingPaths = new HashSet<>();
    for (ScannedQipKnowledgeFile file : files) {
      existingPaths.add(file.relativePath());
    }

    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .filter(path -> !isHiddenPath(path, normalizedRoot))
          .sorted(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()))
          .forEach(
              path -> {
                String canonicalPath = canonicalApmSkillPath(normalizedRoot, path, apmSkillsRoot);
                if (canonicalPath != null && existingPaths.add(canonicalPath)) {
                  files.add(toScannedFile(normalizedRoot, path, canonicalPath));
                }
              });
    } catch (IOException e) {
      throw new IllegalStateException("Failed to scan QIP knowledge APM skills at " + root, e);
    }
  }

  /**
   * Returns true when any path segment relative to {@code root} is a hidden name.
   *
   * <p>Absolute prefixes such as a workspace {@code .worktrees} directory must not hide the pack.
   * {@code .apm} is an intentional package root and is allowed.
   */
  private static boolean isHiddenPath(Path path, Path root) {
    Path absolute = path.toAbsolutePath().normalize();
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path relative;
    if (absolute.startsWith(normalizedRoot)) {
      relative = normalizedRoot.relativize(absolute);
    } else {
      Path fileName = absolute.getFileName();
      return fileName != null
          && fileName.toString().startsWith(".")
          && !".apm".equals(fileName.toString());
    }
    for (Path part : relative) {
      String name = part.toString();
      if (name.startsWith(".") && !".apm".equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static ScannedQipKnowledgeFile toScannedFile(Path packRoot, Path absolutePath) {
    String relativePath = packRoot.relativize(absolutePath).toString().replace('\\', '/');
    return toScannedFile(packRoot, absolutePath, relativePath);
  }

  private static ScannedQipKnowledgeFile toScannedFile(
      Path packRoot, Path absolutePath, String relativePath) {
    String content;
    try {
      content = Files.readString(absolutePath, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read file: " + absolutePath, e);
    }
    return new ScannedQipKnowledgeFile(
        absolutePath,
        relativePath,
        classify(relativePath),
        sha256(content),
        content);
  }

  private static String canonicalApmSkillPath(
      Path packRoot, Path absolutePath, String apmSkillsRoot) {
    String relativePath = packRoot.relativize(absolutePath).toString().replace('\\', '/');
    String prefix = apmSkillsRoot + "/";
    if (!relativePath.startsWith(prefix) || !relativePath.endsWith("/SKILL.md")) {
      return null;
    }
    String skillPath = relativePath.substring(prefix.length());
    return skillPath.indexOf('/') > 0 ? "skills/" + skillPath : null;
  }

  static QipKnowledgePackFileKind classify(String relativePath) {
    String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
    QipKnowledgePackFileKind skillKind = classifySkillPath(normalized);
    if (skillKind != null) {
      return skillKind;
    }
    return classifyKnowledgePath(normalized);
  }

  private static QipKnowledgePackFileKind classifySkillPath(String normalized) {
    if (normalized.equals("skills/runtime_skill_index.yaml")) {
      return QipKnowledgePackFileKind.RUNTIME_SKILL_INDEX;
    }
    if (normalized.equals("skills/skill-catalog.yaml")) {
      return QipKnowledgePackFileKind.SKILL_CATALOG;
    }
    if (normalized.startsWith("skills/") && normalized.endsWith("/apm_private")) {
      return QipKnowledgePackFileKind.SKILL_PRIVATE_MARKER;
    }
    if (normalized.startsWith("skills/") && normalized.endsWith("/skill.md")) {
      return QipKnowledgePackFileKind.SKILL;
    }
    if (normalized.matches("^skills/[^/]+\\.md$")) {
      return QipKnowledgePackFileKind.SKILL_SOURCE_SPECIFICATION;
    }
    if (normalized.startsWith("skills/normalized-skill-specifications/")) {
      return QipKnowledgePackFileKind.SKILL_SPECIFICATION;
    }
    if (normalized.startsWith("skills/skill-specifications/")) {
      return QipKnowledgePackFileKind.SKILL_SPECIFICATION;
    }
    return null;
  }

  private static QipKnowledgePackFileKind classifyKnowledgePath(String normalized) {
    if (normalized.startsWith("skill-specifications/")) {
      return QipKnowledgePackFileKind.SKILL_SPECIFICATION;
    }
    if (normalized.startsWith("compiler-runtime-package/")) {
      return QipKnowledgePackFileKind.RUNTIME_PACKAGE_ARTIFACT;
    }
    if (normalized.startsWith("knowledge/ai/")) {
      return QipKnowledgePackFileKind.KNOWLEDGE_AI;
    }
    if (normalized.startsWith("knowledge/grammar/")) {
      return QipKnowledgePackFileKind.KNOWLEDGE_GRAMMAR;
    }
    if (normalized.startsWith("knowledge/corporate/")) {
      return QipKnowledgePackFileKind.KNOWLEDGE_CORPORATE;
    }
    if (normalized.startsWith("knowledge/")) {
      return QipKnowledgePackFileKind.KNOWLEDGE_GENERIC;
    }
    if (normalized.startsWith("generated/")) {
      return QipKnowledgePackFileKind.GENERATED_EXAMPLE;
    }
    if (normalized.contains("/examples/")) {
      return QipKnowledgePackFileKind.EXAMPLE;
    }
    return QipKnowledgePackFileKind.OTHER;
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
}
