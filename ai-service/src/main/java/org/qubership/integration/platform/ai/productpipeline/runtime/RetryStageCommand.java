package org.qubership.integration.platform.ai.productpipeline.runtime;

/** Manually retries a failed stage without refreshing pinned profile or dependencies. */
public record RetryStageCommand(String runId) {}
