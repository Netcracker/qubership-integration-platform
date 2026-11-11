package org.qubership.integration.platform.ai.a2a.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aSseTestSupport;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Mandatory launch scenario: provided IDS → approvals → COMPLETED with materialization-result.
 */
@QuarkusTest
class A2aProvidedIdsE2EIT {

  private static final String DESIGN_HASH = "a".repeat(64);
  private static final String PLAN_HASH = "b".repeat(64);
  private static final String MATERIALIZATION_HASH = "c".repeat(64);

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  private final AtomicInteger startCalls = new AtomicInteger();
  private final AtomicInteger approveCalls = new AtomicInteger();
  private final AtomicReference<CreateChainExecutionSnapshot> snapshot = new AtomicReference<>();

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    approveCalls.set(0);
    snapshot.set(null);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              CreateChainPendingAction.Approve pending =
                  new CreateChainPendingAction.Approve(
                      CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                      DESIGN_HASH,
                      1L,
                      "Approve provided IDS");
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      pending,
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Analyzing provided IDS"),
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                          "design-1",
                          DESIGN_HASH,
                          1L),
                      new CreateChainEvent.Waiting(pending));
            });
    when(facade.approve(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              approveCalls.incrementAndGet();
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              if (CreateChainPublicArtifactTypes.INTEGRATION_DESIGN.equals(command.artifactType())) {
                CreateChainPendingAction.Approve planPending =
                    new CreateChainPendingAction.Approve(
                        CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                        PLAN_HASH,
                        2L,
                        "Approve implementation plan");
                CreateChainExecutionSnapshot waiting =
                    new CreateChainExecutionSnapshot(
                        command.taskId(),
                        "run-" + command.taskId(),
                        CreateChainExecutionStatus.INPUT_REQUIRED,
                        2L,
                        planPending,
                        "");
                snapshot.set(waiting);
                return new ApproveCreateChainOutcome.Accepted(
                    List.of(
                        new CreateChainEvent.ArtifactReady(
                            CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                            "plan-1",
                            PLAN_HASH,
                            2L),
                        new CreateChainEvent.Waiting(planPending)),
                    waiting);
              }
              CreateChainExecutionSnapshot completed =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.COMPLETED,
                      3L,
                      null,
                      "");
              snapshot.set(completed);
              return new ApproveCreateChainOutcome.Accepted(
                  List.of(
                      new CreateChainEvent.Progress("Auto-implementing approved plan"),
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
                          "mat-1",
                          MATERIALIZATION_HASH,
                          3L),
                      new CreateChainEvent.Completed(completed)),
                  completed);
            });

    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(java.util.Optional.empty());
    when(facade.streamApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              ApproveCreateChainOutcome outcome = facade.approve(invocation.getArgument(0));
              if (!(outcome instanceof ApproveCreateChainOutcome.Accepted accepted)) {
                throw new IllegalStateException("unexpected approve outcome in stream stub: " + outcome);
              }
              return io.smallrye.mutiny.Multi.createFrom().iterable(accepted.events());
            });

    when(facade.snapshot(any())).thenAnswer(invocation -> Optional.ofNullable(snapshot.get()));
  }

  @Test
  void providedIdsStreamsApprovalsAndMaterializationResult() throws Exception {
    A2aE2eSupport.agentCardOffersCreateChainOverRest();

    String messageId = UUID.randomUUID().toString();
    String idsBody =
        A2aE2eSupport.textMessageBody(
            messageId,
            null,
            "# Integration Design Specification\\nProvided IDS for pets GET /pets");

    List<JsonNode> createEvents =
        A2aE2eSupport.streamCreate(idsBody, Duration.ofSeconds(20));
    assertFalse(createEvents.isEmpty());
    assertTrue(A2aSseTestSupport.isTaskEvent(createEvents.get(0)));
    List<String> createStates = A2aE2eSupport.orderedStates(createEvents);
    assertTrue(createStates.contains("TASK_STATE_WORKING"), createStates.toString());
    assertTrue(createStates.contains("TASK_STATE_INPUT_REQUIRED"), createStates.toString());
    assertTrue(
        createStates.indexOf("TASK_STATE_WORKING")
            < createStates.indexOf("TASK_STATE_INPUT_REQUIRED"),
        createStates.toString());

    String taskId = A2aE2eSupport.extractTaskId(createEvents.get(0));
    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(taskId, persisted.conversationId());
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());
    A2aE2eSupport.assertNoSensitiveLeak(persisted.publicSnapshotJson());

    A2aE2eSupport.approvePending(taskId);
    assertEquals("TASK_STATE_INPUT_REQUIRED", A2aE2eSupport.getTaskState(taskId));

    String planApproveBody =
        A2aE2eSupport.approveBody(
            taskId, CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN, PLAN_HASH, 2L);
    List<JsonNode> planEvents =
        A2aE2eSupport.streamCreate(planApproveBody, Duration.ofSeconds(20));
    List<String> planStates = A2aE2eSupport.orderedStates(planEvents);
    assertTrue(planStates.contains("TASK_STATE_COMPLETED"), planStates.toString());
    assertFalse(
        planStates.contains("TASK_STATE_INPUT_REQUIRED"),
        "auto-implement must not emit INPUT_REQUIRED: " + planStates);

    A2aE2eSupport.assertMaterializationResult(taskId);
    assertFinalSnapshotMatchesGetTask(taskId, planEvents.get(planEvents.size() - 1));
    A2aE2eSupport.cancelRejected(taskId);

    assertEquals(1, startCalls.get());
    assertEquals(2, approveCalls.get());
    assertEquals(1, taskRepository.findByTaskId(taskId).stream().count());
  }

  private static void assertFinalSnapshotMatchesGetTask(String taskId, JsonNode finalEvent) {
    String getState = A2aE2eSupport.getTaskState(taskId);
    String streamState = A2aSseTestSupport.eventState(finalEvent);
    assertEquals("TASK_STATE_COMPLETED", getState);
    assertEquals(getState, streamState);
  }
}
