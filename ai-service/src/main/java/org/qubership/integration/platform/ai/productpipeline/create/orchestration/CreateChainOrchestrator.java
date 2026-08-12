package org.qubership.integration.platform.ai.productpipeline.create.orchestration;

import io.smallrye.mutiny.Multi;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;

/**
 * Transport-neutral lifecycle operations used by the create-chain application facades.
 *
 * <p>Implementations preserve command idempotency and emit terminal signals for every completed
 * lifecycle operation.
 */
public interface CreateChainOrchestrator {

  Multi<PipelineSignal> startOrResume(StartOrResumeCommand command);

  Multi<PipelineSignal> acceptInput(AcceptInputCommand command);

  Multi<PipelineSignal> approve(ApproveCommand command);

  Multi<PipelineSignal> implement(ImplementCommand command);

  Optional<String> approvedPlanContentHash(String runId);

  Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId);
}
