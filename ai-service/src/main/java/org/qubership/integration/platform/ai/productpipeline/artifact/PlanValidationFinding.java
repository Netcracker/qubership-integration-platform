package org.qubership.integration.platform.ai.productpipeline.artifact;

/** One deterministic or compiler plan-validation finding. */
public record PlanValidationFinding(String code, String message, boolean blocker) {}
