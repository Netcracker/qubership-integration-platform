package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Planner failed its single format retry. Maps to {@link StageOutcomeClass#CONTRACT_FAILURE} in
 * {@code DesignPlanningCapability}.
 */
public final class PlannerContractException extends RuntimeException {

  public PlannerContractException(String message) {
    super(message);
  }

  public PlannerContractException(String message, Throwable cause) {
    super(message, cause);
  }

  public StageOutcomeClass outcomeClass() {
    return StageOutcomeClass.CONTRACT_FAILURE;
  }
}
