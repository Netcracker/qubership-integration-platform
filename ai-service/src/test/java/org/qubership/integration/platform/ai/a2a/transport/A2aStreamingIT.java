package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

@QuarkusTest
class A2aStreamingIT {

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  private final AtomicInteger startCalls = new AtomicInteger();

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              if (taskId == null) {
                return Optional.empty();
              }
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of()),
                      ""));
            });
    when(facade.approve(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              CreateChainExecutionSnapshot completed =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run",
                      CreateChainExecutionStatus.COMPLETED,
                      2L,
                      null,
                      "");
              return new ApproveCreateChainOutcome.Accepted(
                  List.of(
                      new CreateChainEvent.Progress("Implementing approved plan"),
                      new CreateChainEvent.Completed(completed)),
                  completed);
            });
    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(Optional.empty());
    when(facade.streamApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              ApproveCreateChainOutcome outcome = facade.approve(invocation.getArgument(0));
              ApproveCreateChainOutcome.Accepted accepted =
                  (ApproveCreateChainOutcome.Accepted) outcome;
              return Multi.createFrom().iterable(accepted.events());
            });
  }

  @Test
  void orderedStreamEmitsTaskWorkingInputRequiredThenCompletes() throws Exception {
    String body =
        A2aSseTestSupport.textMessageBody(UUID.randomUUID().toString(), null, "Build a chain");

    List<JsonNode> events =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/message:stream", body, Duration.ofSeconds(20));

    assertFalse(events.isEmpty(), "expected SSE events");
    assertTrue(A2aSseTestSupport.isTaskEvent(events.get(0)), "first event must be Task snapshot");

    List<String> states =
        events.stream().map(A2aSseTestSupport::eventState).filter(s -> !s.isBlank()).toList();
    assertTrue(states.contains("TASK_STATE_WORKING"), "expected WORKING in " + states);
    assertTrue(
        states.contains("TASK_STATE_INPUT_REQUIRED"), "expected INPUT_REQUIRED in " + states);
    int workingIdx = states.indexOf("TASK_STATE_WORKING");
    int inputIdx = states.indexOf("TASK_STATE_INPUT_REQUIRED");
    assertTrue(workingIdx >= 0 && inputIdx > workingIdx, "WORKING before INPUT_REQUIRED: " + states);

    String taskId = extractTaskId(events.get(0));
    assertTrue(taskRepository.findByTaskId(taskId).isPresent());
    assertEquals(1, startCalls.get());
  }

  @Test
  void planApprovalStreamStaysWorkingThroughAutoImplement() throws Exception {
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              CreateChainExecutionSnapshot completed =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.COMPLETED,
                      3L,
                      null,
                      "");
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Planning"),
                      new CreateChainEvent.Progress("Auto-implementing approved plan"),
                      new CreateChainEvent.ArtifactReady(
                          "materialization-result", "art-1", "hash-1", 3L),
                      new CreateChainEvent.Completed(completed));
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      CreateChainExecutionStatus.COMPLETED,
                      3L,
                      null,
                      ""));
            });

    String body =
        A2aSseTestSupport.textMessageBody(
            UUID.randomUUID().toString(), null, "IDS with approved plan hash");

    List<JsonNode> events =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/message:stream", body, Duration.ofSeconds(20));

    List<String> states =
        events.stream().map(A2aSseTestSupport::eventState).filter(s -> !s.isBlank()).toList();
    assertTrue(states.contains("TASK_STATE_WORKING"), "expected WORKING in " + states);
    assertTrue(states.contains("TASK_STATE_COMPLETED"), "expected COMPLETED in " + states);
    assertFalse(
        states.contains("TASK_STATE_INPUT_REQUIRED"),
        "WAITING_FOR_IMPLEMENT auto-implement must not emit INPUT_REQUIRED: " + states);
  }

  @Test
  void holdOpenFacadeEmitsProgressBeforeCompletion() throws Exception {
    CountDownLatch progressSeenByFacade = new CountDownLatch(1);
    CountDownLatch releaseCompletion = new CountDownLatch(1);
    AtomicInteger startCallsLocal = new AtomicInteger();

    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCallsLocal.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              return Multi.createFrom()
                  .emitter(
                      emitter -> {
                        emitter.emit(new CreateChainEvent.Progress("Live progress"));
                        progressSeenByFacade.countDown();
                        try {
                          if (!releaseCompletion.await(15, TimeUnit.SECONDS)) {
                            emitter.fail(new IllegalStateException("hold-open release timed out"));
                            return;
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          emitter.fail(interrupted);
                          return;
                        }
                        emitter.emit(
                            new CreateChainEvent.Waiting(
                                new CreateChainPendingAction.Clarify(
                                    "Additional input is required.", List.of())));
                        emitter.complete();
                      });
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              boolean released = releaseCompletion.getCount() == 0;
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      released
                          ? CreateChainExecutionStatus.INPUT_REQUIRED
                          : CreateChainExecutionStatus.WORKING,
                      released ? 1L : 0L,
                      released
                          ? new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())
                          : null,
                      ""));
            });

    String body =
        A2aSseTestSupport.textMessageBody(UUID.randomUUID().toString(), null, "Hold open stream");

    A2aSseTestSupport.FirstFrameSse open =
        A2aSseTestSupport.openUntilFirstFrame(
            "POST", "/message:stream", body, Duration.ofSeconds(20));
    assertFalse(open.firstEvents().isEmpty());
    assertTrue(
        progressSeenByFacade.await(5, TimeUnit.SECONDS),
        "facade Multi must still be open when first SSE frame arrives");
    assertEquals(1, releaseCompletion.getCount(), "facade completion must still be held");

    String taskId = extractTaskId(open.firstEvents().get(0));
    assertTrue(taskRepository.findByTaskId(taskId).isPresent());
    assertEquals(
        "TASK_STATE_WORKING",
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .when()
            .get(URI.create("/tasks/" + taskId))
            .then()
            .statusCode(200)
            .extract()
            .path("status.state"));

    releaseCompletion.countDown();
    List<JsonNode> all = A2aSseTestSupport.drainRemaining(open);
    List<String> states =
        all.stream().map(A2aSseTestSupport::eventState).filter(s -> !s.isBlank()).toList();
    assertTrue(states.contains("TASK_STATE_WORKING"), states.toString());
    assertTrue(states.contains("TASK_STATE_INPUT_REQUIRED"), states.toString());
    assertEquals(1, startCallsLocal.get());
  }

  private static String extractTaskId(JsonNode first) {
    if (first.has("id")) {
      return first.get("id").asText();
    }
    if (first.has("task") && first.get("task").has("id")) {
      return first.get("task").get("id").asText();
    }
    throw new AssertionError("Unable to extract task id from " + first);
  }
}
