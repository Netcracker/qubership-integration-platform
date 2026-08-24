package org.qubership.integration.platform.ai.productpipeline.create.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/** Runs create-chain as one persisted Flow instance per product run. */
public final class ProvidedIdsFlowOrchestrator implements CreateChainOrchestrator {

  private static final URI EVENT_SOURCE = URI.create("urn:qip:create-chain");
  private static final ObjectMapper JSON = new ObjectMapper();
  /** Live stage work includes LLM turns; keep this above the chat/A2A turn budget. */
  private static final Duration STAGE_SETTLE_TIMEOUT = Duration.ofMinutes(15);

  private final ProductPipelineRunSupport runSupport;
  private final ProductPipelineRunStore runStore;
  private final ProvidedIdsFlow flow;
  private final ProvidedIdsFlowTasks tasks;
  private final WorkflowApplication application;

  public ProvidedIdsFlowOrchestrator(
      ProductPipelineRunSupport runSupport,
      ProductPipelineRunStore runStore,
      ProvidedIdsFlow flow,
      ProvidedIdsFlowTasks tasks,
      WorkflowApplication application) {
    this.runSupport = Objects.requireNonNull(runSupport, "runSupport");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.flow = Objects.requireNonNull(flow, "flow");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.application = Objects.requireNonNull(application, "application");
  }

  @Override
  public Multi<PipelineSignal> startOrResume(StartOrResumeCommand command) {
    Optional<ProductPipelineRunDocument> existing =
        runStore.loadByConversation(command.conversationId());
    if (existing.isEmpty()) {
      return startPersistedInstance(command);
    }
    ProductPipelineRunDocument document = existing.get();
    rejectUnboundManualRun(document);
    return runSupport.restoreForExternalWorkflow(command);
  }

  private Multi<PipelineSignal> startPersistedInstance(StartOrResumeCommand command) {
    return streamWhileSettling(
        command.runId(),
        () -> {
          ProvidedIdsFlow.RunContext context = contextOf(command);
          WorkflowInstance instance = flow.instance(context);
          runSupport.bootstrap(command, instance.id());
          instance.start();
          waitUntil(
              "create-chain Flow instance " + instance.id() + " must reach a listen wait",
              () ->
                  instance.status() == WorkflowStatus.WAITING
                      || instance.status() == WorkflowStatus.COMPLETED
                      || instance.status() == WorkflowStatus.FAULTED);
          List<PipelineSignal> live = tasks.drainSignals(command.runId());
          if (!live.isEmpty()) {
            return live;
          }
          return runSupport
              .restoreForExternalWorkflow(command)
              .collect()
              .asList()
              .await()
              .indefinitely();
        });
  }

  @Override
  public Multi<PipelineSignal> acceptInput(AcceptInputCommand command) {
    ProductPipelineRunDocument document = requireRun(command.runId());
    rejectUnboundManualRun(document);
    boolean alreadyApplied =
        document.appliedCommand(command.commandId(), command.commandPayloadHash()).isPresent();
    return resumeBoundInstance(
        runSupport.recordInput(command),
        command.runId(),
        document.run().flowInstanceId(),
        alreadyApplied,
        ProvidedIdsFlow.INPUT_EVENT_TYPE,
        RunStatus.WAITING_FOR_INPUT);
  }

  @Override
  public Multi<PipelineSignal> approve(ApproveCommand command) {
    ProductPipelineRunDocument document = requireRun(command.runId());
    rejectUnboundManualRun(document);
    boolean alreadyApplied =
        document.appliedCommand(command.commandId(), command.commandPayloadHash()).isPresent();
    return resumeBoundInstance(
        runSupport.recordApprove(command),
        command.runId(),
        document.run().flowInstanceId(),
        alreadyApplied,
        ProvidedIdsFlow.APPROVAL_EVENT_TYPE,
        RunStatus.WAITING_FOR_APPROVAL);
  }

  @Override
  public Multi<PipelineSignal> implement(ImplementCommand command) {
    ProductPipelineRunDocument document = requireRun(command.runId());
    rejectUnboundManualRun(document);
    boolean alreadyApplied =
        document.appliedCommand(command.commandId(), command.commandPayloadHash()).isPresent();
    return resumeBoundInstance(
        runSupport.recordImplement(command),
        command.runId(),
        document.run().flowInstanceId(),
        alreadyApplied,
        ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE,
        RunStatus.WAITING_FOR_IMPLEMENT);
  }

  @Override
  public Optional<String> approvedPlanContentHash(String runId) {
    return runSupport.approvedPlanContentHash(runId);
  }

  @Override
  public Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId) {
    return runSupport.latestCatalogChainSnapshot(runId);
  }

  private ProductPipelineRunDocument requireRun(String runId) {
    return runStore
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
  }

  private static void rejectUnboundManualRun(ProductPipelineRunDocument document) {
    if (document.run().flowInstanceId() != null) {
      return;
    }
    throw new IllegalStateException(
        "Create-chain run "
            + document.run().runId()
            + " has no Flow instance. Unfinished manual-runtime runs cannot resume after cutover. Stored evidence is unchanged.");
  }

  private static ProvidedIdsFlow.RunContext contextOf(StartOrResumeCommand command) {
    return new ProvidedIdsFlow.RunContext(
        command.runId(),
        command.profile().profileId(),
        command.profile().profileVersion(),
        command.runManifest().profileDigest(),
        null);
  }

  private Multi<PipelineSignal> resumeBoundInstance(
      Multi<PipelineSignal> recorded,
      String runId,
      String flowInstanceId,
      boolean alreadyApplied,
      String eventType,
      RunStatus waitingStatus) {
    return recorded
        .onCompletion()
        .switchTo(
            () ->
                streamWhileSettling(
                    runId,
                    () -> {
                      ProductPipelineRunDocument afterRecord =
                          runStore.load(runId).orElseThrow();
                      if (afterRecord.run().status() == waitingStatus) {
                        return tasks.drainSignals(runId);
                      }
                      if (alreadyApplied && afterRecord.run().status() != waitingStatus) {
                        return tasks.drainSignals(runId);
                      }
                      publishCorrelatedEvent(
                          eventType, flowInstanceId, contextFrom(afterRecord));
                      waitUntil(
                          "create-chain Flow instance " + flowInstanceId + " must resume",
                          () -> tasks.settled(runId));
                      return tasks.drainSignals(runId);
                    }));
  }

  /**
   * Emits {@link PipelineSignalLiveSink} rows while Flow is still blocked in {@code waitUntil},
   * then appends the drained remainder so terminal waits/completions still arrive.
   *
   * <p>Cancelling the chat subscription interrupts the wait and unbinds the live sink so later
   * rows do not hit a disposed emitter.
   */
  private Multi<PipelineSignal> streamWhileSettling(
      String runId, Supplier<List<PipelineSignal>> waitThenDrain) {
    return Multi.createFrom()
        .emitter(
            emitter -> {
              AtomicReference<Thread> worker = new AtomicReference<>();
              AtomicReference<Cancellable> subscription = new AtomicReference<>();
              emitter.onTermination(
                  () -> {
                    Thread running = worker.get();
                    if (running != null) {
                      running.interrupt();
                    }
                    Cancellable cancellable = subscription.get();
                    if (cancellable != null) {
                      cancellable.cancel();
                    }
                    PipelineSignalLiveSink.unbind(runId);
                  });
              subscription.set(
                  Uni.createFrom()
                      .item(
                          () -> {
                            worker.set(Thread.currentThread());
                            PipelineSignalLiveSink.bind(
                                runId,
                                signal -> {
                                  if (!emitter.isCancelled()) {
                                    emitter.emit(signal);
                                  }
                                });
                            try {
                              return waitThenDrain.get();
                            } finally {
                              worker.set(null);
                              PipelineSignalLiveSink.unbind(runId);
                            }
                          })
                      .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                      .subscribe()
                      .with(
                          drained -> {
                            if (emitter.isCancelled()) {
                              return;
                            }
                            if (drained != null) {
                              for (PipelineSignal signal : drained) {
                                if (emitter.isCancelled()) {
                                  return;
                                }
                                emitter.emit(signal);
                              }
                            }
                            emitter.complete();
                          },
                          failure -> {
                            if (!emitter.isCancelled()) {
                              emitter.fail(failure);
                            }
                          }));
            });
  }

  private void publishCorrelatedEvent(
      String eventType, String flowInstanceId, ProvidedIdsFlow.RunContext context) {
    byte[] data;
    try {
      data = JSON.writeValueAsBytes(context);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize create-chain resume event", e);
    }
    CloudEvent event =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(EVENT_SOURCE)
            .withType(eventType)
            .withExtension("flowinstanceid", flowInstanceId)
            .withData("application/json", data)
            .build();
    List<EventPublisher> publishers = List.copyOf(application.eventPublishers());
    if (publishers.isEmpty()) {
      throw new IllegalStateException(
          "no in-process Flow event publisher is registered; cannot resume instance "
              + flowInstanceId);
    }
    for (EventPublisher publisher : publishers) {
      publisher.publish(event).toCompletableFuture().join();
    }
  }

  private static ProvidedIdsFlow.RunContext contextFrom(ProductPipelineRunDocument document) {
    return new ProvidedIdsFlow.RunContext(
        document.run().runId(), null, null, null, decisionFrom(document));
  }

  private static String decisionFrom(ProductPipelineRunDocument document) {
    return switch (document.run().status()) {
      case WAITING_FOR_IMPLEMENT -> "WAIT_FOR_IMPLEMENTATION";
      case WAITING_FOR_APPROVAL -> approvalDecision(document.run().currentStageId());
      case WAITING_FOR_INPUT -> "WAIT_FOR_INPUT";
      default -> "CONTINUE";
    };
  }

  private static String approvalDecision(String stageId) {
    if ("requirement-analysis".equals(stageId)) {
      return "WAIT_FOR_REQUIREMENT_APPROVAL";
    }
    if ("design-input".equals(stageId)) {
      return "WAIT_FOR_IDS_APPROVAL";
    }
    if ("design-planning".equals(stageId)) {
      return "WAIT_FOR_PLAN_APPROVAL";
    }
    return "CONTINUE";
  }

  private static void waitUntil(String message, BooleanSupplier condition) {
    long deadline = System.nanoTime() + STAGE_SETTLE_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting: " + message);
      }
    }
    throw new IllegalStateException(message);
  }
}
