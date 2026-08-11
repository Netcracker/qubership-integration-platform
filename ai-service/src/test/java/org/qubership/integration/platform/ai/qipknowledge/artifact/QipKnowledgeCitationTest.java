package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;

class QipKnowledgeCitationTest {

  @Test
  void createsDeclaredValidatorRuleCitation() {
    QipKnowledgeCitation citation =
        QipKnowledgeCitation.declaredRule(
            "VR-E-010",
            QipKnowledgeRefType.VALIDATION_RULE);

    assertEquals("VR-E-010", citation.refId());
    assertEquals(QipKnowledgeRefType.VALIDATION_RULE, citation.refType());
    assertNull(citation.sourcePath());
    assertNull(citation.packVersion());
    assertNull(citation.snippet());
  }
}
