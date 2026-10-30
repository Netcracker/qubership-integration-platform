package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aCommandId;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

class A2aLifecycleExecutorTest {

  @Test
  void initialApproveFailsBeforeAnyRepositoryWrite() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade,
            persister,
            receipts,
            () -> new CallerContext("local", "local-user"),
            new LocalPermitAllTaskAccessPolicy());

    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-approve")
            .parts(
                List.of(
                    new org.a2aproject.sdk.spec.DataPart(
                        Map.of(
                            "action",
                            "approve",
                            "artifactType",
                            "implementation-plan",
                            "artifactHash",
                            "abc",
                            "revision",
                            1))))
            .build();
    RequestContext context =
        new RequestContext.Builder()
            .setTaskId("task-1")
            .setContextId("ctx-1")
            .setParams(
                org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();

    assertThrows(
        org.a2aproject.sdk.spec.InvalidParamsError.class,
        () -> executor.execute(context, mock(AgentEmitter.class)));
    verify(receipts, times(0)).claimInitialWithWorkingTask(any(), any(), any(), any(), any());
    verify(receipts, times(0))
        .claimInitialWithWorkingTask(any(), any(), any(), any(), any(), any());
    verify(facade, times(0)).start(any());
  }

  @Test
  void cancelAlwaysThrowsTaskNotCancelable() {
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            mock(CreateChainApplicationFacade.class),
            mock(A2aTaskSnapshotPersister.class),
            mock(A2aMessageReceiptRepository.class),
            () -> new CallerContext("local", "local-user"),
            new LocalPermitAllTaskAccessPolicy());

    RequestContext context =
        new RequestContext.Builder().setTaskId("t1").setContextId("c1").build();
    assertThrows(
        TaskNotCancelableError.class,
        () -> executor.cancel(context, mock(AgentEmitter.class)));
  }

  @Test
  void executePersistsBeforeEmittingInputRequired() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CallerContextProvider callers = () -> new CallerContext("local", "local-user");
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade, persister, receipts, callers, new LocalPermitAllTaskAccessPolicy());

    when(receipts.claimInitialWithWorkingTask(
            eq("local"), eq("local-user"), eq("msg-1"), any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult
                .Claimed("task-1"));
    when(receipts.acquireDispatch(eq("local"), eq("local-user"), eq("msg-1")))
        .thenReturn(
            org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CreateChainEvent.Waiting(
                        new CreateChainPendingAction.Clarify("need input", List.of()))));
    when(facade.snapshot("task-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "task-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    1L,
                    new CreateChainPendingAction.Clarify("need input", List.of()),
                    "")));
    when(persister.loadSdkTask("task-1"))
        .thenReturn(
            Optional.of(
                org.a2aproject.sdk.spec.Task.builder()
                    .id("task-1")
                    .contextId("ctx-1")
                    .status(
                        new org.a2aproject.sdk.spec.TaskStatus(
                            org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING, null, null))
                    .build()));
    when(persister.persistAndBuildSdkTask(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ProjectedTask projected = invocation.getArgument(3);
              org.a2aproject.sdk.spec.Task task =
                  org.a2aproject.sdk.spec.Task.builder()
                      .id("task-1")
                      .contextId("ctx-1")
                      .status(
                          new org.a2aproject.sdk.spec.TaskStatus(
                              projected.state().toSdk(), null, null))
                      .build();
              return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of(), 9L);
            });

    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("hello")))
            .build();
    RequestContext context =
        new RequestContext.Builder()
            .setTaskId("task-1")
            .setContextId("ctx-1")
            .setParams(
                org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();
    AgentEmitter emitter = mock(AgentEmitter.class);

    executor.execute(context, emitter);
    verify(receipts).completeDispatch("local", "local-user", "msg-1",
        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), 9L, 9L);

    verify(persister, times(1)).persistAndBuildSdkTask(any(), any(), any(), any(), any());
    verify(emitter, org.mockito.Mockito.atLeastOnce()).addTask(any());
    verify(emitter).startWork(any());
    verify(emitter).requiresInput(any(), eq(true));
    assertEquals(1, executor.facadeInvocationCount());
  }

  @Test
  void executeDoesNotReEmitWorkingAfterInputRequired() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CallerContextProvider callers = () -> new CallerContext("local", "local-user");
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade, persister, receipts, callers, new LocalPermitAllTaskAccessPolicy());

    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify("need input", List.of("q1"));
    when(receipts.claimInitialWithWorkingTask(
            eq("local"), eq("local-user"), eq("msg-1"), any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult
                .Claimed("task-1"));
    when(receipts.acquireDispatch(eq("local"), eq("local-user"), eq("msg-1")))
        .thenReturn(
            org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CreateChainEvent.Waiting(clarify),
                    new CreateChainEvent.Progress("Working")));
    when(facade.snapshot("task-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "task-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    1L,
                    clarify,
                    "")))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "task-1",
                    "run-1",
                    CreateChainExecutionStatus.WORKING,
                    1L,
                    null,
                    "")));
    when(persister.loadSdkTask("task-1"))
        .thenReturn(
            Optional.of(
                org.a2aproject.sdk.spec.Task.builder()
                    .id("task-1")
                    .contextId("ctx-1")
                    .status(
                        new org.a2aproject.sdk.spec.TaskStatus(
                            org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING, null, null))
                    .build()));
    when(persister.persistAndBuildSdkTask(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ProjectedTask projected = invocation.getArgument(3);
              org.a2aproject.sdk.spec.Task task =
                  org.a2aproject.sdk.spec.Task.builder()
                      .id("task-1")
                      .contextId("ctx-1")
                      .status(
                          new org.a2aproject.sdk.spec.TaskStatus(
                              projected.state().toSdk(), null, null))
                      .build();
              return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of());
            });

    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("hello")))
            .build();
    RequestContext context =
        new RequestContext.Builder()
            .setTaskId("task-1")
            .setContextId("ctx-1")
            .setParams(
                org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();
    AgentEmitter emitter = mock(AgentEmitter.class);

    executor.execute(context, emitter);

    verify(emitter, times(1)).requiresInput(any(), eq(true));
    verify(emitter, times(1)).startWork(any());
  }

  @Test
  void terminalContinuationIsRejectedByProtocolMapper() {
    UnsupportedOperationError error =
        assertInstanceOf(
            UnsupportedOperationError.class,
            A2aProtocolErrorMapper.terminalContinuation("t1", "TASK_STATE_COMPLETED"));
    assertEquals(-32004, error.getCode());
  }

  @Test
  void specializedCardWithholdsBlankOfferBeforeContinue() throws Exception {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.withholdsRequirementOffer("task-1")).thenReturn(true);
    when(decisions.openDecision("task-1")).thenReturn(Optional.of(new ChatEvent.Decision(
        "policy-1", "clarify", "Which access control should this chain use?",
        PolicyChoiceSelection.ARTIFACT_TYPE, "hash", 3L, "policy", List.of(),
        List.of("keep-requested"), null, List.of(), List.of(), Map.of())));
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    CreateChainA2aAgentExecutor executor =
        continuationExecutor(facade, receipts, archive, persister, decisions);
    when(facade.activeClarificationOffer("task-1")).thenReturn(Optional.of(offer("offer-requirement")));
    when(persister.loadSdkTask("task-1")).thenReturn(Optional.of(waitingTask()));
    Message message = textMessage("msg-policy", "Use HTTPS");

    AgentEmitter emitter = mock(AgentEmitter.class);
    executor.execute(continuation(message), emitter);

    verify(receipts, never()).claimContinuation(any(), any(), any(), any(), any(), any(), any(), any());
    verify(facade, never()).continueWithInput(any());
    assertTrue(archive.read("task-1").turns().isEmpty());
    verify(emitter).requiresInput(any(), eq(true));
  }

  @Test
  void folderChoiceTextReachesTheFacadeWithoutARequirementOffer() throws Exception {
    assertSpecializedGateContinues(PipelineGates.TARGET_FOLDER_CHOICE, "Integrations/Salesforce");
  }

  @Test
  void recoveryClarificationTextReachesTheFacadeWithoutARequirementOffer() throws Exception {
    assertSpecializedGateContinues(
        PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION,
        "The order id is the Salesforce Order.Id field.");
  }

  @Test
  void idsPathChoiceTextReachesTheFacadeWithoutARequirementOffer() throws Exception {
    assertSpecializedGateContinues(PipelineGates.IDS_PATH_CHOICE, "yes");
  }

  @Test
  void catalogVersionTextReachesTheFacadeWithoutARequirementOffer() throws Exception {
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.withholdsRequirementOffer("task-1")).thenReturn(true);
    when(decisions.openDecision("task-1")).thenReturn(Optional.of(new ChatEvent.Decision(
        "version-1", "clarify", "Which catalog version should this call use?",
        CatalogVersionSelection.ARTIFACT_TYPE, "hash", 3L, "version", List.of(),
        List.of(), null, List.of(), List.of(), Map.of())));
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    assertBlankOfferContinues(decisions, "catalog-version", "2024.1", facade);
  }

  private void assertSpecializedGateContinues(String gate, String text) throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    ChatDecisionService decisions = decisionsWaitingOn(facade, gate);
    assertBlankOfferContinues(decisions, gate, text, facade);
  }

  private void assertBlankOfferContinues(
      ChatDecisionService decisions, String label, String text, CreateChainApplicationFacade facade)
      throws Exception {
    ConversationArchive archive = newArchive();
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor =
        continuationExecutor(facade, receipts, archive, null, decisions);
    stubWaitingSnapshot(facade, label);
    when(facade.activeClarificationOffer(any())).thenReturn(Optional.of(offer("offer-requirement")));
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"));
    Message message = textMessage("msg-" + label, text);

    executor.execute(continuation(message), mock(AgentEmitter.class));

    ArgumentCaptor<ContinueCreateChainCommand> continued =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(continued.capture());
    assertEquals("", continued.getValue().clarificationOfferId(), label);
    assertEquals(text, continued.getValue().clarificationText(), label);
    assertTrue(archive.read("task-1").turns().isEmpty(), label);
  }

  private static ChatDecisionService decisionsWaitingOn(
      CreateChainApplicationFacade facade, String gate) {
    stubWaitingSnapshot(facade, gate);
    when(facade.activeClarificationOffer(any())).thenReturn(Optional.of(offer("offer-requirement")));
    return new ChatDecisionService(
        facade, new ApprovalQuestionStore(new InMemoryArtifactBlobStore()), new RequirementDraftStore());
  }

  private static void stubWaitingSnapshot(CreateChainApplicationFacade facade, String gate) {
    when(facade.snapshot(any())).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 3L,
        new CreateChainPendingAction.Clarify("Choose one.", List.of(), gate), "")));
  }

  @Test
  void missingOfferStillPinsWhenSpecializedCardDoesNotWithhold() throws Exception {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.withholdsRequirementOffer("task-1")).thenReturn(false);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, archive, null, decisions);
    when(facade.activeClarificationOffer("task-1")).thenReturn(Optional.of(offer("offer-current")));
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"));
    Message message = textMessage("msg-stable", "Use HTTPS");

    executor.execute(continuation(message), mock(AgentEmitter.class));

    ArgumentCaptor<ContinueCreateChainCommand> continued =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(continued.capture());
    assertEquals("offer-current", continued.getValue().clarificationOfferId());
  }

  @Test
  void missingOfferAndStableMessageIdPinsTheCurrentOffer() throws Exception {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, archive);
    when(facade.activeClarificationOffer("task-1")).thenReturn(Optional.of(offer("offer-current")));
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"));
    Message message = textMessage("msg-stable", "Use HTTPS");

    executor.execute(continuation(message), mock(AgentEmitter.class));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
    verify(receipts).claimContinuation(
        eq("local"), eq("local-user"), eq("msg-stable"), fingerprint.capture(), any(), eq("task-1"),
        any(), payload.capture());
    assertTrue(payload.getValue() != null && payload.getValue().contains("offer-current"), payload.getValue());
    assertEquals(
        A2aCommandFingerprint.compute(message, new InboundCommand.ClarifyText("Use HTTPS", "offer-current")),
        fingerprint.getValue());
    ArgumentCaptor<ContinueCreateChainCommand> continued =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(continued.capture());
    assertEquals("offer-current", continued.getValue().clarificationOfferId());
  }

  @Test
  void explicitOfferIsClaimedAheadOfTheCurrentDurableOffer() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, newArchive());
    when(facade.activeClarificationOffer("task-1")).thenReturn(Optional.of(offer("offer-durable")));
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"));
    Message message = clarifyMessage("msg-explicit", "Use HTTPS", "offer-explicit");

    executor.execute(continuation(message), mock(AgentEmitter.class));

    ArgumentCaptor<ContinueCreateChainCommand> continued =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(continued.capture());
    assertEquals("offer-explicit", continued.getValue().clarificationOfferId());
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(receipts).claimContinuation(
        any(), any(), eq("msg-explicit"), any(), any(), any(), any(), payload.capture());
    assertTrue(payload.getValue() != null && payload.getValue().contains("offer-explicit"), payload.getValue());
    assertTrue(!payload.getValue().contains("offer-durable"));
  }

  @Test
  void redispatchReadsThePinnedOfferAfterTheRunAdvances() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, newArchive());
    when(facade.activeClarificationOffer("task-1")).thenReturn(Optional.of(offer("offer-advanced")));
    when(receipts.findCallerReceipt("local", "local-user", "msg-retry")).thenReturn(Optional.of(
        new A2aCallerMessageReceipt(
            "task-1", "fingerprint", A2aReceiptProcessingState.DISPATCHING, 3L, null,
            A2aInboundMessageParser.KIND_CONTINUE_CLARIFY, 3L,
            "{\"text\":\"Use HTTPS\",\"clarificationOfferId\":\"offer-pinned\"}")));
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Incomplete(
            "task-1", A2aReceiptProcessingState.DISPATCHING));
    Message message = textMessage("msg-retry", "Use HTTPS");

    executor.execute(continuation(message), mock(AgentEmitter.class));

    ArgumentCaptor<ContinueCreateChainCommand> continued =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(continued.capture());
    assertEquals("offer-pinned", continued.getValue().clarificationOfferId());
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(receipts).claimContinuation(
        any(), any(), eq("msg-retry"), any(), any(), any(), any(), payload.capture());
    assertTrue(payload.getValue() != null && payload.getValue().contains("offer-pinned"), payload.getValue());
    assertTrue(!payload.getValue().contains("offer-advanced"));
  }

  @Test
  void missingOfferAndMissingMessageIdIsRejectedBeforeArchival() {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, archive);

    assertThrows(InvalidParamsError.class, () -> executor.execute(
        continuation(textMessage("", "Use HTTPS")), mock(AgentEmitter.class)));

    verify(receipts, never()).claimContinuation(any(), any(), any(), any(), any(), any(), any(), any());
    assertTrue(archive.read("task-1").turns().isEmpty());
    verify(facade, never()).continueWithInput(any());
  }

  @Test
  void initialClarifyTextArchivesOneOpeningAuthorTurn() throws Exception {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = initialClarifyExecutor(facade, receipts, archive);
    String messageId = "msg-opening";
    String text = "Build an HTTP integration chain";
    String commandId = A2aCommandId.derive("local", "local-user", messageId);
    Message message = textMessage(messageId, text);
    RequestContext context = newTaskContext(message);
    when(receipts.claimInitialWithWorkingTask(
            eq("local"), eq("local-user"), eq(messageId), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"))
        .thenReturn(new A2aCallerMessageClaimResult.Incomplete(
            "task-1", A2aReceiptProcessingState.DISPATCHING));
    CreateChainExecutionSnapshot afterStart = new CreateChainExecutionSnapshot(
        "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 1L,
        new CreateChainPendingAction.Clarify("need input", List.of()), "");
    when(facade.snapshot("task-1"))
        .thenReturn(Optional.of(afterStart))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(afterStart));
    AgentEmitter emitter = mock(AgentEmitter.class);

    executor.execute(context, emitter);
    executor.execute(context, emitter);

    var turns = archive.read("task-1").turns();
    assertEquals(1, turns.size());
    assertEquals(text, turns.getFirst().rawText());
    assertEquals(commandId, turns.getFirst().id());
    assertEquals("", clarificationOfferId(turns.getFirst().metadata()));
  }

  @Test
  void oneRetryArchivesOneAnswerTurn() throws Exception {
    ConversationArchive archive = newArchive();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor = continuationExecutor(facade, receipts, archive);
    when(receipts.claimContinuation(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"))
        .thenReturn(new A2aCallerMessageClaimResult.Incomplete(
            "task-1", A2aReceiptProcessingState.CLAIMED));
    Message message = clarifyMessage("msg-once", "Use HTTPS", "offer-current");
    RequestContext context = continuation(message);

    executor.execute(context, mock(AgentEmitter.class));
    executor.execute(context, mock(AgentEmitter.class));

    var turns = archive.read("task-1").turns();
    assertEquals(1, turns.size());
    assertEquals("Use HTTPS", turns.getFirst().rawText());
    assertEquals(
        ConversationArchive.deterministicAnswerTurnId("offer-current", "Use HTTPS"),
        turns.getFirst().id());
  }

  private static ConversationArchive newArchive() {
    return new ConversationArchive(
        new InMemoryArtifactBlobStore(), new ObjectMapper().findAndRegisterModules());
  }

  private static ClarificationTransition.Offer offer(String offerId) {
    return new ClarificationTransition.Offer(
        offerId,
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "draft-1", "hash-1"),
        "question-1",
        3L,
        "generation-1");
  }

  private static Message textMessage(String messageId, String text) {
    return Message.builder()
        .role(Message.Role.ROLE_USER)
        .messageId(messageId)
        .parts(List.of(new TextPart(text)))
        .build();
  }

  private static Message clarifyMessage(String messageId, String text, String offerId) {
    return Message.builder()
        .role(Message.Role.ROLE_USER)
        .messageId(messageId)
        .parts(List.of(
            new TextPart(text),
            new DataPart(Map.of("action", "clarify", "clarificationOfferId", offerId))))
        .build();
  }

  private static String clarificationOfferId(com.fasterxml.jackson.databind.JsonNode metadata) {
    if (metadata == null || !metadata.isObject()) {
      return "";
    }
    com.fasterxml.jackson.databind.JsonNode value = metadata.get("clarificationOfferId");
    if (value == null || value.isNull()) {
      return "";
    }
    return value.asText("");
  }

  private static RequestContext newTaskContext(Message message) {
    return new RequestContext.Builder()
        .setTaskId("task-1")
        .setContextId("ctx-1")
        .setParams(org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
        .build();
  }

  private static RequestContext continuation(Message message) {
    Task task = Task.builder()
        .id("task-1")
        .contextId("ctx-1")
        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, null))
        .build();
    return new RequestContext.Builder()
        .setTaskId("task-1")
        .setContextId("ctx-1")
        .setTask(task)
        .setParams(org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
        .build();
  }

  private static CreateChainA2aAgentExecutor initialClarifyExecutor(
      CreateChainApplicationFacade facade,
      A2aMessageReceiptRepository receipts,
      ConversationArchive archive) {
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    when(receipts.findCallerReceipt(any(), any(), any())).thenReturn(Optional.empty());
    when(receipts.acquireDispatch(any(), any(), any())).thenReturn(
        org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().item(
            new CreateChainEvent.Waiting(
                new CreateChainPendingAction.Clarify("need input", List.of()))));
    when(facade.snapshot("task-1"))
        .thenReturn(Optional.of(new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 1L,
            new CreateChainPendingAction.Clarify("need input", List.of()), "")));
    when(persister.loadSdkTask("task-1"))
        .thenReturn(Optional.of(Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING, null, null))
            .build()));
    when(persister.persistAndBuildSdkTask(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          ProjectedTask projected = invocation.getArgument(3);
          Task task = Task.builder()
              .id("task-1")
              .contextId("ctx-1")
              .status(new TaskStatus(projected.state().toSdk(), null, null))
              .build();
          return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of(), 9L);
        });
    return new CreateChainA2aAgentExecutor(
        facade,
        persister,
        receipts,
        () -> new CallerContext("local", "local-user"),
        new LocalPermitAllTaskAccessPolicy(),
        null,
        null,
        null,
        archive,
        null);
  }

  private static Task waitingTask() {
    return Task.builder()
        .id("task-1")
        .contextId("ctx-1")
        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, null))
        .build();
  }

  private static CreateChainA2aAgentExecutor continuationExecutor(
      CreateChainApplicationFacade facade,
      A2aMessageReceiptRepository receipts,
      ConversationArchive archive) {
    return continuationExecutor(facade, receipts, archive, null, null);
  }

  private static CreateChainA2aAgentExecutor continuationExecutor(
      CreateChainApplicationFacade facade,
      A2aMessageReceiptRepository receipts,
      ConversationArchive archive,
      A2aTaskSnapshotPersister persister,
      ChatDecisionService decisions) {
    A2aTaskSnapshotPersister snapshotPersister =
        persister == null ? mock(A2aTaskSnapshotPersister.class) : persister;
    when(receipts.findCallerReceipt(any(), any(), any())).thenReturn(Optional.empty());
    when(receipts.acquireDispatch(any(), any(), any())).thenReturn(
        org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);
    when(facade.snapshot(any())).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 3L,
        new CreateChainPendingAction.Clarify("Which protocol?", List.of()), "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().item(
        new CreateChainEvent.Waiting(new CreateChainPendingAction.Clarify("next", List.of()))));
    when(snapshotPersister.persistAndBuildSdkTask(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
      ProjectedTask projected = invocation.getArgument(3);
      Task task = Task.builder()
          .id("task-1")
          .contextId("ctx-1")
          .status(new TaskStatus(projected.state().toSdk(), null, null))
          .build();
      return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of(), 9L);
    });
    return new CreateChainA2aAgentExecutor(
        facade,
        snapshotPersister,
        receipts,
        () -> new CallerContext("local", "local-user"),
        new LocalPermitAllTaskAccessPolicy(),
        null,
        null,
        null,
        archive,
        decisions);
  }
}
