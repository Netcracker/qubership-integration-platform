package org.qubership.integration.platform.ai.productpipeline.profile;

/** Technical-failure retry budget and default delay for a stage. */
public record RetryPolicy(int maxTechnicalRetries, long defaultDelayMs) {}
