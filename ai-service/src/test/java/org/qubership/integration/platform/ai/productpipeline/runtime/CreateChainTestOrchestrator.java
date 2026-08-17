package org.qubership.integration.platform.ai.productpipeline.runtime;

import io.smallrye.mutiny.Multi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Test stand-in for the Flow-backed orchestrator. Applies one stage at a time through {@link
 * ProductPipelineRunSupport} until a wait, failure, or completion. Production always uses Flow.
 */
public final class CreateChainTestOrchestrator implements CreateChainOrchestrator {

  private final ProductPipelineRunSupport support;
  private final ProductPipelineRunStore runStore;

  public CreateChainTestOrchestrator(
      ProductPipelineRunSupport support, ProductPipelineRunStore runStore) {
    this.support = Objects.requireNonNull(support, "support");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
  }

  public ProductPipelineRunSupport support() {
    return support;
  }

  public StageExecutor stageExecutor() {
    return support.stageExecutor();
  }

  public Multi<PipelineSignal> recordInput(AcceptInputCommand command) {
    return support.recordInput(command);
  }

  public Multi<PipelineSignal> recordApprove(ApproveCommand command) {
    return support.recordApprove(command);
  }

  public Multi<PipelineSignal> recordImplement(ImplementCommand command) {
    return support.recordImplement(command);
  }

  public Multi<PipelineSignal> executeStage(String runId, String expectedStageId) {
    StageExecutionResult result =
        support.stageExecutor().execute(runId, expectedStageId).await().indefinitely();
    return support.applyStageLifecycle(runId, result);
  }

  public Multi<PipelineSignal> restoreForExternalWorkflow(StartOrResumeCommand command) {
    return support.restoreForExternalWorkflow(command);
  }

  @Override
  public Multi<PipelineSignal> startOrResume(StartOrResumeCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              Optional<ProductPipelineRunDocument> existing =
                  runStore.loadByConversation(command.conversationId());
              if (existing.isPresent()) {
                return support
                    .restoreForExternalWorkflow(command)
                    .onCompletion()
                    .switchTo(
                        () -> {
                          ProductPipelineRunDocument doc =
                              runStore.loadByConversation(command.conversationId()).orElseThrow();
                          if (doc.run().status() == RunStatus.RUNNING) {
                            return loop(doc.run().runId());
                          }
                          return Multi.createFrom().empty();
                        });
              }
              support.bootstrap(command, "test-flow-" + command.runId());
              return loop(command.runId());
            });
  }

  @Override
  public Multi<PipelineSignal> acceptInput(AcceptInputCommand command) {
    return support.recordInput(command).onCompletion().switchTo(() -> loop(command.runId()));
  }

  @Override
  public Multi<PipelineSignal> approve(ApproveCommand command) {
    return support.recordApprove(command).onCompletion().switchTo(() -> loop(command.runId()));
  }

  @Override
  public Multi<PipelineSignal> implement(ImplementCommand command) {
    return support.recordImplement(command).onCompletion().switchTo(() -> loop(command.runId()));
  }

  @Override
  public Optional<String> approvedPlanContentHash(String runId) {
    return support.approvedPlanContentHash(runId);
  }

  @Override
  public Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId) {
    return support.latestCatalogChainSnapshot(runId);
  }

  private Multi<PipelineSignal> loop(String runId) {
    List<PipelineSignal> signals = new ArrayList<>();
    while (true) {
      ProductPipelineRunDocument doc = runStore.load(runId).orElseThrow();
      if (doc.run().status() != RunStatus.RUNNING) {
        break;
      }
      StageExecutionResult result =
          support
              .stageExecutor()
              .execute(runId, doc.run().currentStageId())
              .await()
              .indefinitely();
      signals.addAll(
          support.applyStageLifecycle(runId, result).collect().asList().await().indefinitely());
      if (!(result.decision() instanceof StageDecision.Continue)
          && !(result.decision() instanceof StageDecision.Retry)) {
        break;
      }
    }
    return Multi.createFrom().iterable(signals);
  }
}
