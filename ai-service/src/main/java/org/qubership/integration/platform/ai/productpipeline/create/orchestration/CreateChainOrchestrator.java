package org.qubership.integration.platform.ai.productpipeline.create.orchestration;

import io.smallrye.mutiny.Multi;
import java.util.Optional;
import java.util.List;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.RestartCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.runtime.RestartRunCommand;

/**
 * Transport-neutral lifecycle operations used by the create-chain application facades.
 *
 * <p>Implementations preserve command idempotency and emit terminal signals for every completed
 * lifecycle operation.
 */
public interface CreateChainOrchestrator {

  Multi<PipelineSignal> startOrResume(StartOrResumeCommand command);

  /** Creates a child run at a durable checkpoint and makes it active for the conversation. */
  default Multi<PipelineSignal> restart(RestartRunCommand command) {
    return Multi.createFrom().failure(new UnsupportedOperationException("restart is unavailable"));
  }

  /** Durable checkpoints currently available for this conversation. */
  default List<RestartCheckpoint> availableRestartCheckpoints(String conversationId) {
    return List.of();
  }

  Multi<PipelineSignal> acceptInput(AcceptInputCommand command);

  Multi<PipelineSignal> approve(ApproveCommand command);

  Multi<PipelineSignal> implement(ImplementCommand command);

  Optional<String> approvedPlanContentHash(String runId);

  Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId);
}
