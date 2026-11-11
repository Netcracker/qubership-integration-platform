package org.qubership.integration.platform.ai.productpipeline.artifact;

/** Records failure class, evidence, stage, attempt, and retry eligibility. */
public record FailureRecord(
    FailureClass failureClass,
    String stageId,
    String attemptId,
    String evidence,
    boolean retryEligible) {}
