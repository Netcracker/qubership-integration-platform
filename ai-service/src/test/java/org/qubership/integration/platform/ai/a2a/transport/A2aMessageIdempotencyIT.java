package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/** Slice 5: (taskId, messageId) idempotency and fingerprint conflict over REST. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class A2aMessageIdempotencyIT {

  @InjectMock CreateChainApplicationFacade facade;
  @InjectMock org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy accessPolicy;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aMessageReceiptRepository receiptRepository;

  private final AtomicInteger startCalls = new AtomicInteger();

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
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
  }

  @Test
  void lostInitialRetryWithoutClientTaskIdReturnsOriginalTask() {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "text": "idempotent" } ]
          }
        }
        """
            .formatted(messageId);

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
            .extract()
            .path("task.id");

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

    assertEquals(taskId, secondTaskId);
    assertEquals(1, startCalls.get());
  }

  @Test
  void sameMessageIdWithAddedClientTaskIdIsIdempotencyConflict() {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "text": "idempotent" } ]
          }
        }
        """
            .formatted(messageId);

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
            .extract()
            .path("task.id");

    String secondBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "taskId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "text": "idempotent" } ]
          }
        }
        """
            .formatted(messageId, taskId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(secondBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(422);

    assertEquals(1, startCalls.get());
  }

  @Test
  void sameMessageIdWithChangedClientContextIdIsIdempotencyConflict() {
    String messageId = UUID.randomUUID().toString();
    String firstBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-a",
            "role": "ROLE_USER",
            "parts": [ { "text": "idempotent" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(firstBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200);

    String secondBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-b",
            "role": "ROLE_USER",
            "parts": [ { "text": "idempotent" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(secondBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(422);

    assertEquals(1, startCalls.get());
  }

  @Test
  void lostInitialRetryWithSameContextIdReturnsOriginalTask() {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-stable",
            "role": "ROLE_USER",
            "parts": [ { "text": "retry with context" } ]
          }
        }
        """
            .formatted(messageId);

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
            .extract()
            .path("task.id");

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
    assertEquals(1, startCalls.get());
  }

  @Test
  void lostInitialRetryWithSameContextIdButDifferentBodyConflicts() {
    String messageId = UUID.randomUUID().toString();
    String firstBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-stable",
            "role": "ROLE_USER",
            "parts": [ { "text": "original command" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(firstBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200);

    String conflictBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-stable",
            "role": "ROLE_USER",
            "parts": [ { "text": "different command body" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(conflictBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(anyOf(is(409), is(422)));
    assertEquals(1, startCalls.get());
  }

  @Test
  void concurrentSameMessageIdDistinctCorrelationIdentities() throws Exception {
    A2aClientCorrelationCarrier.clearAll();
    A2aClientCorrelationCarrier.Binding first =
        A2aClientCorrelationCarrier.bind(null, "ctx-a");
    A2aClientCorrelationCarrier.Binding second =
        A2aClientCorrelationCarrier.bind(null, "ctx-b");
    assertNotEquals(first.requestId(), second.requestId());
    assertEquals("ctx-a", first.holder().contextId());
    assertEquals("ctx-b", second.holder().contextId());
    assertEquals("ctx-a", A2aClientCorrelationCarrier.lookup(first.requestId()).contextId());
    assertEquals("ctx-b", A2aClientCorrelationCarrier.lookup(second.requestId()).contextId());
    assertFalse(A2aClientCorrelationCarrier.clear("not-an-owner"));
    assertTrue(A2aClientCorrelationCarrier.containsForTest(first.requestId()));
    assertTrue(A2aClientCorrelationCarrier.clear(first.requestId()));
    assertFalse(A2aClientCorrelationCarrier.containsForTest(first.requestId()));
    assertTrue(A2aClientCorrelationCarrier.containsForTest(second.requestId()));
    A2aClientCorrelationCarrier.clear(second.requestId());
    assertEquals(0, A2aClientCorrelationCarrier.sizeForTest());
  }

  @Test
  void concurrentDifferentContextIdsConflictOverHttp() throws Exception {
    String messageId = "concurrent-ctx-" + UUID.randomUUID();
    java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(3);
    java.util.concurrent.atomic.AtomicInteger statusA =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger statusB =
        new java.util.concurrent.atomic.AtomicInteger();
    String bodyA =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-a",
            "role": "ROLE_USER",
            "parts": [ { "text": "same body" } ]
          }
        }
        """
            .formatted(messageId);
    String bodyB =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-b",
            "role": "ROLE_USER",
            "parts": [ { "text": "same body" } ]
          }
        }
        """
            .formatted(messageId);
    Thread t1 =
        new Thread(
            () -> {
              try {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                statusA.set(
                    given()
                        .urlEncodingEnabled(false)
                        .header("A2A-Version", "1.0")
                        .contentType(ContentType.JSON)
                        .body(bodyA)
                        .when()
                        .post(URI.create("/message:send"))
                        .then()
                        .extract()
                        .statusCode());
                barrier.await(20, java.util.concurrent.TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Thread t2 =
        new Thread(
            () -> {
              try {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                statusB.set(
                    given()
                        .urlEncodingEnabled(false)
                        .header("A2A-Version", "1.0")
                        .contentType(ContentType.JSON)
                        .body(bodyB)
                        .when()
                        .post(URI.create("/message:send"))
                        .then()
                        .extract()
                        .statusCode());
                barrier.await(20, java.util.concurrent.TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    t1.start();
    t2.start();
    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
    barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
    t1.join(30_000);
    t2.join(30_000);
    java.util.Set<Integer> statuses = java.util.Set.of(statusA.get(), statusB.get());
    assertTrue(statuses.contains(200), "one request must succeed: " + statuses);
    assertTrue(statuses.contains(422), "other request must conflict: " + statuses);
    assertEquals(1, startCalls.get());
    assertEquals(0, A2aClientCorrelationCarrier.sizeForTest());
  }

  @Test
  void streamRetryWithSameContextIdReturnsOriginalTask() throws Exception {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "contextId": "ctx-stream",
            "role": "ROLE_USER",
            "parts": [ { "text": "stream retry" } ]
          }
        }
        """
            .formatted(messageId);

    java.util.List<com.fasterxml.jackson.databind.JsonNode> first =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/message:stream", body, java.time.Duration.ofSeconds(20));
    assertFalse(first.isEmpty());
    String taskId =
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .orElseThrow();

    java.util.List<com.fasterxml.jackson.databind.JsonNode> second =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/message:stream", body, java.time.Duration.ofSeconds(20));
    assertFalse(second.isEmpty());
    assertEquals(
        taskId,
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .orElseThrow());
    assertEquals(1, startCalls.get());
  }

  @Test
  void reusedMessageIdWithDifferentBodyReturnsConflict() {
    String messageId = UUID.randomUUID().toString();
    String firstBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "text": "first body" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(firstBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200);

    String conflictBody =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [ { "text": "different body" } ]
          }
        }
        """
            .formatted(messageId);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(conflictBody)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(422);

    assertEquals(1, startCalls.get());
  }

  @Test
  void invalidInitialApproveLeavesNoDurableRows() {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "role": "ROLE_USER",
            "parts": [ {
              "data": {
                "action": "approve",
                "artifactType": "implementation-plan",
                "artifactHash": "%s",
                "revision": 1
              }
            } ]
          }
        }
        """
            .formatted(messageId, "a".repeat(64));

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(anyOf(is(400), is(422)));

    assertTrue(
        receiptRepository.findTaskIdForCallerMessage("local", "local-user", messageId).isEmpty());
    assertTrue(taskRepository.findByTaskId(messageId).isEmpty());
    assertEquals(0, startCalls.get());
  }

  @ParameterizedTest
  @MethodSource("terminalReceiptCases")
  void incompleteTerminalReceiptRequiresExactCommandEvidence(
      boolean applied, boolean owned, boolean streaming, boolean denied) throws Exception {
    String messageId = UUID.randomUUID().toString();
    String taskId = "receipt-" + UUID.randomUUID();
    var message = org.a2aproject.sdk.spec.Message.builder().messageId(messageId)
        .role(org.a2aproject.sdk.spec.Message.Role.ROLE_USER)
        .parts(List.of(new org.a2aproject.sdk.spec.TextPart("saved input"))).build();
    var command = A2aInboundMessageParser.parse(message);
    var task = org.a2aproject.sdk.spec.Task.builder().id(taskId).contextId(taskId)
        .status(new org.a2aproject.sdk.spec.TaskStatus(org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING, null, null))
        .history(List.of(message)).build();
    String workingJson = org.a2aproject.sdk.jsonrpc.common.json.JsonUtil.toJson(task);
    receiptRepository.claimInitialWithWorkingTask("local", "local-user", messageId,
        A2aCommandFingerprint.compute(message, command), A2aInboundMessageParser.commandKind(command, true),
        new org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate(taskId, taskId, taskId,
            org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState.WORKING, "local", "local-user",
            workingJson, "[]", "[]", null));
    String completedJson = org.a2aproject.sdk.jsonrpc.common.json.JsonUtil.toJson(
        org.a2aproject.sdk.spec.Task.builder(task)
            .status(new org.a2aproject.sdk.spec.TaskStatus(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED, null, null))
            .build());
    taskRepository.transition(taskId, new org.qubership.integration.platform.ai.a2a.persistence.A2aTaskUpdate(1L,
        org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState.COMPLETED,
        completedJson, "[]", "[]", java.time.Instant.now()));
    when(facade.inputAlreadyApplied(taskId, A2aCommandId.derive("local", "local-user", messageId), "saved input"))
        .thenReturn(applied);
    var owner = owned ? receiptRepository.acquireDispatch("local", "local-user", messageId).ownerToken() : null;
    if (denied) {
      org.mockito.Mockito.doThrow(new org.qubership.integration.platform.ai.a2a.access.TaskAccessDeniedException("denied"))
          .when(accessPolicy).check(any(), any(), any());
    }

    String body = A2aSseTestSupport.textMessageBody(messageId, null, "saved input");
    if (streaming) {
      var events = A2aSseTestSupport.collectSseEvents("POST", "/message:stream", body, java.time.Duration.ofSeconds(10));
      assertEquals(1, events.size());
      assertEquals(taskId, events.getFirst().path("task").path("id").asText());
    } else {
      given().urlEncodingEnabled(false).header("A2A-Version", "1.0").contentType(ContentType.JSON)
          .body(body).post(URI.create("/message:send")).then().statusCode(denied ? 404 : applied ? 200 : 400);
    }
    assertEquals(applied && !owned && !denied,
        receiptRepository.findCallerReceipt("local", "local-user", messageId).orElseThrow().completed());
    if (owned) {
      assertTrue(receiptRepository.renewDispatch("local", "local-user", messageId, owner), "a replay cannot steal a live lease");
      receiptRepository.releaseDispatch("local", "local-user", messageId, owner);
    }
    assertEquals(2L, taskRepository.findByTaskId(taskId).orElseThrow().revision());
    assertEquals(0, startCalls.get());
  }

  static java.util.stream.Stream<Arguments> terminalReceiptCases() {
    return java.util.stream.Stream.of(Arguments.of(false, false, false, false), Arguments.of(true, false, false, false),
        Arguments.of(true, true, false, false), Arguments.of(true, false, true, false), Arguments.of(true, false, false, true));
  }
}
