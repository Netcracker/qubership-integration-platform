package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Citation declared by a deterministic artifact rule. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QipKnowledgeCitation(
    String refId,
    QipKnowledgeRefType refType,
    String sourcePath,
    QipKnowledgePackVersion packVersion,
    String snippet) {

  public static QipKnowledgeCitation declaredRule(
      String ruleId, QipKnowledgeRefType type) {
    return new QipKnowledgeCitation(ruleId, type, null, null, null);
  }
}
