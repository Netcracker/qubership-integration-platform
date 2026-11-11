package org.qubership.integration.platform.ai.a2a.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aAgentExecutor;
import org.qubership.integration.platform.ai.a2a.transport.A2aTaskSnapshotPersister;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

/**
 * Table-driven coverage that every A2A operation invokes {@link TaskAccessPolicy} with the
 * resolved local caller. Includes denial coverage even though the runtime policy permits access.
 */
class TaskAccessPolicyTest {

  static Stream<Arguments> everyOperation() {
    return Stream.of(TaskOperation.values()).map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("everyOperation")
  void permitAllAcceptsEveryOperation(TaskOperation operation) {
    TaskAccessPolicy policy = new LocalPermitAllTaskAccessPolicy();
    CallerContext caller = new CallerContext("local", "local-user");
    assertDoesNotThrow(() -> policy.check(caller, operation, new TaskIdentity("task-1", "ctx-1")));
  }

  @ParameterizedTest
  @MethodSource("everyOperation")
  void denyingPolicyRejectsEveryOperation(TaskOperation operation) {
    TaskAccessPolicy policy = new DenyingTaskAccessPolicy();
    CallerContext caller = new CallerContext("local", "local-user");
    TaskAccessDeniedException denied =
        assertThrows(
            TaskAccessDeniedException.class,
            () -> policy.check(caller, operation, new TaskIdentity("task-1", "ctx-1")));
    assertEquals("denied:" + operation.name(), denied.getMessage());
  }

  @Test
  void executorInvokesPolicyOnCreateWithResolvedCaller() throws Exception {
    RecordingTaskAccessPolicy policy = new RecordingTaskAccessPolicy();
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade,
            persister,
            receipts,
            () -> new CallerContext("local", "local-user"),
            policy);

    org.a2aproject.sdk.spec.Message message =
        org.a2aproject.sdk.spec.Message.builder()
            .role(org.a2aproject.sdk.spec.Message.Role.ROLE_USER)
            .messageId("msg-policy")
            .parts(List.of(new org.a2aproject.sdk.spec.TextPart("build")))
            .metadata(java.util.Map.of("tenantId", "evil-tenant", "subjectId", "evil-user"))
            .build();
    org.a2aproject.sdk.server.agentexecution.RequestContext context =
        new org.a2aproject.sdk.server.agentexecution.RequestContext.Builder()
            .setTaskId("task-policy")
            .setContextId("ctx-policy")
            .setParams(
                org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();

    // Fail closed on persist so we stop after the policy check + receipt path setup.
    org.mockito.Mockito.when(
            receipts.claimInitialWithWorkingTask(
                org.mockito.ArgumentMatchers.eq("local"),
                org.mockito.ArgumentMatchers.eq("local-user"),
                org.mockito.ArgumentMatchers.eq("msg-policy"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult
                .Claimed("task-policy"));
    org.mockito.Mockito.when(
            receipts.acquireDispatch(
                org.mockito.ArgumentMatchers.eq("local"),
                org.mockito.ArgumentMatchers.eq("local-user"),
                org.mockito.ArgumentMatchers.eq("msg-policy")))
        .thenReturn(
            org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
                java.util.UUID.randomUUID()));
    org.mockito.Mockito.when(
            receipts.renewDispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    org.mockito.Mockito.when(facade.start(org.mockito.ArgumentMatchers.any()))
        .thenReturn(io.smallrye.mutiny.Multi.createFrom().empty());
    org.mockito.Mockito.when(facade.snapshot("task-policy"))
        .thenReturn(
            java.util.Optional.of(
                new org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot(
                    "task-policy",
                    "run-1",
                    org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus.WORKING,
                    1L,
                    null,
                    "")));
    org.mockito.Mockito.when(persister.loadSdkTask("task-policy"))
        .thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(
            persister.persistAndBuildSdkTask(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              org.a2aproject.sdk.spec.Task task =
                  org.a2aproject.sdk.spec.Task.builder()
                      .id("task-policy")
                      .contextId("ctx-policy")
                      .status(
                          new org.a2aproject.sdk.spec.TaskStatus(
                              org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING, null, null))
                      .build();
              return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of());
            });

    executor.execute(context, mock(org.a2aproject.sdk.server.tasks.AgentEmitter.class));

    assertEquals(1, policy.calls.size());
    assertEquals(TaskOperation.CREATE, policy.calls.get(0).operation());
    assertEquals("local", policy.calls.get(0).caller().tenantId());
    assertEquals("local-user", policy.calls.get(0).caller().subjectId());
    assertEquals("task-policy", policy.calls.get(0).task().taskId());
    verify(receipts)
        .claimInitialWithWorkingTask(
            org.mockito.ArgumentMatchers.eq("local"),
            org.mockito.ArgumentMatchers.eq("local-user"),
            org.mockito.ArgumentMatchers.eq("msg-policy"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(receipts)
        .acquireDispatch(
            org.mockito.ArgumentMatchers.eq("local"),
            org.mockito.ArgumentMatchers.eq("local-user"),
            org.mockito.ArgumentMatchers.eq("msg-policy"));
  }

  @Test
  void deniedCreateDoesNotCreateTask() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade,
            persister,
            receipts,
            () -> new CallerContext("local", "local-user"),
            new DenyingTaskAccessPolicy());

    org.a2aproject.sdk.spec.Message message =
        org.a2aproject.sdk.spec.Message.builder()
            .role(org.a2aproject.sdk.spec.Message.Role.ROLE_USER)
            .messageId("msg-denied")
            .parts(List.of(new org.a2aproject.sdk.spec.TextPart("build")))
            .build();
    org.a2aproject.sdk.server.agentexecution.RequestContext context =
        new org.a2aproject.sdk.server.agentexecution.RequestContext.Builder()
            .setTaskId("task-denied")
            .setContextId("ctx-denied")
            .setParams(
                org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();

    assertThrows(
        org.a2aproject.sdk.spec.A2AError.class,
        () -> executor.execute(context, mock(org.a2aproject.sdk.server.tasks.AgentEmitter.class)));
    verifyNoMoreInteractions(facade, receipts, persister);
  }

  private static final class DenyingTaskAccessPolicy implements TaskAccessPolicy {
    @Override
    public void check(CallerContext caller, TaskOperation operation, TaskIdentity task) {
      throw new TaskAccessDeniedException("denied:" + operation.name());
    }
  }

  private static final class RecordingTaskAccessPolicy implements TaskAccessPolicy {
    private final List<Call> calls = new ArrayList<>();

    @Override
    public void check(CallerContext caller, TaskOperation operation, TaskIdentity task) {
      calls.add(new Call(caller, operation, task));
    }

    private record Call(CallerContext caller, TaskOperation operation, TaskIdentity task) {}
  }
}
