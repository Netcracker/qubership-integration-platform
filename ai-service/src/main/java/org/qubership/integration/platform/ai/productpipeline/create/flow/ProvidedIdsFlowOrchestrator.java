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
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/** Runs the create-chain profile through Flow and uses the runtime only to execute durable stages. */
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
    Optional<ProductPipelineRunDocument> existing =
        runStore.loadByConversation(command.conversationId());
    if (existing.isPresent() && resumesInFlow(existing.get())) {
      String runId = existing.get().run().runId();
      ProvidedIdsFlow.RunInput input = tasks.begin(runId);
      return continueWithFlow(legacy.restoreForExternalWorkflow(command), input);
    }
    return legacy.startOrResume(command);
  }

  private boolean resumesInFlow(ProductPipelineRunDocument document) {
    return document.run().status() == RunStatus.RUNNING && belongsToFlow(document);
  }

  private boolean belongsToFlow(ProductPipelineRunDocument document) {
    return flow.ownsStage(document.run().currentStageId());
  }

  @Override
  public Multi<PipelineSignal> acceptInput(AcceptInputCommand command) {
    boolean continueInFlow = runStore.load(command.runId()).map(this::belongsToFlow).orElse(false);
    if (!continueInFlow) {
      return legacy.acceptInput(command);
    }

    ProvidedIdsFlow.RunInput input = tasks.begin(command.runId());
    return continueWithFlow(legacy.recordInput(command), input);
  }

  private Multi<PipelineSignal> continueWithFlow(
      Multi<PipelineSignal> precedingSignals, ProvidedIdsFlow.RunInput input) {
    return precedingSignals
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
              return Multi.createFrom().iterable(result.signals());
            });
  }

  @Override
  public Multi<PipelineSignal> approve(ApproveCommand command) {
    if (runStore.load(command.runId()).map(this::belongsToFlow).orElse(false)) {
      ProvidedIdsFlow.RunInput input = tasks.begin(command.runId());
      return continueWithFlow(legacy.recordApprove(command), input);
    }
    return legacy.approve(command);
  }

  @Override
  public Multi<PipelineSignal> implement(ImplementCommand command) {
    if (runStore.load(command.runId()).map(this::belongsToFlow).orElse(false)) {
      ProvidedIdsFlow.RunInput input = tasks.begin(command.runId());
      return continueWithFlow(legacy.recordImplement(command), input);
    }
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
