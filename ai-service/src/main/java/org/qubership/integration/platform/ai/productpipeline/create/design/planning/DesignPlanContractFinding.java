package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;

/** Stable machine identity and readable details for one rejected design-plan contract fact. */
public record DesignPlanContractFinding(
    Code code,
    TargetKind targetKind,
    String targetId,
    String stepId,
    boolean blocking,
    String message) {

  public enum Code {
    CAPTURE_MISSING,
    CAPTURE_SHAPE_INVALID,
    DUPLICATE_STEP_ID,
    DUPLICATE_STEP_CLAIM,
    UNKNOWN_TARGET,
    TARGET_KIND_MISMATCH,
    OWNER_TARGET_MISMATCH,
    MISSING_REQUIRED_OWNER,
    MISSING_TARGET_PRODUCER,
    DUPLICATE_TARGET_PRODUCER,
    UNKNOWN_STEP_DEPENDENCY,
    SELF_STEP_DEPENDENCY,
    STEP_DEPENDENCY_CYCLE,
    COMPILER_DEPENDENCY_MISSING,
    UNKNOWN_OWNER
  }

  public DesignPlanContractFinding {
    code = DesignArtifacts.requireNonNull(code, "code");
    targetId = DesignArtifacts.nullableTrimmed(targetId);
    stepId = DesignArtifacts.nullableTrimmed(stepId);
    message = DesignArtifacts.requireText(message, "message");
  }

  public String evidenceIdentity() {
    return code
        + ":"
        + (targetKind == null ? "" : targetKind)
        + ":"
        + targetId
        + ":"
        + stepId;
  }
}
