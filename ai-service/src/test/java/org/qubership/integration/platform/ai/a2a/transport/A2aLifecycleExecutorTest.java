package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
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
}
