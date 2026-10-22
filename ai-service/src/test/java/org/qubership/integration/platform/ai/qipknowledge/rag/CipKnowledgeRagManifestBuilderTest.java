package org.qubership.integration.platform.ai.qipknowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

class CipKnowledgeRagManifestBuilderTest {

  private final CipKnowledgePackIngestionService ingestionService = new CipKnowledgePackIngestionService();
  private final CipKnowledgeRagManifestBuilder builder = new CipKnowledgeRagManifestBuilder();

  @Test
  void buildsDeterministicManifestFromFixturePack() {
    var result = ingestionService.ingest(CipKnowledgePackFixturePaths.packRoot());
    CipKnowledgeRagIngestionManifest manifest = builder.build(result);

    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, manifest.version().normalized());
    assertFalse(manifest.chunks().isEmpty());

    boolean hasSkillChunk =
        manifest.chunks().stream()
            .anyMatch(
                chunk ->
                    chunk.sourcePath().equals("skills/cip-error-handling-generator/SKILL.md")
                        && chunk.phase() == CipKnowledgeCapabilityPhase.GENERATOR
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
                        && chunk.phase() == CipKnowledgeCapabilityPhase.UNSUPPORTED
                        && chunk.content().contains("Brainstorming Ideas Into Designs"));
    assertTrue(hasBrainstorming);

    List<String> sourcePaths =
        manifest.chunks().stream().map(CipKnowledgeRagChunk::sourcePath).toList();
    List<String> sorted = sourcePaths.stream().sorted().toList();
    assertEquals(sorted, sourcePaths);

    for (int i = 0; i < manifest.chunks().size(); i++) {
      assertEquals(i, manifest.chunks().get(i).ordinal());
    }
  }
}
