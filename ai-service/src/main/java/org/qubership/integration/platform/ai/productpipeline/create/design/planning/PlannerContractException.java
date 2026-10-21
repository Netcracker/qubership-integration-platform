package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Planner failed its single format retry. Maps to {@link StageOutcomeClass#CONTRACT_FAILURE} in
 * {@code DesignPlanningCapability}.
 */
public final class PlannerContractException extends RuntimeException {

  private final List<DesignPlanContractFinding> findings;

  public PlannerContractException(String message) {
    this(message, List.of());
  }

  public PlannerContractException(
      String message, List<DesignPlanContractFinding> findings) {
    super(message);
    this.findings = findings == null ? List.of() : List.copyOf(findings);
  }

  public PlannerContractException(String message, Throwable cause) {
    super(message, cause);
    this.findings = List.of();
  }

  public StageOutcomeClass outcomeClass() {
    return StageOutcomeClass.CONTRACT_FAILURE;
  }

  public RecoveryCause recoveryCause() {
    List<PlanValidationFinding> evidence =
        findings.stream()
            .map(
                finding ->
                    new PlanValidationFinding(
                        finding.code().name(),
                        finding.evidenceIdentity(),
                        finding.blocking()))
            .toList();
    return RecoveryCause.fromFindings(evidence, outcomeClass());
  }
}
