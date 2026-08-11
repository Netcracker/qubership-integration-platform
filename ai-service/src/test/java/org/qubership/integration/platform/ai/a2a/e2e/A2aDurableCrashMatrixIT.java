package org.qubership.integration.platform.ai.a2a.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.transport.A2aDispatchCrashGate;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

/**
 * Crash matrix over the real facade, runtime, and run store.
 *
 * <p>Each row crashes one command at one durable window, then retries the same caller-scoped
 * Message. The contract is at-least-once invocation with exactly-once durable effects, so the
 * assertions inspect the run document rather than counting facade calls.
 *
 * <p>Crash points are the three that differ in durable state. {@code AFTER_DISPATCHING} collapses
 * into {@code AFTER_CLAIM} because neither has touched the run document, and {@code
 * AFTER_COMPLETED} is a client-side delivery loss already covered by
 * {@link A2aInitialResponseRecoveryE2EIT}.
 */
@QuarkusTest
class A2aDurableCrashMatrixIT {

  private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);

  @Inject A2aDispatchCrashGate crashGate;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aMessageReceiptRepository receiptRepository;

  private A2aRealRuntimeFacadeFactory.Harness harness;

  enum Command {
    INITIAL_REQUIREMENTS,
    CLARIFICATION,
    DESIGN_APPROVAL,
    PLAN_APPROVAL_WITH_AUTO_IMPLEMENT
  }

  @AfterEach
  void disarm() {
    crashGate.clear();
  }

  static Stream<Arguments> matrix() {
    List<A2aDispatchCrashGate.Point> points =
        List.of(
            A2aDispatchCrashGate.Point.AFTER_CLAIM,
            A2aDispatchCrashGate.Point.AFTER_RUNTIME_COMMIT,
            A2aDispatchCrashGate.Point.AFTER_FIRST_PERSIST);
    return Stream.of(Command.values())
        .flatMap(command -> points.stream().map(point -> Arguments.of(command, point)));
  }

  @ParameterizedTest(name = "{0} crashing at {1}")
  @MethodSource("matrix")
  void retryProducesExactlyOneDurableEffect(Command command, A2aDispatchCrashGate.Point point)
      throws Exception {
    installHarness(command);

    String taskId = driveToPrecondition(command);
    String runIdBefore =
        taskId == null
            ? null
            : harness.runStore().loadByConversation(taskId).map(d -> d.run().runId()).orElse(null);

    String messageId = UUID.randomUUID().toString();
    String body = commandBody(command, taskId, messageId);

    crashGate.arm(point);
    int firstStatus = postMessage(body);
    assertTrue(firstStatus >= 500, "injected crash must fail the first attempt: " + firstStatus);

    String boundTaskId =
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .orElseThrow(() -> new AssertionError("claim must leave a recoverable Task binding"));
    if (taskId != null) {
      assertEquals(taskId, boundTaskId, "retry must not rebind the Task");
    }
    // A crashed dispatch must leave the Task recoverable. A terminal Task rejects every later
    // Message, so the caller could never retry and the receipt would stay incomplete forever.
    assertTrue(
        !List.of("TASK_STATE_COMPLETED", "TASK_STATE_FAILED")
            .contains(A2aE2eSupport.getTaskState(boundTaskId)),
        "crash must not leave the Task terminal; run status was "
            + harness.runStore().loadByConversation(boundTaskId).map(d -> d.run().status()));

    crashGate.clear();
    io.restassured.response.Response retry = sendMessage(body);
    assertEquals(
        200,
        retry.statusCode(),
        "retry must succeed: "
            + retry.asString()
            + " | run status "
            + harness.runStore().loadByConversation(boundTaskId).map(d -> d.run().status())
            + " | task state "
            + A2aE2eSupport.getTaskState(boundTaskId));

    assertEquals(
        A2aReceiptProcessingState.COMPLETED,
        receiptRepository
            .findCallerReceipt("local", "local-user", messageId)
            .orElseThrow()
            .processingState(),
        "no receipt may stay stuck in CLAIMED or DISPATCHING");
    assertEquals(1, taskRepository.findByTaskId(boundTaskId).stream().count());

    ProductPipelineRunDocument doc =
        harness.runStore().loadByConversation(boundTaskId).orElseThrow();
    if (runIdBefore != null) {
      assertEquals(runIdBefore, doc.run().runId(), "retry must not create a second pipeline run");
    }
    assertEquals(1, harness.bindingStore().load(boundTaskId).stream().count());
    assertEachCommandAppliedOnce(doc);
  }

  /** Every internal command ID must appear in at most one durable transition. */
  private static void assertEachCommandAppliedOnce(ProductPipelineRunDocument doc) {
    Map<String, Integer> byCommand = new LinkedHashMap<>();
    for (RunTransition transition : doc.transitions()) {
      if (transition.commandId() != null) {
        byCommand.merge(transition.commandId(), 1, Integer::sum);
      }
    }
    byCommand.forEach(
        (commandId, count) ->
            assertEquals(1, count, "command " + commandId + " produced " + count + " transitions"));
  }

  private void installHarness(Command command) {
    harness =
        command == Command.INITIAL_REQUIREMENTS || command == Command.CLARIFICATION
            ? A2aRealRuntimeFacadeFactory.generatedDesignPath()
            : A2aRealRuntimeFacadeFactory.providedIdsPath();
    QuarkusMock.installMockForType(harness.facade(), CreateChainApplicationFacade.class);
  }

  /** Runs the steps before the crashing command and returns the Task, or null for a new Task. */
  private String driveToPrecondition(Command command) throws Exception {
    if (command == Command.INITIAL_REQUIREMENTS) {
      return null;
    }
    String taskId = startTask(command);
    if (command == Command.CLARIFICATION) {
      requirePendingAction(taskId, "clarify");
      return taskId;
    }
    if (command == Command.DESIGN_APPROVAL) {
      requirePendingAction(taskId, "approve");
      return taskId;
    }
    // Plan approval: approve the earlier artifact so the next approval crosses the implementation
    // gate and triggers automatic implementation.
    requirePendingAction(taskId, "approve");
    A2aE2eSupport.approvePending(taskId);
    requirePendingAction(taskId, "approve");
    return taskId;
  }

  /**
   * Starts the Task. The clarification row opens with empty text: the facade only auto-submits the
   * requirement when it is non-blank, so the run stays waiting for input and advertises clarify.
   */
  private String startTask(Command command) throws Exception {
    String text =
        command == Command.CLARIFICATION
            ? ""
            : "# Integration Design Specification\\nIntegration flow for CIP Chain - Pets GET /pets";
    String body = A2aE2eSupport.textMessageBody(UUID.randomUUID().toString(), null, text);
    List<com.fasterxml.jackson.databind.JsonNode> events =
        A2aE2eSupport.streamCreate(body, STREAM_TIMEOUT);
    assertTrue(!events.isEmpty(), "start must stream at least one event");
    return A2aE2eSupport.extractTaskId(events.get(0));
  }

  /**
   * The streaming call blocks until the Task closes, so the pending action is already settled here.
   */
  private void requirePendingAction(String taskId, String action) {
    String state = A2aE2eSupport.getTaskState(taskId);
    assertEquals("TASK_STATE_INPUT_REQUIRED", state, "task must wait before the crashing command");
    Map<String, Object> pending = A2aE2eSupport.pendingData(taskId);
    assertEquals(action, String.valueOf(pending.get("action")), pending.toString());
  }

  private String commandBody(Command command, String taskId, String messageId) {
    return switch (command) {
      case INITIAL_REQUIREMENTS ->
          A2aE2eSupport.textMessageBody(
              messageId,
              null,
              "# Integration Design Specification\\nIntegration flow for CIP Chain - Pets GET /pets");
      case CLARIFICATION -> A2aE2eSupport.textMessageBody(messageId, taskId, "create greetings API");
      case DESIGN_APPROVAL, PLAN_APPROVAL_WITH_AUTO_IMPLEMENT -> approveBody(taskId, messageId);
    };
  }

  private String approveBody(String taskId, String messageId) {
    Map<String, Object> pending = A2aE2eSupport.pendingData(taskId);
    assertNotNull(pending.get("artifactType"), "pending approve must advertise an artifact type");
    return A2aE2eSupport.approveBody(
        messageId,
        taskId,
        String.valueOf(pending.get("artifactType")),
        String.valueOf(pending.get("artifactHash")),
        ((Number) pending.get("revision")).longValue());
  }

  private static int postMessage(String body) {
    return sendMessage(body).statusCode();
  }

  private static io.restassured.response.Response sendMessage(String body) {
    return given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .extract()
        .response();
  }
}
