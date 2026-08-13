package org.qubership.integration.platform.ai.productpipeline.create.flow;

import io.smallrye.mutiny.Multi;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/** Selects the provided-IDS Flow at its entry stage and delegates every other route to legacy. */
public final class ProvidedIdsFlowOrchestrator implements CreateChainOrchestrator {

  private final ProductPipelineRuntime legacy;
  private final ProductPipelineRunStore runStore;
  private final ProvidedIdsFlow flow;
  private final ProvidedIdsFlowTasks tasks;

  public ProvidedIdsFlowOrchestrator(
      ProductPipelineRuntime legacy,
      ProductPipelineRunStore runStore,
      ProvidedIdsFlow flow,
      ProvidedIdsFlowTasks tasks) {
    this.legacy = Objects.requireNonNull(legacy, "legacy");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.flow = Objects.requireNonNull(flow, "flow");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
  }

  @Override
  public Multi<PipelineSignal> startOrResume(StartOrResumeCommand command) {
    return legacy.startOrResume(command);
  }

  @Override
  public Multi<PipelineSignal> acceptInput(AcceptInputCommand command) {
    boolean atEntry =
        runStore
            .load(command.runId())
            .map(document -> "ids-entry".equals(document.run().currentStageId()))
            .orElse(false);
    if (!atEntry) {
      return legacy.acceptInput(command);
    }

    ProvidedIdsFlow.RunInput input = tasks.begin(command.runId());
    return legacy
        .recordInput(command)
        .onCompletion()
        .switchTo(() -> runFlow(input))
        .onFailure()
        .invoke(() -> tasks.discard(input))
        .onCancellation()
        .invoke(() -> tasks.discard(input));
  }

  private Multi<PipelineSignal> runFlow(ProvidedIdsFlow.RunInput input) {
    return flow
        .startInstance(input)
        .onItem()
        .transformToMulti(
            ignored -> {
              ProvidedIdsFlowTasks.Result result = tasks.finish(input);
              Multi<PipelineSignal> signals = Multi.createFrom().iterable(result.signals());
              return result.standardRoute()
                  ? signals.onCompletion().switchTo(() -> legacy.continueRun(input.runId()))
                  : signals;
            });
  }

  @Override
  public Multi<PipelineSignal> approve(ApproveCommand command) {
    return legacy.approve(command);
  }

  @Override
  public Multi<PipelineSignal> implement(ImplementCommand command) {
    return legacy.implement(command);
  }

  @Override
  public Optional<String> approvedPlanContentHash(String runId) {
    return legacy.approvedPlanContentHash(runId);
  }

  @Override
  public Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId) {
    return legacy.latestCatalogChainSnapshot(runId);
  }
}
