package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ImplementationBlockedRecovery;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Protocol-level exact approval coverage: pending-action advertisement, success, rejection matrix,
 * free-form bypass denial, and implementation-blocked recovery.
 */
@QuarkusTest
class A2aApprovalTest {

  private static final String EXPECTED_TYPE = CreateChainPublicArtifactTypes.INTEGRATION_DESIGN;
  private static final String EXPECTED_HASH = "f".repeat(64);
  private static final long EXPECTED_REVISION = 3L;
  private static final String APPROVAL_TOKEN =
      EXPECTED_HASH.substring(0, A2aProtocolConstants.APPROVAL_TOKEN_LENGTH);

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  private final AtomicInteger approveCalls = new AtomicInteger();
  private final AtomicInteger continueCalls = new AtomicInteger();
  private final AtomicReference<CreateChainExecutionSnapshot> snapshot =
      new AtomicReference<>();
  private final AtomicReference<ApproveCreateChainOutcome> approveOutcome =
      new AtomicReference<>();

  @BeforeEach
  void stubFacade() {
    approveCalls.set(0);
    continueCalls.set(0);
    approveOutcome.set(null);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              StartCreateChainCommand command = invocation.getArgument(0);
              CreateChainPendingAction.Approve pending =
                  new CreateChainPendingAction.Approve(
                      EXPECTED_TYPE,
                      EXPECTED_HASH,
                      EXPECTED_REVISION,
                      "Review the integration design");
              snapshot.set(
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      "run-" + command.taskId(),
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      EXPECTED_REVISION,
                      pending,
                      ""));
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.ArtifactReady(
                          EXPECTED_TYPE, "design-1", EXPECTED_HASH, EXPECTED_REVISION),
                      new CreateChainEvent.Waiting(pending));
            });
    when(facade.approve(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              approveCalls.incrementAndGet();
              ApproveCreateChainOutcome outcome = approveOutcome.get();
              if (outcome == null) {
                ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
                CreateChainExecutionSnapshot current = snapshot.get();
                if (command.revision() != EXPECTED_REVISION) {
                  return new ApproveCreateChainOutcome.StaleRevision(
                      EXPECTED_REVISION, command.revision());
                }
                if (!EXPECTED_TYPE.equals(command.artifactType())) {
                  return new ApproveCreateChainOutcome.WrongArtifactType(
                      EXPECTED_TYPE, command.artifactType());
                }
                if (!EXPECTED_HASH.equals(command.artifactHash())) {
                  return new ApproveCreateChainOutcome.WrongArtifactHash(
                      EXPECTED_HASH, command.artifactHash());
                }
                CreateChainExecutionSnapshot after =
                    new CreateChainExecutionSnapshot(
                        command.taskId(),
                        current.runId(),
                        CreateChainExecutionStatus.WORKING,
                        EXPECTED_REVISION + 1,
                        null,
                        "");
                snapshot.set(after);
                return new ApproveCreateChainOutcome.Accepted(
                    List.of(new CreateChainEvent.Progress("Working")), after);
              }
              return outcome;
            });
    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              ApproveCreateChainOutcome outcome = approveOutcome.get();
              if (outcome != null) {
                approveCalls.incrementAndGet();
                return Optional.of(outcome);
              }
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              if (command.revision() != EXPECTED_REVISION) {
                return Optional.of(
                    new ApproveCreateChainOutcome.StaleRevision(
                        EXPECTED_REVISION, command.revision()));
              }
              if (!EXPECTED_TYPE.equals(command.artifactType())) {
                return Optional.of(
                    new ApproveCreateChainOutcome.WrongArtifactType(
                        EXPECTED_TYPE, command.artifactType()));
              }
              if (!EXPECTED_HASH.equals(command.artifactHash())) {
                return Optional.of(
                    new ApproveCreateChainOutcome.WrongArtifactHash(
                        EXPECTED_HASH, command.artifactHash()));
              }
              return Optional.empty();
            });
    when(facade.streamApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenAnswer(
            invocation -> {
              approveCalls.incrementAndGet();
              ApproveCreateChainArtifactCommand command = invocation.getArgument(0);
              CreateChainExecutionSnapshot current = snapshot.get();
              CreateChainExecutionSnapshot after =
                  new CreateChainExecutionSnapshot(
                      command.taskId(),
                      current.runId(),
                      CreateChainExecutionStatus.WORKING,
                      EXPECTED_REVISION + 1,
                      null,
                      "");
              snapshot.set(after);
              return Multi.createFrom().item(new CreateChainEvent.Progress("Working"));
            });
    when(facade.continueWithInput(any()))
        .thenAnswer(
            invocation -> {
              continueCalls.incrementAndGet();
              return Multi.createFrom().item(new CreateChainEvent.Progress("should not run"));
            });
    when(facade.snapshot(any())).thenAnswer(invocation -> Optional.ofNullable(snapshot.get()));
  }

  @Test
  void pendingApprovalAdvertisesExactArtifactWithoutInternalReference() {
    String taskId = createWaitingTask();

    Map<?, ?> body =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .when()
            .get(URI.create("/tasks/" + taskId))
            .then()
            .statusCode(200)
            .body("status.state", equalTo("TASK_STATE_INPUT_REQUIRED"))
            .extract()
            .path("status.message.parts.find { it.data != null }.data");

    assertEquals("approve", body.get("action"));
    assertEquals(EXPECTED_TYPE, body.get("artifactType"));
    assertEquals(EXPECTED_HASH, body.get("artifactHash"));
    assertEquals(EXPECTED_REVISION, ((Number) body.get("revision")).longValue());
    assertEquals(List.of("approve"), body.get("allowedActions"));
    assertEquals("Review the integration design", body.get("prompt"));

    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertFalse(persisted.publicSnapshotJson().contains("Reference["));
    assertFalse(persisted.publicSnapshotJson().contains("CompilationArtifacts"));
    assertFalse(persisted.publicSnapshotJson().contains("s3://"));
  }

  @Test
  void structuredApprovalAdvancesTaskOnceAndPlanPathStaysWorking() {
    String taskId = createWaitingTask();
    approveOutcome.set(null);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .body("task.id", equalTo(taskId))
        .body("task.status.state", equalTo("TASK_STATE_WORKING"));

    assertEquals(1, approveCalls.get());
    assertEquals(0, continueCalls.get());
    assertEquals(CreateChainExecutionStatus.WORKING, snapshot.get().status());
  }

  @Test
  void duplicateApprovalLeavesTaskUnchanged() {
    String taskId = createWaitingTask();
    approveOutcome.set(new ApproveCreateChainOutcome.DuplicateApproval());
    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"));

    assertEquals(1, approveCalls.get());
    assertEquals(CreateChainExecutionStatus.INPUT_REQUIRED, snapshot.get().status());
    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.INPUT_REQUIRED, after.state());
    assertTrue(after.revision() >= before.revision());
  }

  @Test
  void approvalOutsideWaitingStageIsRejected() {
    String taskId = createWaitingTask();
    // Force snapshot to WORKING without pending action for the next approve attempt.
    snapshot.set(
        new CreateChainExecutionSnapshot(
            taskId,
            "run-" + taskId,
            CreateChainExecutionStatus.WORKING,
            EXPECTED_REVISION,
            null,
            ""));

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(422);

    assertEquals(0, approveCalls.get());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectionCases")
  void approvalRejectionMatrixLeavesStateUnchanged(
      String label, String type, String hash, long revision, int expectedStatus) {
    String taskId = createWaitingTask();
    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();
    CreateChainExecutionSnapshot beforeSnap = snapshot.get();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, type, hash, revision))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(expectedStatus);

    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(before.state(), after.state());
    assertEquals(before.revision(), after.revision());
    assertEquals(beforeSnap.status(), snapshot.get().status());
    assertEquals(beforeSnap.revision(), snapshot.get().revision());
  }

  static Stream<Arguments> rejectionCases() {
    return Stream.of(
        Arguments.of("wrong type", CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN, EXPECTED_HASH, EXPECTED_REVISION, 422),
        Arguments.of("wrong hash", EXPECTED_TYPE, "0".repeat(64), EXPECTED_REVISION, 422),
        Arguments.of("stale revision", EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION - 1, 422));
  }

  @Test
  void freeFormApproveTextDoesNotBypassExactApproval() {
    String taskId = createWaitingTask();
    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();

    String status =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textBody(taskId, "approve"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            // An open stream carries no error frame, so the refusal arrives as the interrupted
            // state the Task is already in, not as a protocol error.
            .statusCode(200)
            .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"))
            .extract()
            .path("task.status.message.parts.find { it.text != null }.text");

    assertTrue(status.contains(APPROVAL_TOKEN), "refusal must name the token: " + status);
    assertEquals(0, approveCalls.get());
    assertEquals(0, continueCalls.get());
    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.INPUT_REQUIRED, after.state());
    // The refusal publishes a new status message, so the Task advances even though the pipeline
    // did not. A client that tracks revisions has to see the explanation it needs to act on.
    assertEquals(before.revision() + 1, after.revision());
  }

  @Test
  void approvalTokenTextApprovesWhenExtensionIsNotActivated() {
    String taskId = createWaitingTask();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(textBody(taskId, "approve " + APPROVAL_TOKEN))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200);

    assertEquals(1, approveCalls.get());
    assertEquals(0, continueCalls.get());
  }

  @Test
  void approvalTokenTextIsRefusedWhenExtensionIsActivated() {
    String taskId = createWaitingTask();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .header("A2A-Extensions", A2aProtocolConstants.EXACT_APPROVAL_EXTENSION_URI)
        .contentType(ContentType.JSON)
        .body(textBody(taskId, "approve " + APPROVAL_TOKEN))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"));

    assertEquals(0, approveCalls.get());
    assertEquals(0, continueCalls.get());
  }

  @Test
  void inputRequiredStatusPrintsTheApprovalToken() {
    String taskId = createWaitingTask();

    String status =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textBody(taskId, "still thinking"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.status.message.parts.find { it.text != null }.text");

    assertTrue(
        status.contains("approve " + APPROVAL_TOKEN), "status must print the token: " + status);
  }

  @Test
  void publicImplementActionIsRejectedWithoutStateChange() {
    String taskId = createWaitingTask();
    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(structuredBody(taskId, Map.of("action", "implement")))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(400);

    assertEquals(0, approveCalls.get());
    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(before.revision(), after.revision());
  }

  @Test
  void implementationBlockedApproveRecoveryContinuesSameTask() {
    String taskId = createWaitingTask();
    approveOutcome.set(
        new ApproveCreateChainOutcome.ImplementationBlocked(
            new ImplementationBlockedRecovery.ApprovePlanEvidence(
                "Approved plan hash is unavailable for automatic implementation.",
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                "p".repeat(64),
                7L)));

    Map<?, ?> pending =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(approveBody(taskId, EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .body("task.id", equalTo(taskId))
            .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"))
            .extract()
            .path("task.status.message.parts.find { it.data != null }.data");

    assertEquals("approve", pending.get("action"));
    assertEquals(
        CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN, pending.get("artifactType"));
    assertEquals(List.of("approve"), pending.get("allowedActions"));
    assertFalse(pending.containsKey("implement"));
  }

  @Test
  void implementationBlockedClarifyRecoveryRejectsApproveMismatch() {
    String taskId = createWaitingTask();
    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify(
            "Missing plan evidence", List.of("implementation-plan"));
    snapshot.set(
        new CreateChainExecutionSnapshot(
            taskId,
            "run-" + taskId,
            CreateChainExecutionStatus.INPUT_REQUIRED,
            8L,
            clarify,
            ""));
    // Re-seed persisted task pending through a no-op continue is awkward; set outcome for next
    // approve attempt against clarify pending.
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, EXPECTED_TYPE, EXPECTED_HASH, EXPECTED_REVISION))
        .when()
        .post(URI.create("/message:send"))
        .then()
        // The Task waits on a clarification, so an approval is input the agent cannot act on
        // rather than a failure. It keeps waiting for the clarification.
        .statusCode(200)
        .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"));

    assertEquals(0, approveCalls.get());
  }

  @Test
  void projectBlockedClarifyPendingActionSchema() {
    var projected =
        CreateChainA2aStateMapper.projectBlocked(
            "task-clarify",
            new ImplementationBlockedRecovery.ClarifyMissingEvidence(
                "Need plan evidence", List.of("implementation-plan")),
            new CreateChainExecutionSnapshot(
                "task-clarify", "run-1", CreateChainExecutionStatus.WORKING, 2L, null, ""));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertInstanceOf(CreateChainPendingAction.Clarify.class, projected.pendingAction());
    assertEquals("clarify", projected.pendingActionData().get("action"));
    assertEquals(List.of("clarify"), projected.pendingActionData().get("allowedActions"));
    assertEquals(
        List.of("implementation-plan"), projected.pendingActionData().get("missingEvidence"));
  }

  private String createWaitingTask() {
    return given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(textBody(null, "Build a sync chain"))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"))
        .extract()
        .path("task.id");
  }

  private static String textBody(String taskId, String text) {
    String taskField = taskId == null ? "" : "\"taskId\": \"%s\",".formatted(taskId);
    return """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            %s
            "role": "ROLE_USER",
            "parts": [ { "text": "%s" } ]
          }
        }
        """
        .formatted(UUID.randomUUID(), taskField, text);
  }

  private static String approveBody(String taskId, String type, String hash, long revision) {
    return structuredBody(
        taskId,
        Map.of(
            "action", "approve",
            "artifactType", type,
            "artifactHash", hash,
            "revision", revision));
  }

  private static String structuredBody(String taskId, Map<String, Object> data) {
    StringBuilder dataJson = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      if (!first) {
        dataJson.append(',');
      }
      first = false;
      dataJson.append('"').append(entry.getKey()).append("\":");
      Object value = entry.getValue();
      if (value instanceof Number || value instanceof Boolean) {
        dataJson.append(value);
      } else {
        dataJson.append('"').append(value).append('"');
      }
    }
    dataJson.append('}');
    return """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "taskId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "data": %s } ]
          }
        }
        """
        .formatted(UUID.randomUUID(), taskId, dataJson);
  }
}
