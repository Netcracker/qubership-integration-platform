package org.qubership.integration.platform.ai.qipknowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class QipKnowledgeRagManifestBuilderTest {

  private final QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
  private final QipKnowledgeRagManifestBuilder builder = new QipKnowledgeRagManifestBuilder();

  @Test
  void buildsDeterministicManifestFromFixturePack() {
    var result = ingestionService.ingest(QipKnowledgePackFixturePaths.packRoot());
    QipKnowledgeRagIngestionManifest manifest = builder.build(result);

    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, manifest.version().normalized());
    assertFalse(manifest.chunks().isEmpty());

    boolean hasSkillChunk =
        manifest.chunks().stream()
            .anyMatch(
                chunk ->
                    chunk.sourcePath().equals("skills/cip-error-handling-generator/SKILL.md")
                        && chunk.phase() == QipKnowledgeCapabilityPhase.GENERATOR
                        && chunk.capabilityIds().contains("cip-error-handling-generator"));
    assertTrue(hasSkillChunk);

    boolean hasKnowledgeChunk =
        manifest.chunks().stream()
            .anyMatch(chunk -> chunk.sourcePath().startsWith("knowledge/"));
    assertFalse(hasKnowledgeChunk);

    boolean hasRefIds =
        manifest.chunks().stream().anyMatch(chunk -> !chunk.capabilityIds().isEmpty());
    assertTrue(hasRefIds);

    boolean hasContent =
        manifest.chunks().stream()
            .anyMatch(
                chunk ->
                    chunk.sourcePath().equals("skills/cip-security-generator/SKILL.md")
                        && chunk.content().contains("Supported Access Control Types"));
    assertTrue(hasContent);

    boolean hasBrainstorming =
        manifest.chunks().stream()
            .anyMatch(
                chunk ->
                    chunk.sourcePath().equals("skills/brainstorming/SKILL.md")
                        && chunk.phase() == QipKnowledgeCapabilityPhase.UNSUPPORTED
                        && chunk.content().contains("Brainstorming Ideas Into Designs"));
    assertTrue(hasBrainstorming);

    List<String> sourcePaths =
        manifest.chunks().stream().map(QipKnowledgeRagChunk::sourcePath).toList();
    List<String> sorted = sourcePaths.stream().sorted().toList();
    assertEquals(sorted, sourcePaths);

    for (int i = 0; i < manifest.chunks().size(); i++) {
      assertEquals(i, manifest.chunks().get(i).ordinal());
    }
  }
}
