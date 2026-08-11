package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;

/** Records deterministic and compiler plan-validation findings for a candidate plan. */
public record PlanValidationResult(List<PlanValidationFinding> findings) {

  public PlanValidationResult {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /** Returns false when any finding is a blocker. */
  public boolean approvalEligible() {
    return findings.stream().noneMatch(PlanValidationFinding::blocker);
  }
}
