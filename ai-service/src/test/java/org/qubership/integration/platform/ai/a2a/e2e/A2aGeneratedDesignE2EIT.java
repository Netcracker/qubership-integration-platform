package org.qubership.integration.platform.ai.a2a.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Mandatory launch scenario: generated design with clarify + approvals on one Task / one pipeline
 * run.
 */
@QuarkusTest
class A2aGeneratedDesignE2EIT {

  private static final String BRIEF_HASH = "1".repeat(64);
  private static final String DESIGN_HASH = "2".repeat(64);
  private static final String PLAN_HASH = "3".repeat(64);
  private static final String MATERIALIZATION_HASH = "4".repeat(64);

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  private final AtomicInteger startCalls = new AtomicInteger();
  private final AtomicInteger continueCalls = new AtomicInteger();
  private final AtomicInteger approveCalls = new AtomicInteger();
  private final AtomicReference<CreateChainExecutionSnapshot> snapshot = new AtomicReference<>();
  private final AtomicReference<String> runId = new AtomicReference<>();

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    continueCalls.set(0);
    approveCalls.set(0);
    snapshot.set(null);
    runId.set(null);

    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              runId.set("run-" + command.taskId());
              CreateChainPendingAction.Approve pending =
                  new CreateChainPendingAction.Approve(
                      "requirement-brief", BRIEF_HASH, 1L, "Approve requirement brief");
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      runId.get(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      pending,
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Drafting requirement brief"),
                      new CreateChainEvent.Waiting(pending));
            });

    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              continueCalls.incrementAndGet();
              ContinueCreateChainCommand command = invocation.getArgument(0);
              CreateChainPendingAction.Approve pending =
                  new CreateChainPendingAction.Approve(
                      CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                      DESIGN_HASH,
                      2L,
                      "Approve generated IDS");
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      runId.get(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      2L,
                      pending,
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Generating IDS"),
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                          "design-gen-1",
                          DESIGN_HASH,
                          2L),
                      new CreateChainEvent.Waiting(pending));
            });

    when(facade.approve(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              approveCalls.incrementAndGet();
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              if ("requirement-brief".equals(command.artifactType())) {
                CreateChainPendingAction.Clarify clarify =
                    new CreateChainPendingAction.Clarify(
                        "Choose IDS path", List.of("generate-or-provide"));
                CreateChainExecutionSnapshot waiting =
                    new CreateChainExecutionSnapshot(
                        command.taskId(),
                        runId.get(),
                        CreateChainExecutionStatus.INPUT_REQUIRED,
                        2L,
                        clarify,
                        "");
                snapshot.set(waiting);
                return new ApproveCreateChainOutcome.Accepted(
                    List.of(new CreateChainEvent.Waiting(clarify)), waiting);
              }
              if (CreateChainPublicArtifactTypes.INTEGRATION_DESIGN.equals(command.artifactType())) {
                CreateChainPendingAction.Approve planPending =
                    new CreateChainPendingAction.Approve(
                        CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                        PLAN_HASH,
                        3L,
                        "Approve plan");
                CreateChainExecutionSnapshot waiting =
                    new CreateChainExecutionSnapshot(
                        command.taskId(),
                        runId.get(),
                        CreateChainExecutionStatus.INPUT_REQUIRED,
                        3L,
                        planPending,
                        "");
                snapshot.set(waiting);
                return new ApproveCreateChainOutcome.Accepted(
                    List.of(
                        new CreateChainEvent.ArtifactReady(
                            CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                            "plan-gen-1",
                            PLAN_HASH,
                            3L),
                        new CreateChainEvent.Waiting(planPending)),
                    waiting);
              }
              CreateChainExecutionSnapshot completed =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      runId.get(),
                      CreateChainExecutionStatus.COMPLETED,
                      4L,
                      null,
                      "");
              snapshot.set(completed);
              return new ApproveCreateChainOutcome.Accepted(
                  List.of(
                      new CreateChainEvent.Progress("Auto-implementing approved plan"),
                      new CreateChainEvent.ArtifactReady(
                          CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
                          "mat-gen-1",
                          MATERIALIZATION_HASH,
                          4L),
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
  void generatedDesignUsesOnePipelineRunAndOneChain() {
    String taskId =
        A2aE2eSupport.sendMessage(
            A2aE2eSupport.textMessageBody(
                UUID.randomUUID().toString(), null, "Create a pets HTTP integration"));

    A2aE2eSupport.approvePending(taskId);
    assertEquals("TASK_STATE_INPUT_REQUIRED", A2aE2eSupport.getTaskState(taskId));

    A2aE2eSupport.sendMessage(
        A2aE2eSupport.textMessageBody(
            UUID.randomUUID().toString(), taskId, "Generate full IDS"));
    A2aE2eSupport.approvePending(taskId);
    A2aE2eSupport.approvePending(taskId);

    assertEquals("TASK_STATE_COMPLETED", A2aE2eSupport.getTaskState(taskId));
    A2aE2eSupport.assertMaterializationResult(taskId);

    assertEquals(1, startCalls.get(), "only one pipeline start");
    assertEquals(1, continueCalls.get(), "one clarify continuation");
    assertEquals(3, approveCalls.get());
    assertEquals(1, taskRepository.findByTaskId(taskId).stream().count());
    assertEquals(taskId, taskRepository.findByTaskId(taskId).orElseThrow().conversationId());
    assertEquals(runId.get(), snapshot.get().runId());
    assertFalse(taskRepository.findByTaskId(taskId).orElseThrow().publicSnapshotJson().contains("s3://"));
  }
}
