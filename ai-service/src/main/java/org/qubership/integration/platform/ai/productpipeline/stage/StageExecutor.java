package org.qubership.integration.platform.ai.productpipeline.stage;

import io.smallrye.mutiny.Uni;

/**
 * Executes at most one product-pipeline profile stage and returns one terminal decision.
 *
 * <p>The implementation resolves pinned inputs, applies skip and bypass policy, invokes one
 * capability, writes immutable artifacts and attempt evidence, and stops. It never selects the next
 * stage, sleeps for retry, suspends for a command, or invokes itself recursively.
 */
public interface StageExecutor {

  Uni<StageExecutionResult> execute(String runId, String expectedStageId);
}
