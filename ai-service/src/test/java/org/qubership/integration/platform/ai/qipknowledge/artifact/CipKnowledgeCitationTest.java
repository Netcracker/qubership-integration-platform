package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.CipKnowledgeRefType;

class CipKnowledgeCitationTest {

  @Test
  void createsDeclaredValidatorRuleCitation() {
    CipKnowledgeCitation citation =
        CipKnowledgeCitation.declaredRule(
            "VR-E-010",
            CipKnowledgeRefType.VALIDATION_RULE);

    assertEquals("VR-E-010", citation.refId());
    assertEquals(CipKnowledgeRefType.VALIDATION_RULE, citation.refType());
    assertNull(citation.sourcePath());
    assertNull(citation.packVersion());
    assertNull(citation.snippet());
  }
}
