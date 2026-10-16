package org.qubership.integration.platform.ai.qipknowledge.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackFileKind;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanner;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class SkillParserTest {

  private final QipKnowledgePackScanner scanner = new QipKnowledgePackScanner();
  private final SkillParser parser = new SkillParser();

  @Test
  void parsesErrorHandlingGeneratorSkillId() {
    var file =
        scanner.scan(QipKnowledgePackFixturePaths.packRoot()).files().stream()
            .filter(
                scanned ->
                    scanned
                        .relativePath()
                        .equals("skills/cip-error-handling-generator/SKILL.md"))
            .findFirst()
            .orElseThrow();

    SkillDescriptor skill = parser.parse(file);

    assertEquals("cip-error-handling-generator", skill.skillId());
    assertEquals(QipKnowledgeCapabilityPhase.GENERATOR, skill.phase());
  }

  @Test
  void doesNotPromoteMarkdownIdsIntoSkillIdentity() {
    var file =
        scanner.scan(QipKnowledgePackFixturePaths.packRoot()).files().stream()
            .filter(
                scanned ->
                    scanned
                        .relativePath()
                        .equals("skills/cip-error-handling-generator/SKILL.md"))
            .findFirst()
            .orElseThrow();

    SkillDescriptor skill = parser.parse(file);

    assertEquals(
        "skills/cip-error-handling-generator/SKILL.md",
        skill.sourcePath());
    assertFalse(skill.rawSummary().isBlank());
  }

  @Test
  void flagsFileTransportOnlySkills() {
    var file =
        scanner.scan(QipKnowledgePackFixturePaths.packRoot()).files().stream()
            .filter(
                scanned ->
                    scanned.kind() == QipKnowledgePackFileKind.SKILL
                        && scanned.relativePath().contains("cip-folder-organizer/"))
            .findFirst()
            .orElseThrow();

    SkillDescriptor skill = parser.parse(file);

    assertTrue(skill.fileTransportOnly());
  }

  @Test
  void generatorSkillIsNotFileTransportOnly() {
    var file =
        scanner.scan(QipKnowledgePackFixturePaths.packRoot()).files().stream()
            .filter(
                scanned ->
                    scanned
                        .relativePath()
                        .equals("skills/cip-error-handling-generator/SKILL.md"))
            .findFirst()
            .orElseThrow();

    SkillDescriptor skill = parser.parse(file);

    assertFalse(skill.fileTransportOnly());
  }
}
