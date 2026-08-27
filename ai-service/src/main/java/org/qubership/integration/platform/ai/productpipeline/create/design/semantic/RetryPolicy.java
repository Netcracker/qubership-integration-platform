package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

/** Retry count and delay for one operation occurrence. Both fields serialize as JSON numbers. */
public record RetryPolicy(int retryCount, int retryDelayMillis) {}
