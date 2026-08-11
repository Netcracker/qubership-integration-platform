package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * REST contract coverage for create, continue, get, idempotency, and protocol errors.
 */
@QuarkusTest
class A2aTransportContractIT {

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  @Inject TaskStore taskStore;

  private final AtomicInteger startCalls = new AtomicInteger();
  private final AtomicInteger continueCalls = new AtomicInteger();
  private boolean completeOnStart;

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    continueCalls.set(0);
    completeOnStart = false;
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              if (completeOnStart) {
                CreateChainExecutionSnapshot completed =
                    new CreateChainExecutionSnapshot(
                        command.taskId(),
                        "run-" + command.taskId(),
                        CreateChainExecutionStatus.COMPLETED,
                        1L,
                        null,
                        "");
                return Multi.createFrom().item(new CreateChainEvent.Completed(completed));
              }
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              continueCalls.incrementAndGet();
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Still need more detail.", List.of())));
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              if (completeOnStart && continueCalls.get() == 0 && startCalls.get() > 0) {
                return Optional.of(
                    new CreateChainExecutionSnapshot(
                        taskId,
                        "run-" + taskId,
                        CreateChainExecutionStatus.COMPLETED,
                        1L,
                        null,
                        ""));
              }
              CreateChainPendingAction pending =
                  continueCalls.get() > 0
                      ? new CreateChainPendingAction.Clarify(
                          "Still need more detail.", List.of())
                      : new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of());
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L + continueCalls.get(),
                      pending,
                      ""));
            });
  }

  @Test
  void createTaskPersistsSnapshotAndUsesTaskIdAsConversationId() {
    String messageId = UUID.randomUUID().toString();
    String body = textMessageBody(messageId, null, "Build a payment chain");

    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .body("task.id", notNullValue())
            .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"))
            .extract()
            .path("task.id");

    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(taskId, persisted.conversationId());
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());
    assertTrue(persisted.publicSnapshotJson().contains(taskId));
    assertEquals(1, startCalls.get());
  }

  @Test
  void continueInputRequiredAdvancesSameTaskWithoutSecondCreate() {
    String createMessageId = UUID.randomUUID().toString();
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textMessageBody(createMessageId, null, "Start requirements"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    String continueMessageId = UUID.randomUUID().toString();
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(textMessageBody(continueMessageId, taskId, "Clarification details"))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .body("task.id", equalTo(taskId))
        .body("task.status.state", equalTo("TASK_STATE_INPUT_REQUIRED"));

    assertEquals(1, startCalls.get());
    assertEquals(1, continueCalls.get());
    assertEquals(1, taskRepository.findByTaskId(taskId).stream().count());
  }

  @Test
  void getTaskReturnsDurableSnapshot() throws Exception {
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textMessageBody(UUID.randomUUID().toString(), null, "Poll me"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    Task durable = JsonUtil.fromJson(persisted.publicSnapshotJson(), Task.class);
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());

    // Clear the SDK in-memory store so Get Task cannot rely on same-process cache.
    taskStore.delete(taskId);
    assertNull(taskStore.get(taskId));

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get(URI.create("/tasks/" + taskId))
        .then()
        .statusCode(200)
        .body("id", equalTo(durable.id()))
        .body("contextId", equalTo(durable.contextId()))
        .body("status.state", equalTo(durable.status().state().name()));
  }

  @Test
  void getUnknownTaskReturnsTaskNotFoundWithoutCreatingState() {
    String missingTaskId = "missing-get-" + UUID.randomUUID();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get(URI.create("/tasks/" + missingTaskId))
        .then()
        .statusCode(404)
        .body("error.details[0].reason", equalTo("TASK_NOT_FOUND"));

    assertTrue(taskRepository.findByTaskId(missingTaskId).isEmpty());
    assertNull(taskStore.get(missingTaskId));
  }

  @Test
  void duplicateMessageIdIsIdempotent() {
    String messageId = UUID.randomUUID().toString();
    String body = textMessageBody(messageId, null, "Idempotent create");

    String firstTaskId =
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

    // Lost-initial retry: same Message without taskId. Adding a client taskId is a different
    // canonical command and must conflict (covered by A2aMessageIdempotencyIT).
    String secondTaskId =
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

    assertEquals(firstTaskId, secondTaskId);
    assertEquals(1, startCalls.get());
    assertEquals(0, continueCalls.get());
  }

  @Test
  void terminalContinuationIsRejected() {
    completeOnStart = true;
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textMessageBody(UUID.randomUUID().toString(), null, "finish"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .body("task.status.state", equalTo("TASK_STATE_COMPLETED"))
            .extract()
            .path("task.id");

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(textMessageBody(UUID.randomUUID().toString(), taskId, "too late"))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(400)
        .body("error.details[0].reason", equalTo("UNSUPPORTED_OPERATION"));
  }

  @Test
  void unknownTaskReturnsTaskNotFound() {
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(textMessageBody(UUID.randomUUID().toString(), "missing-task", "hello"))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(404)
        .body("error.details[0].reason", equalTo("TASK_NOT_FOUND"));
  }

  @Test
  void cancelReturnsTaskNotCancelableWithoutStateChange() {
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textMessageBody(UUID.randomUUID().toString(), null, "cancel me"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(URI.create("/tasks/" + taskId + ":cancel"))
        .then()
        .statusCode(409)
        .body("error.details[0].reason", equalTo("TASK_NOT_CANCELABLE"));

    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(before.state(), after.state());
    assertEquals(before.revision(), after.revision());
    assertEquals(before.publicSnapshotJson(), after.publicSnapshotJson());
  }

  @Test
  void unsupportedFilePartReturnsContentTypeError() {
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [
              {
                "raw": "YQ==",
                "filename": "a.txt",
                "mediaType": "text/plain"
              }
            ]
          }
        }
        """
            .formatted(UUID.randomUUID());

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(415)
        .body("error.details[0].reason", equalTo("CONTENT_TYPE_NOT_SUPPORTED"));
  }

  @Test
  void publicImplementActionIsRejected() {
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(textMessageBody(UUID.randomUUID().toString(), null, "start"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "taskId": "%s",
            "role": "ROLE_USER",
            "parts": [
              {
                "data": {
                  "action": "implement"
                }
              }
            ]
          }
        }
        """
            .formatted(UUID.randomUUID(), taskId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(400)
        .body("error.details[0].reason", equalTo("UNSUPPORTED_OPERATION"))
        .body("error.message", containsString("implement"));
  }

  private static String textMessageBody(String messageId, String taskId, String text) {
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
        .formatted(messageId, taskField, text);
  }
}
