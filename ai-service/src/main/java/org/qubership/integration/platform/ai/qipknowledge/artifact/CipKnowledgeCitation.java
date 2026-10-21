package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.CipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

/** Citation declared by a deterministic artifact rule. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CipKnowledgeCitation(
    String refId,
    CipKnowledgeRefType refType,
    String sourcePath,
    CipKnowledgePackVersion packVersion,
    String snippet) {

  public static CipKnowledgeCitation declaredRule(
      String ruleId, CipKnowledgeRefType type) {
    return new CipKnowledgeCitation(ruleId, type, null, null, null);
  }
}
