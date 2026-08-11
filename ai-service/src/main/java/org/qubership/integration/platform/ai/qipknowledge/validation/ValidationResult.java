package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Aggregate validation result for a planning or build step. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationResult(boolean valid, List<ValidationIssue> issues, String summary) {

  public boolean hasBlockingIssues() {
    return issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
  }
}
