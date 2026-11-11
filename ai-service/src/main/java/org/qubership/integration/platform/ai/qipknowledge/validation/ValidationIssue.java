package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.qubership.integration.platform.ai.qipknowledge.artifact.CipKnowledgeCitation;
import java.util.List;

/** One validation issue raised against a plan or built chain. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationIssue(
    String issueId,
    ValidationSeverity severity,
    String message,
    String ownerCapabilityId,
    List<String> affectedNodeIds,
    List<CipKnowledgeCitation> ruleRefs,
    String suggestedFix,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String findingCode,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String originRecordId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String fieldPointer) {

  public ValidationIssue(
      String issueId, ValidationSeverity severity, String message, String ownerCapabilityId,
      List<String> affectedNodeIds, List<CipKnowledgeCitation> ruleRefs, String suggestedFix) {
    this(issueId, severity, message, ownerCapabilityId, affectedNodeIds, ruleRefs, suggestedFix,
        null, null, null);
  }
}
