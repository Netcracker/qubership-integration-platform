package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/** Result of applying one {@link GraphPatch} to a {@link ChainPlanGraph}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphPatchApplyResult(ChainPlanGraph graph, ValidationResult validationResult) {

  /** Returns true when the patch was applied without blocking issues. */
  public boolean applied() {
    return validationResult.valid() && !validationResult.hasBlockingIssues();
  }
}
