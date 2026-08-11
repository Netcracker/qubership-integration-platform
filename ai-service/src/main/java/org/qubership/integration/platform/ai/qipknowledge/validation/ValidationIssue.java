package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import java.util.List;

/** One validation issue raised against a plan or built chain. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationIssue(
    String issueId,
    ValidationSeverity severity,
    String message,
    String ownerCapabilityId,
    List<String> affectedNodeIds,
    List<QipKnowledgeCitation> ruleRefs,
    String suggestedFix) {}
