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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.PreparedRestart;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RestartCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.runtime.RestartRunCommand;
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

  @Override
  public List<RestartCheckpoint> availableRestartCheckpoints(String conversationId) {
    return runSupport.availableRestartCheckpoints(conversationId);
  }

  @Override
  public Multi<PipelineSignal> restart(RestartRunCommand command) {
    return streamWhileSettling(
        command.childRunId(),
        () -> {
          ProductPipelineRunDocument active =
              runStore
                  .loadByConversation(command.conversationId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "unknown conversation: " + command.conversationId()));
          if (command.childRunId().equals(active.run().runId())) {
            PreparedRestart prepared =
                runSupport.prepareCheckpointRestart(
                    command, active.run().flowInstanceId());
            if (active.run().status() == RunStatus.RUNNING) {
              publishCorrelatedEvent(
                  ProvidedIdsFlow.ACTIVATION_EVENT_TYPE,
                  active.run().flowInstanceId(),
                  activationContext(command));
              waitUntil(
                  "restarted create-chain Flow instance "
                      + active.run().flowInstanceId()
                      + " must settle after activation",
                  () -> tasks.settled(command.childRunId()));
            }
            return runSupport
                .restoreForExternalWorkflow(
                    new StartOrResumeCommand(
                        command.conversationId(),
                        command.childRunId(),
                        command.profile(),
                        prepared.runManifest()))
                .collect()
                .asList()
                .await()
                .indefinitely();
          }
          if (!command.parentRunId().equals(active.run().runId())) {
            throw new org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException(
                "expected active run "
                    + command.parentRunId()
                    + " but conversation has "
                    + active.run().runId());
          }
          ProvidedIdsFlow.RunContext context =
              new ProvidedIdsFlow.RunContext(
                  command.childRunId(),
                  command.profile().profileId(),
                  command.profile().profileVersion(),
                  command.profile().profileId() + "@" + command.profile().profileVersion(),
                  "WAIT_FOR_ACTIVATION");
          WorkflowInstance instance = flow.instance(context);
          runSupport.prepareCheckpointRestart(command, instance.id());
          instance.start();
          waitUntil(
              "restarted create-chain Flow instance " + instance.id() + " must await activation",
              () ->
                  instance.status() == WorkflowStatus.WAITING
                      || instance.status() == WorkflowStatus.COMPLETED
                      || instance.status() == WorkflowStatus.FAULTED);
          if (instance.status() == WorkflowStatus.FAULTED) {
            throw new IllegalStateException(
                "restarted create-chain Flow instance "
                    + instance.id()
                    + " faulted before activation");
          }
          runStore.replaceConversationBinding(
              command.conversationId(),
              command.parentRunId(),
              command.parentRunRevision(),
              command.childRunId());
          try {
            publishCorrelatedEvent(
                ProvidedIdsFlow.ACTIVATION_EVENT_TYPE,
                instance.id(),
                activationContext(command));
          } catch (RuntimeException activationFailure) {
            try {
              runStore.replaceConversationBinding(
                  command.conversationId(), command.childRunId(), command.parentRunId());
            } catch (RuntimeException rollbackFailure) {
              activationFailure.addSuppressed(rollbackFailure);
            }
            throw activationFailure;
          }
          waitUntil(
              "restarted create-chain Flow instance "
                  + instance.id()
                  + " must settle after activation",
              () ->
                  tasks.settled(command.childRunId())
                      || instance.status() == WorkflowStatus.COMPLETED
                      || instance.status() == WorkflowStatus.FAULTED);
          List<PipelineSignal> live = tasks.drainSignals(command.childRunId());
          if (!live.isEmpty()) {
            return live;
          }
          ProductPipelineRunDocument child =
              runStore.load(command.childRunId()).orElseThrow();
          org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest manifest =
              runSupport.prepareCheckpointRestart(command, instance.id()).runManifest();
          return runSupport
              .restoreForExternalWorkflow(
                  new StartOrResumeCommand(
                      command.conversationId(),
                      child.run().runId(),
                      command.profile(),
                      manifest))
              .collect()
              .asList()
              .await()
              .indefinitely();
        });
  }

  private static ProvidedIdsFlow.RunContext activationContext(RestartRunCommand command) {
    return new ProvidedIdsFlow.RunContext(
        command.childRunId(),
        command.profile().profileId(),
        command.profile().profileVersion(),
        command.profile().profileId() + "@" + command.profile().profileVersion(),
        null);
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
  public Multi<PipelineSignal> acceptInput(AcceptInputCommand input) {
    AcceptInputCommand command = ProductPipelineRunSupport.bindRepairOffer(input);
    ProductPipelineRunDocument document = requireRun(command.runId());
    rejectUnboundManualRun(document);
    boolean alreadyApplied =
        document.appliedCommand(command.commandId(), command.commandPayloadHash()).isPresent();
    if (alreadyApplied && org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.CONTINUE_REPAIR_ACTION
        .equals(org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.haltCardAction(command.text()))) {
      return Multi.createFrom().empty();
    }
    return resumeBoundInstance(
        runSupport.recordInput(command),
        command.runId(),
        document.run().flowInstanceId(),
        alreadyApplied,
        ProvidedIdsFlow.INPUT_EVENT_TYPE,
        RunStatus.WAITING_FOR_INPUT,
        org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.CONTINUE_REPAIR_ACTION.equals(
            org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.haltCardAction(command.text())));
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
        RunStatus.WAITING_FOR_APPROVAL, false);
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
        RunStatus.WAITING_FOR_IMPLEMENT, false);
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
      RunStatus waitingStatus,
      boolean requireAcceptedGrant) {
    return streamWhileSettling(
        runId,
        () -> {
          List<PipelineSignal> recordedSignals = recorded
              .onItem()
              .invoke(signal -> PipelineSignalLiveSink.emit(runId, signal))
              .collect()
              .asList()
              .await()
              .indefinitely();
          if (requireAcceptedGrant && recordedSignals.stream().noneMatch(PipelineSignal.Progress.class::isInstance)) {
            return List.of();
          }
          ProductPipelineRunDocument afterRecord = runStore.load(runId).orElseThrow();
          if (afterRecord.run().status() == waitingStatus && !alreadyApplied) {
            return tasks.drainSignals(runId);
          }
          publishCorrelatedEvent(eventType, flowInstanceId, contextFrom(afterRecord));
          // Applied commands can already be at another wait whose listener ignores this event.
          if (!alreadyApplied || afterRecord.run().status() == RunStatus.RUNNING) {
            waitUntil(
                "create-chain Flow instance " + flowInstanceId + " must resume",
                () -> tasks.settled(runId));
          }
          return tasks.drainSignals(runId);
        });
  }

  /**
   * Emits {@link PipelineSignalLiveSink} rows while Flow is still blocked in {@code waitUntil},
   * then appends the drained remainder so terminal waits/completions still arrive.
   *
   * <p>Disconnecting detaches the observer. Accepted command recording and dispatch continue on
   * the server, so closing a chat cannot strand a committed command before its resume event.
   */
  private Multi<PipelineSignal> streamWhileSettling(
      String runId, Supplier<List<PipelineSignal>> waitThenDrain) {
    return Multi.createFrom()
        .emitter(
            emitter -> {
              Consumer<PipelineSignal> observer =
                  signal -> {
                    if (!emitter.isCancelled()) {
                      emitter.emit(signal);
                    }
                  };
              emitter.onTermination(() -> PipelineSignalLiveSink.unbind(runId, observer));
              Uni.createFrom()
                  .item(
                      () -> {
                        if (!emitter.isCancelled()) {
                          PipelineSignalLiveSink.bind(runId, observer);
                        }
                        try {
                          return waitThenDrain.get();
                        } finally {
                          PipelineSignalLiveSink.unbind(runId, observer);
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
                      });
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
        document.run().runId(), null, null, null, ProvidedIdsFlow.decisionFrom(document.run()));
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
