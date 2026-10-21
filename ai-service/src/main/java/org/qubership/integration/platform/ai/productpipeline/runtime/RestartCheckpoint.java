package org.qubership.integration.platform.ai.productpipeline.runtime;

/** Durable point from which a derived create-chain run can resume. */
public enum RestartCheckpoint {
  BEGINNING("restart-from-beginning", null, "ids-entry"),
  APPROVED_REQUIREMENTS(
      "restart-from-approved-requirements", "requirement-analysis", "design-input"),
  APPROVED_PLAN("restart-from-approved-plan", "design-planning", "design-execution");

  private final String actionId;
  private final String checkpointStageId;
  private final String resumeStageId;

  RestartCheckpoint(String actionId, String checkpointStageId, String resumeStageId) {
    this.actionId = actionId;
    this.checkpointStageId = checkpointStageId;
    this.resumeStageId = resumeStageId;
  }

  public String actionId() {
    return actionId;
  }

  public String checkpointStageId() {
    return checkpointStageId;
  }

  public String resumeStageId() {
    return resumeStageId;
  }

  public static java.util.Optional<RestartCheckpoint> fromActionId(String actionId) {
    return java.util.Arrays.stream(values())
        .filter(checkpoint -> checkpoint.actionId.equals(actionId))
        .findFirst();
  }
}
