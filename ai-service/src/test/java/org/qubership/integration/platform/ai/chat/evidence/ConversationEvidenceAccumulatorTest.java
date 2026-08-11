package org.qubership.integration.platform.ai.chat.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

class ConversationEvidenceAccumulatorTest {

  private static KnowledgePackageRef packageRef(String checksum) {
    return new KnowledgePackageRef(
        "fixture@1.0.0",
        "1.0.0",
        "1.0.0",
        checksum,
        "CERTIFIED",
        "sha256:certificate");
  }

  @Test
  void recordKnowledgeUnionsIdsAndSumsCharsAcrossTurns() {
    var accumulator = new ConversationEvidenceAccumulator();
    KnowledgePackageRef ref = packageRef("sha256:package-a");

    accumulator.recordKnowledge(
        ref, List.of("CIP:GEN-000049", "CIP:STD-000001"), 120);
    accumulator.recordKnowledge(
        ref, List.of("CIP:STD-000001", "CIP:RULE-000001"), 80);

    EvidenceSnapshot.Knowledge knowledge =
        accumulator.toSnapshot("conversation-a").knowledge();
    assertEquals(ref, knowledge.packageRef());
    assertEquals(
        List.of("CIP:GEN-000049", "CIP:STD-000001", "CIP:RULE-000001"),
        knowledge.objectIds());
    assertEquals(200, knowledge.contentChars());
  }

  @Test
  void recordKnowledgeRejectsPackageChangeInsideConversation() {
    var accumulator = new ConversationEvidenceAccumulator();
    accumulator.recordKnowledge(
        packageRef("sha256:package-a"), List.of("CIP:GEN-000049"), 120);

    assertThrows(
        IllegalStateException.class,
        () ->
            accumulator.recordKnowledge(
                packageRef("sha256:package-b"), List.of("CIP:STD-000001"), 80));
  }
}
