package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class QipKnowledgePackScannerTest {

  private final QipKnowledgePackScanner scanner = new QipKnowledgePackScanner();

  @Test
  void scannerIgnoresMissingOptionalDirectories(@TempDir Path tempDir) throws Exception {
    Files.createDirectories(tempDir.resolve("skills/cip-test-generator"));
    Files.writeString(tempDir.resolve("skills/cip-test-generator/SKILL.md"), "# Test\n");

    QipKnowledgePackScanResult result = scanner.scan(tempDir);

    assertEquals(1, result.files().size());
    assertEquals("skills/cip-test-generator/SKILL.md", result.files().get(0).relativePath());
  }

  @Test
  void scannerReturnsSortedRelativePaths() {
    QipKnowledgePackScanResult result = scanner.scan(QipKnowledgePackFixturePaths.packRoot());

    List<String> paths = result.files().stream().map(file -> file.relativePath()).toList();
    List<String> sorted = paths.stream().sorted().toList();

    assertEquals(sorted, paths);
    assertFalse(paths.isEmpty());
  }

  @Test
  void scannerClassifiesSkillFile() {
    QipKnowledgePackScanResult result = scanner.scan(QipKnowledgePackFixturePaths.packRoot());

    boolean found =
        result.files().stream()
            .anyMatch(
                file ->
                    file.relativePath().equals("skills/cip-error-handling-generator/SKILL.md")
                        && file.kind() == QipKnowledgePackFileKind.SKILL);

    assertTrue(found);
  }

  @Test
  void scannerClassifiesProductionCompilerSkillSurfaces(@TempDir Path tempDir) throws Exception {
    Files.createDirectories(tempDir.resolve("skills/cip-trigger-generator"));
    Files.writeString(tempDir.resolve("skills/cip-trigger-generator/SKILL.md"), "# Trigger\n");
    Files.writeString(tempDir.resolve("skills/cip-trigger-generator/APM_PRIVATE"), "# Private\n");
    Files.writeString(tempDir.resolve("skills/cip-trigger-generator.md"), "# Trigger spec\n");
    Files.writeString(tempDir.resolve("skills/RUNTIME_SKILL_INDEX.yaml"), "skills: []\n");
    Files.writeString(tempDir.resolve("skills/skill-catalog.yaml"), "normalized-skills: []\n");
    Files.createDirectories(tempDir.resolve("compiler-runtime-package"));
    Files.writeString(tempDir.resolve("compiler-runtime-package/language-model.yaml"), "version: test\n");

    QipKnowledgePackScanResult result = scanner.scan(tempDir);

    assertKind(result, "skills/cip-trigger-generator/SKILL.md", QipKnowledgePackFileKind.SKILL);
    assertKind(
        result,
        "skills/cip-trigger-generator/APM_PRIVATE",
        QipKnowledgePackFileKind.SKILL_PRIVATE_MARKER);
    assertKind(
        result,
        "skills/cip-trigger-generator.md",
        QipKnowledgePackFileKind.SKILL_SOURCE_SPECIFICATION);
    assertKind(
        result, "skills/RUNTIME_SKILL_INDEX.yaml", QipKnowledgePackFileKind.RUNTIME_SKILL_INDEX);
    assertKind(result, "skills/skill-catalog.yaml", QipKnowledgePackFileKind.SKILL_CATALOG);
    assertKind(
        result,
        "compiler-runtime-package/language-model.yaml",
        QipKnowledgePackFileKind.RUNTIME_PACKAGE_ARTIFACT);
  }

  @Test
  void scannerImportsMissingApmProcessSkills() {
    QipKnowledgePackScanResult result = scanner.scan(QipKnowledgePackFixturePaths.packRoot());

    assertKind(result, "skills/brainstorming/SKILL.md", QipKnowledgePackFileKind.SKILL);
  }

  @Test
  void scannerComputesNonEmptySha256() {
    QipKnowledgePackScanResult result = scanner.scan(QipKnowledgePackFixturePaths.packRoot());

    assertTrue(
        result.files().stream()
            .allMatch(file -> file.sha256() != null && !file.sha256().isBlank()));
  }

  private static void assertKind(
      QipKnowledgePackScanResult result, String relativePath, QipKnowledgePackFileKind kind) {
    assertTrue(
        result.files().stream()
            .anyMatch(file -> relativePath.equals(file.relativePath()) && file.kind() == kind),
        () -> "Expected " + relativePath + " to be classified as " + kind);
  }
}
