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
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
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
 * Mandatory launch scenario: INPUT_REQUIRED survives process-local restart (cleared SDK TaskStore),
 * then GetTask, resubscribe, continue, and complete.
 */
@QuarkusTest
class A2aRestartE2EIT {

  private static final String DESIGN_HASH = "d".repeat(64);
  private static final String MATERIALIZATION_HASH = "e".repeat(64);

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aMessageReceiptRepository receiptRepository;

  @Inject TaskStore taskStore;

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
                      "Approve design");
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
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                          "design-restart-1",
                          DESIGN_HASH,
                          1L),
                      new CreateChainEvent.Waiting(pending));
            });
    when(facade.approve(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              approveCalls.incrementAndGet();
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              CreateChainExecutionSnapshot completed =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.COMPLETED,
                      2L,
                      null,
                      "");
              snapshot.set(completed);
              return new ApproveCreateChainOutcome.Accepted(
                  List.of(
                      new CreateChainEvent.Progress("Auto-implementing approved plan"),
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
                          "mat-restart-1",
                          MATERIALIZATION_HASH,
                          2L),
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
  void restartClearsInMemoryStoreThenResubscribeAndComplete() throws Exception {
    String createMessageId = UUID.randomUUID().toString();
    String taskId =
        A2aE2eSupport.sendMessage(
            A2aE2eSupport.textMessageBody(createMessageId, null, "Restart scenario IDS"));

    assertEquals("TASK_STATE_INPUT_REQUIRED", A2aE2eSupport.getTaskState(taskId));
    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.INPUT_REQUIRED, before.state());
    assertTrue(receiptRepository.exists(taskId, createMessageId));
    assertTrue(
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", createMessageId)
            .isPresent());

    // Simulate process restart: durable JDBC remains; SDK in-memory TaskStore is empty.
    taskStore.delete(taskId);

    assertEquals("TASK_STATE_INPUT_REQUIRED", A2aE2eSupport.getTaskState(taskId));
    List<JsonNode> subscribed =
        A2aE2eSupport.subscribe(taskId, Duration.ofSeconds(10));
    assertFalse(subscribed.isEmpty());
    assertTrue(A2aSseTestSupport.isTaskEvent(subscribed.get(0)));
    assertEquals(
        "TASK_STATE_INPUT_REQUIRED", A2aSseTestSupport.eventState(subscribed.get(0)));

    A2aPersistedTask afterRestart = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(before.publicSnapshotJson(), afterRestart.publicSnapshotJson());
    assertTrue(afterRestart.artifactMetadataJson().contains("integration-design"));

    A2aE2eSupport.approvePending(taskId);
    assertEquals("TASK_STATE_COMPLETED", A2aE2eSupport.getTaskState(taskId));
    A2aE2eSupport.assertMaterializationResult(taskId);

    assertEquals(1, startCalls.get());
    assertEquals(1, approveCalls.get());
    assertTrue(receiptRepository.exists(taskId, createMessageId));
  }
}
