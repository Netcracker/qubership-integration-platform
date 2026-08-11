package org.qubership.integration.platform.ai.a2a.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.transport.A2aDispatchCrashGate;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Package 02 crash windows: fail after claim / dispatching / first persist / completed, then retry
 * the same caller-scoped Message and resume without duplicate facade dispatch ownership.
 */
@QuarkusTest
class A2aCrashWindowIT {

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aDispatchCrashGate crashGate;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aMessageReceiptRepository receiptRepository;

  private final AtomicInteger startCalls = new AtomicInteger();
  private final AtomicInteger continueCalls = new AtomicInteger();
  private final AtomicReference<CreateChainExecutionSnapshot> snapshot = new AtomicReference<>();

  @BeforeEach
  void stubFacade() {
    crashGate.clear();
    startCalls.set(0);
    continueCalls.set(0);
    snapshot.set(null);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of()),
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working after resume"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              continueCalls.incrementAndGet();
              ContinueCreateChainCommand command = invocation.getArgument(0);
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of()),
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working after resume"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.snapshot(any()))
        .thenAnswer(invocation -> Optional.ofNullable(snapshot.get()));
  }

  @AfterEach
  void disarm() {
    crashGate.clear();
  }

  static Stream<A2aDispatchCrashGate.Point> crashPoints() {
    return Stream.of(
        A2aDispatchCrashGate.Point.AFTER_CLAIM,
        A2aDispatchCrashGate.Point.AFTER_DISPATCHING,
        A2aDispatchCrashGate.Point.AFTER_FIRST_PERSIST,
        A2aDispatchCrashGate.Point.AFTER_COMPLETED);
  }

  @ParameterizedTest
  @MethodSource("crashPoints")
  void retryAfterInjectedCrashResumesSameTask(A2aDispatchCrashGate.Point point) {
    String messageId = "crash-" + point + "-" + UUID.randomUUID();
    String body = A2aE2eSupport.textMessageBody(messageId, null, "Crash window " + point);

    crashGate.arm(point);
    int firstStatus =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(URI.create("/message:send"))
            .then()
            .extract()
            .statusCode();

    Optional<String> taskIdOpt =
        receiptRepository.findTaskIdForCallerMessage("local", "local-user", messageId);
    assertTrue(taskIdOpt.isPresent(), "claim must leave a recoverable Task binding");
    String taskId = taskIdOpt.get();
    assertTrue(taskRepository.findByTaskId(taskId).isPresent());

    A2aCallerMessageReceipt afterCrash =
        receiptRepository.findCallerReceipt("local", "local-user", messageId).orElseThrow();
    if (point == A2aDispatchCrashGate.Point.AFTER_COMPLETED) {
      assertEquals(A2aReceiptProcessingState.COMPLETED, afterCrash.processingState());
      assertTrue(firstStatus == 200 || firstStatus >= 500, "status=" + firstStatus);
    } else {
      assertTrue(
          afterCrash.processingState() == A2aReceiptProcessingState.CLAIMED
              || afterCrash.processingState() == A2aReceiptProcessingState.DISPATCHING,
          afterCrash.processingState().name());
      assertTrue(
          firstStatus >= 500, "injected crash must fail the first HTTP attempt: " + firstStatus);
    }

    // AFTER_FIRST_PERSIST may have published INPUT_REQUIRED before the crash; keep snapshot.
    if (point == A2aDispatchCrashGate.Point.AFTER_CLAIM
        || point == A2aDispatchCrashGate.Point.AFTER_DISPATCHING) {
      snapshot.set(null);
    }

    crashGate.clear();
    String recovered =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    assertEquals(taskId, recovered);
    A2aCallerMessageReceipt completed =
        receiptRepository.findCallerReceipt("local", "local-user", messageId).orElseThrow();
    assertEquals(A2aReceiptProcessingState.COMPLETED, completed.processingState());
    assertEquals("TASK_STATE_INPUT_REQUIRED", A2aE2eSupport.getTaskState(taskId));
    assertEquals(1, taskRepository.findByTaskId(taskId).stream().count());

    // The launch contract is at-least-once facade invocation with exactly-once durable effects.
    // A retry redispatches the same command on purpose; the run document's command evidence, not
    // the transport, keeps the durable effect single. This IT stubs the facade, so it can only
    // prove transport recovery. Exactly-once durable effects are proven against the real facade
    // and runtime in A2aDurableCrashMatrixIT.
    assertTrue(
        startCalls.get() + continueCalls.get() >= 1,
        "retry must redispatch the same facade command for point " + point);

    assertInstanceOf(A2aCallerMessageReceipt.class, completed);
  }
}
