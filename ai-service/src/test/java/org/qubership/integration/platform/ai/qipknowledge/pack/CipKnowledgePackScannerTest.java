package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

class CipKnowledgePackScannerTest {

  private final CipKnowledgePackScanner scanner = new CipKnowledgePackScanner();

  @Test
  void scannerIgnoresMissingOptionalDirectories(@TempDir Path tempDir) throws Exception {
    Files.createDirectories(tempDir.resolve("skills/cip-test-generator"));
    Files.writeString(tempDir.resolve("skills/cip-test-generator/SKILL.md"), "# Test\n");

    CipKnowledgePackScanResult result = scanner.scan(tempDir);

    assertEquals(1, result.files().size());
    assertEquals("skills/cip-test-generator/SKILL.md", result.files().get(0).relativePath());
  }

  @Test
  void scannerReturnsSortedRelativePaths() {
    CipKnowledgePackScanResult result = scanner.scan(CipKnowledgePackFixturePaths.packRoot());

    List<String> paths = result.files().stream().map(file -> file.relativePath()).toList();
    List<String> sorted = paths.stream().sorted().toList();

    assertEquals(sorted, paths);
    assertFalse(paths.isEmpty());
  }

  @Test
  void scannerClassifiesSkillFile() {
    CipKnowledgePackScanResult result = scanner.scan(CipKnowledgePackFixturePaths.packRoot());

    boolean found =
        result.files().stream()
            .anyMatch(
                file ->
                    file.relativePath().equals("skills/cip-error-handling-generator/SKILL.md")
                        && file.kind() == CipKnowledgePackFileKind.SKILL);

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

    CipKnowledgePackScanResult result = scanner.scan(tempDir);

    assertKind(result, "skills/cip-trigger-generator/SKILL.md", CipKnowledgePackFileKind.SKILL);
    assertKind(
        result,
        "skills/cip-trigger-generator/APM_PRIVATE",
        CipKnowledgePackFileKind.SKILL_PRIVATE_MARKER);
    assertKind(
        result,
        "skills/cip-trigger-generator.md",
        CipKnowledgePackFileKind.SKILL_SOURCE_SPECIFICATION);
    assertKind(
        result, "skills/RUNTIME_SKILL_INDEX.yaml", CipKnowledgePackFileKind.RUNTIME_SKILL_INDEX);
    assertKind(result, "skills/skill-catalog.yaml", CipKnowledgePackFileKind.SKILL_CATALOG);
    assertKind(
        result,
        "compiler-runtime-package/language-model.yaml",
        CipKnowledgePackFileKind.RUNTIME_PACKAGE_ARTIFACT);
  }

  @Test
  void scannerImportsMissingApmProcessSkills() {
    CipKnowledgePackScanResult result = scanner.scan(CipKnowledgePackFixturePaths.packRoot());

    assertKind(result, "skills/brainstorming/SKILL.md", CipKnowledgePackFileKind.SKILL);
  }

  @Test
  void scannerComputesNonEmptySha256() {
    CipKnowledgePackScanResult result = scanner.scan(CipKnowledgePackFixturePaths.packRoot());

    assertTrue(
        result.files().stream()
            .allMatch(file -> file.sha256() != null && !file.sha256().isBlank()));
  }

  private static void assertKind(
      CipKnowledgePackScanResult result, String relativePath, CipKnowledgePackFileKind kind) {
    assertTrue(
        result.files().stream()
            .anyMatch(file -> relativePath.equals(file.relativePath()) && file.kind() == kind),
        () -> "Expected " + relativePath + " to be classified as " + kind);
  }
}
