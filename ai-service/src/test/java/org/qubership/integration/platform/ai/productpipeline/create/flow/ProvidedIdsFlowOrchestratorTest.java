package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cloudevents.CloudEvent;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.smallrye.mutiny.Multi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

class ProvidedIdsFlowOrchestratorTest {

  private static final String RUN_ID = "run-1";
  private static final String CONVERSATION_ID = "conversation-1";

  private ProductPipelineRunSupport runSupport;
  private ProductPipelineRunStore runStore;
  private ProvidedIdsFlow flow;
  private ProvidedIdsFlowTasks tasks;
  private WorkflowApplication application;
  private ProvidedIdsFlowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    runSupport = mock(ProductPipelineRunSupport.class);
    runStore = mock(ProductPipelineRunStore.class);
    flow = mock(ProvidedIdsFlow.class);
    tasks = mock(ProvidedIdsFlowTasks.class);
    application = mock(WorkflowApplication.class);
    when(application.eventPublishers()).thenReturn(List.of());
    orchestrator = new ProvidedIdsFlowOrchestrator(runSupport, runStore, flow, tasks, application);
  }

  @Test
  void unfinishedManualRuntimeRunFailsOnStartWithoutMutationOrReplacement() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(command.runId()).thenReturn(RUN_ID);
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> orchestrator.startOrResume(command).collect().asList().await().indefinitely());

    assertTrue(thrown.getMessage().contains(RUN_ID));
    assertTrue(thrown.getMessage().contains("no Flow instance"));
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(runSupport, never()).recordInput(any());
    verify(runSupport, never()).restoreForExternalWorkflow(command);
    verify(runStore, never()).commit(anyLong(), any());
  }

  @Test
  void unfinishedManualRuntimeRunFailsOnInputWithoutMutationOrReplacement() {
    AcceptInputCommand command = new AcceptInputCommand(RUN_ID, "provided IDS");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> orchestrator.acceptInput(command).collect().asList().await().indefinitely());

    assertTrue(thrown.getMessage().contains(RUN_ID));
    assertTrue(thrown.getMessage().contains("no Flow instance"));
    verify(runSupport, never()).recordInput(command);
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(runStore, never()).commit(anyLong(), any());
  }

  @Test
  void unfinishedManualRuntimeRunFailsOnApproveWithoutMutationOrReplacement() {
    ApproveCommand command = mock(ApproveCommand.class);
    when(command.runId()).thenReturn(RUN_ID);
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_APPROVAL, "design-input")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> orchestrator.approve(command).collect().asList().await().indefinitely());

    assertTrue(thrown.getMessage().contains(RUN_ID));
    assertTrue(thrown.getMessage().contains("no Flow instance"));
    verify(runSupport, never()).recordApprove(command);
    verify(flow, never()).startInstance(any());
    verify(runStore, never()).commit(anyLong(), any());
  }

  @Test
  void unfinishedManualRuntimeRunFailsOnImplementWithoutMutationOrReplacement() {
    ImplementCommand command = new ImplementCommand(RUN_ID, "plan-sha", 3L);
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> orchestrator.implement(command).collect().asList().await().indefinitely());

    assertTrue(thrown.getMessage().contains(RUN_ID));
    assertTrue(thrown.getMessage().contains("no Flow instance"));
    verify(runSupport, never()).recordImplement(command);
    verify(flow, never()).startInstance(any());
    verify(runStore, never()).commit(anyLong(), any());
  }

  @Test
  void startCreatesOneFlowInstanceAndDoesNotUseStartInstance() {
    StartOrResumeCommand command = startCommand();
    when(runStore.loadByConversation(CONVERSATION_ID)).thenReturn(Optional.empty());
    WorkflowInstance instance = mock(WorkflowInstance.class);
    when(instance.id()).thenReturn("flow-1");
    when(instance.status()).thenReturn(WorkflowStatus.WAITING);
    when(instance.start()).thenReturn(CompletableFuture.completedFuture(mock(WorkflowModel.class)));
    when(flow.instance(any(ProvidedIdsFlow.RunContext.class))).thenReturn(instance);
    when(runSupport.bootstrap(eq(command), eq("flow-1")))
        .thenReturn(document(RunStatus.WAITING_FOR_INPUT, "ids-entry", "flow-1"));
    PipelineSignal waiting = new PipelineSignal.WaitingForInput("ids-entry", "need input");
    when(runSupport.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().item(waiting));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(waiting), actual);
    verify(flow).instance(any(ProvidedIdsFlow.RunContext.class));
    verify(flow, never()).startInstance(any());
    verify(runSupport).bootstrap(command, "flow-1");
    verify(instance).start();
  }

  @Test
  void openingInputPublishesCorrelatedEventInsteadOfStartingAnotherInstance() {
    AcceptInputCommand command = new AcceptInputCommand(RUN_ID, "provided IDS", "cmd-1", "hash-1");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry", "flow-1")));
    // Accepting input leaves the run RUNNING, the way recordInput commits it in production. The
    // orchestrator only publishes a resume event when the run moved off the wait, so a stub that
    // leaves the store waiting models a halt follow-up instead of accepted input.
    when(runSupport.recordInput(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(
                                      RunStatus.RUNNING, "requirement-discovery", "flow-1", 4L)));
                      return Multi.createFrom().empty();
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal progressed = new PipelineSignal.Message("IDS accepted");
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(progressed));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    verify(runSupport).recordInput(command);
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(1, published.size());
    assertEquals(ProvidedIdsFlow.INPUT_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(progressed), actual);
  }

  @Test
  void inputThatLeavesTheRunWaitingStreamsHaltSignalsWithoutResumingTheInstance() {
    AcceptInputCommand command =
        new AcceptInputCommand(RUN_ID, "retry after halt", "cmd-halt-1", "hash-halt-1");
    // A recoverable halt parks Flow back on the same wait, so recordInput keeps the stored status
    // at WAITING_FOR_INPUT. Publishing a resume event here would push a second event at an
    // instance that never left the listen.
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry", "flow-1")));
    when(runSupport.recordInput(command)).thenReturn(Multi.createFrom().empty());
    EventPublisher publisher = mock(EventPublisher.class);
    when(publisher.publish(any(CloudEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal halted = new PipelineSignal.WaitingForInput("ids-entry", "correct the IDS");
    // Settled so that a regression fails on the publish check rather than parking in the wait.
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(halted));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    assertEquals(List.of(halted), actual);
    verify(runSupport).recordInput(command);
    verify(publisher, never()).publish(any(CloudEvent.class));
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
  }

  @Test
  void requirementAnalysisApprovalPublishesCorrelatedEventInsteadOfStartingAnotherInstance() {
    ApproveCommand command = mock(ApproveCommand.class);
    when(command.runId()).thenReturn(RUN_ID);
    when(command.commandId()).thenReturn("cmd-approve-brief");
    when(command.commandPayloadHash()).thenReturn("hash-approve-brief");
    when(runStore.load(RUN_ID))
        .thenReturn(
            Optional.of(document(RunStatus.WAITING_FOR_APPROVAL, "requirement-analysis", "flow-1")));
    when(runSupport.recordApprove(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(RunStatus.RUNNING, "design-input", "flow-1", 5L)));
                      return Multi.createFrom().empty();
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal progressed = new PipelineSignal.Message("brief approved");
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(progressed));

    List<PipelineSignal> actual =
        orchestrator.approve(command).collect().asList().await().indefinitely();

    verify(runSupport).recordApprove(command);
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(ProvidedIdsFlow.APPROVAL_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(progressed), actual);
  }

  @Test
  void idsPathChoiceInputPublishesCorrelatedEventInsteadOfStartingAnotherInstance() {
    AcceptInputCommand command =
        new AcceptInputCommand(RUN_ID, "Generate full IDS", "cmd-ids-path", "hash-ids-path");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "design-input", "flow-1")));
    when(runSupport.recordInput(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(RunStatus.RUNNING, "design-input", "flow-1", 6L)));
                      return Multi.createFrom().empty();
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal progressed = new PipelineSignal.Message("IDS path selected");
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(progressed));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    verify(runSupport).recordInput(command);
    verify(flow, never()).startInstance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(ProvidedIdsFlow.INPUT_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(progressed), actual);
  }

  @Test
  void restartAtRequirementApprovalRestoresBoundPendingAction() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    PipelineSignal waiting =
        new PipelineSignal.WaitingForApproval("requirement-analysis", null, "approve brief");
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(
            Optional.of(
                document(RunStatus.WAITING_FOR_APPROVAL, "requirement-analysis", "flow-1")));
    when(runSupport.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().item(waiting));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(waiting), actual);
    verify(runSupport).restoreForExternalWorkflow(command);
    verify(flow, never()).startInstance(any());
  }

  @Test
  void restartAtIdsPathChoiceRestoresBoundPendingAction() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    PipelineSignal waiting = new PipelineSignal.WaitingForInput("design-input", "IDS path?");
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "design-input", "flow-1")));
    when(runSupport.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().item(waiting));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(waiting), actual);
    verify(runSupport).restoreForExternalWorkflow(command);
    verify(flow, never()).startInstance(any());
  }

  @Test
  void idsApprovalPublishesCorrelatedEventInsteadOfStartingAnotherInstance() {
    ApproveCommand command = mock(ApproveCommand.class);
    when(command.runId()).thenReturn(RUN_ID);
    when(command.commandId()).thenReturn("cmd-approve-1");
    when(command.commandPayloadHash()).thenReturn("hash-approve-1");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_APPROVAL, "design-input", "flow-1")));
    when(runSupport.recordApprove(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(RunStatus.RUNNING, "design-planning", "flow-1", 5L)));
                      return Multi.createFrom().empty();
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal progressed = new PipelineSignal.Message("IDS approved");
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(progressed));

    List<PipelineSignal> actual =
        orchestrator.approve(command).collect().asList().await().indefinitely();

    verify(runSupport).recordApprove(command);
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(1, published.size());
    assertEquals(ProvidedIdsFlow.APPROVAL_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(progressed), actual);
  }

  @Test
  void planApprovalPublishesCorrelatedEventAndDoesNotStartAnotherInstance() {
    ApproveCommand command = mock(ApproveCommand.class);
    when(command.runId()).thenReturn(RUN_ID);
    when(command.commandId()).thenReturn("cmd-approve-plan");
    when(command.commandPayloadHash()).thenReturn("hash-approve-plan");
    when(runStore.load(RUN_ID))
        .thenReturn(
            Optional.of(document(RunStatus.WAITING_FOR_APPROVAL, "design-planning", "flow-1")));
    PipelineSignal waiting =
        new PipelineSignal.WaitingForImplement("design-planning", "plan-sha");
    when(runSupport.recordApprove(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(
                                      RunStatus.WAITING_FOR_IMPLEMENT,
                                      "design-planning",
                                      "flow-1",
                                      6L)));
                      return Multi.createFrom().item(waiting);
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of());

    List<PipelineSignal> actual =
        orchestrator.approve(command).collect().asList().await().indefinitely();

    verify(runSupport).recordApprove(command);
    verify(flow, never()).startInstance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(ProvidedIdsFlow.APPROVAL_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(waiting), actual);
  }

  @Test
  void implementPublishesCorrelatedEventInsteadOfStartingAnotherInstance() {
    ImplementCommand command = new ImplementCommand(RUN_ID, "plan-sha", 6L, "cmd-impl-1", "hash-impl-1");
    when(runStore.load(RUN_ID))
        .thenReturn(
            Optional.of(document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning", "flow-1")));
    when(runSupport.recordImplement(command))
        .thenReturn(
            Multi.createFrom()
                .deferred(
                    () -> {
                      when(runStore.load(RUN_ID))
                          .thenReturn(
                              Optional.of(
                                  document(RunStatus.RUNNING, "design-execution", "flow-1", 7L)));
                      return Multi.createFrom().empty();
                    }));
    EventPublisher publisher = mock(EventPublisher.class);
    List<CloudEvent> published = new ArrayList<>();
    when(publisher.publish(any(CloudEvent.class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    PipelineSignal completed = new PipelineSignal.Completed(RunStatus.CHAIN_MATERIALIZED);
    when(tasks.settled(RUN_ID)).thenReturn(true);
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of(completed));

    List<PipelineSignal> actual =
        orchestrator.implement(command).collect().asList().await().indefinitely();

    verify(runSupport).recordImplement(command);
    verify(flow, never()).startInstance(any());
    verify(flow, never()).instance(any());
    verify(publisher, times(1)).publish(any(CloudEvent.class));
    assertEquals(1, published.size());
    assertEquals(ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE, published.get(0).getType());
    assertEquals("flow-1", published.get(0).getExtension("flowinstanceid"));
    assertEquals(List.of(completed), actual);
  }

  @Test
  void duplicateApprovalOnBoundInstanceDoesNotPublishAnotherEvent() {
    ApproveCommand command = mock(ApproveCommand.class);
    when(command.runId()).thenReturn(RUN_ID);
    when(command.commandId()).thenReturn("cmd-approve-1");
    when(command.commandPayloadHash()).thenReturn("hash-approve-1");
    ProductPipelineRunDocument alreadyApplied =
        documentWithCommand(
            RunStatus.RUNNING, "design-planning", "flow-1", "cmd-approve-1", "hash-approve-1");
    when(runStore.load(RUN_ID)).thenReturn(Optional.of(alreadyApplied));
    when(runSupport.recordApprove(command)).thenReturn(Multi.createFrom().empty());
    EventPublisher publisher = mock(EventPublisher.class);
    when(publisher.publish(any(CloudEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(application.eventPublishers()).thenReturn(List.of(publisher));
    when(tasks.drainSignals(RUN_ID)).thenReturn(List.of());

    orchestrator.approve(command).collect().asList().await().indefinitely();

    verify(runSupport).recordApprove(command);
    verify(publisher, never()).publish(any(CloudEvent.class));
    verify(flow, never()).startInstance(any());
  }

  @Test
  void restartAtImplementationWaitRestoresBoundInstanceWithoutStartingAnother() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    PipelineSignal waiting =
        new PipelineSignal.WaitingForImplement("design-planning", "plan-sha");
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(
            Optional.of(
                document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning", "flow-1")));
    when(runSupport.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().item(waiting));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(waiting), actual);
    verify(runSupport).restoreForExternalWorkflow(command);
    verify(flow, never()).startInstance(any());
  }

  private StartOrResumeCommand startCommand() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(command.runId()).thenReturn(RUN_ID);
    when(command.profile())
        .thenReturn(
            org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser
                .parse(
                    ProvidedIdsFlowOrchestratorTest.class.getResourceAsStream(
                        "/product-pipelines/profiles/create-chain-v2.yaml")));
    when(command.runManifest())
        .thenReturn(
            new org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest(
                RUN_ID,
                null,
                List.of(),
                "create-chain@2",
                "create-chain",
                "2",
                "manifest-sha",
                "baseline",
                "baseline-sha",
                List.of(),
                "closure-sha",
                null,
                "24.4",
                List.of(),
                null));
    return command;
  }

  private static ProductPipelineRunDocument document(RunStatus status, String stageId) {
    return document(status, stageId, null);
  }

  private static ProductPipelineRunDocument document(
      RunStatus status, String stageId, String flowInstanceId) {
    return document(status, stageId, flowInstanceId, 3L);
  }

  private static ProductPipelineRunDocument document(
      RunStatus status, String stageId, String flowInstanceId, long revision) {
    return new ProductPipelineRunDocument(
        new RunSnapshot(
            RUN_ID, CONVERSATION_ID, revision, status, stageId, List.of(), null, flowInstanceId),
        List.of(),
        List.of(),
        "blob-version");
  }

  private static ProductPipelineRunDocument documentWithCommand(
      RunStatus status,
      String stageId,
      String flowInstanceId,
      String commandId,
      String payloadHash) {
    return new ProductPipelineRunDocument(
        new RunSnapshot(
            RUN_ID, CONVERSATION_ID, 5L, status, stageId, List.of(), null, flowInstanceId),
        List.of(),
        List.of(
            new RunTransition(
                4L,
                5L,
                RunStatus.WAITING_FOR_APPROVAL,
                status,
                stageId,
                Instant.parse("2026-08-14T00:00:00Z"),
                "approved",
                commandId,
                payloadHash)),
        "blob-version");
  }
}
