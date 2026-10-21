package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

@QuarkusTest
class A2aSubscriptionIT {

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aTaskSnapshotPersister persister;

  @Inject TaskEventHub eventHub;

  @Inject CreateChainCancelRejectingRequestHandler requestHandler;

  @BeforeEach
  void stubFacade() {
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              StartCreateChainCommand command = invocation.getArgument(0);
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify("need more", List.of())));
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
                      new CreateChainPendingAction.Clarify("need more", List.of()),
                      ""));
            });
  }

  @Test
  void subscribeReturnsDurableSnapshotThenLiveUpdates() throws Exception {
    String taskId = "sub-" + UUID.randomUUID();
    persistWorking(taskId);

    AssertSubscriber<StreamingEventKind> live =
        eventHub.subscribe(taskId).subscribe().withSubscriber(AssertSubscriber.create(10));

    assertTrue(taskRepository.findByTaskId(taskId).isPresent());

    eventHub.publish(
        taskId,
        A2aStreamingEventSupport.statusUpdate(
            taskId, taskId, A2aTaskState.COMPLETED, "done"));
    live.awaitCompletion(Duration.ofSeconds(2));
    assertFalse(live.getItems().isEmpty());
    assertEquals(
        A2aTaskState.COMPLETED.toSdk(),
        ((TaskStatusUpdateEvent) live.getItems().get(live.getItems().size() - 1))
            .status()
            .state());

    // Force INPUT_REQUIRED durable state and verify subscribe returns snapshot then completes.
    String closedTaskId = "sub-closed-" + UUID.randomUUID();
    persistState(closedTaskId, A2aTaskState.INPUT_REQUIRED);
    List<JsonNode> closedSub =
        A2aSseTestSupport.collectSseEvents(
            "POST",
            "/tasks/" + closedTaskId + ":subscribe",
            "{}",
            Duration.ofSeconds(10));
    assertFalse(closedSub.isEmpty());
    assertTrue(A2aSseTestSupport.isTaskEvent(closedSub.get(0)));
    assertEquals(
        "TASK_STATE_INPUT_REQUIRED", A2aSseTestSupport.eventState(closedSub.get(0)));
  }

  @Test
  void subscribeAfterInputRequiredContinueReceivesLiveUpdates() throws Exception {
    String taskId = "reopen-" + UUID.randomUUID();

    // Episode 1: durable INPUT_REQUIRED closes the stream and the hub channel.
    persistState(taskId, A2aTaskState.INPUT_REQUIRED);
    List<JsonNode> closed =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/tasks/" + taskId + ":subscribe", "{}", Duration.ofSeconds(10));
    assertFalse(closed.isEmpty());
    assertEquals(
        "TASK_STATE_INPUT_REQUIRED", A2aSseTestSupport.eventState(closed.get(0)));

    // Continue: Task leaves INPUT_REQUIRED; hub must reopen for a new live episode.
    persistState(taskId, A2aTaskState.WORKING);

    List<JsonNode> live =
        A2aSseTestSupport.collectSseEventsAfterFirstFrame(
            "POST",
            "/tasks/" + taskId + ":subscribe",
            "{}",
            Duration.ofSeconds(15),
            () ->
                eventHub.publish(
                    taskId,
                    A2aStreamingEventSupport.statusUpdate(
                        taskId, taskId, A2aTaskState.COMPLETED, "done")));

    assertTrue(live.size() >= 2, "expected durable snapshot then live COMPLETED: " + live);
    assertTrue(A2aSseTestSupport.isTaskEvent(live.get(0)));
    assertEquals("TASK_STATE_WORKING", A2aSseTestSupport.eventState(live.get(0)));
    assertEquals(
        "TASK_STATE_COMPLETED",
        A2aSseTestSupport.eventState(live.get(live.size() - 1)));
  }

  @Test
  void reconnectUsesGetTaskAndLatestSnapshotWithoutReplay() throws Exception {
    String taskId = "re-" + UUID.randomUUID();
    persistWorking(taskId);

    eventHub.publish(
        taskId,
        A2aStreamingEventSupport.statusUpdate(
            taskId, taskId, A2aTaskState.WORKING, "transient"));

    persistState(taskId, A2aTaskState.WORKING);
    // Mutate durable snapshot while no subscriber is attached.
    persistState(taskId, A2aTaskState.INPUT_REQUIRED);

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get(URI.create("/tasks/" + taskId))
        .then()
        .statusCode(200)
        .body("status.state", equalTo("TASK_STATE_INPUT_REQUIRED"));

    List<JsonNode> events =
        A2aSseTestSupport.collectSseEvents(
            "POST",
            "/tasks/" + taskId + ":subscribe",
            "{}",
            Duration.ofSeconds(10));
    assertFalse(events.isEmpty());
    assertTrue(A2aSseTestSupport.isTaskEvent(events.get(0)));
    assertEquals(
        "TASK_STATE_INPUT_REQUIRED", A2aSseTestSupport.eventState(events.get(0)));
    // No guarantee that the earlier transient WORKING status is replayed.
    assertEquals(1, events.size());
  }

  @Test
  void multipleHttpSubscribersSeeOrderedRevisions() throws Exception {
    String taskId = "multi-" + UUID.randomUUID();
    persistWorking(taskId);

    AssertSubscriber<StreamingEventKind> one =
        eventHub.subscribe(taskId).subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<StreamingEventKind> two =
        eventHub.subscribe(taskId).subscribe().withSubscriber(AssertSubscriber.create(10));

    eventHub.publish(
        taskId,
        A2aStreamingEventSupport.statusUpdate(
            taskId, taskId, A2aTaskState.WORKING, "w1"));
    one.cancel();
    eventHub.publish(
        taskId,
        A2aStreamingEventSupport.statusUpdate(
            taskId, taskId, A2aTaskState.COMPLETED, "done"));

    two.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(2, two.getItems().size());
    assertTrue(taskRepository.findByTaskId(taskId).isPresent());
  }

  @Test
  void persistBeforePublishIsVisibleInRepository() throws Exception {
    String taskId = "pbp-" + UUID.randomUUID();
    CountDownLatch subscribed = new CountDownLatch(1);
    AtomicBoolean sawBeforeReadable = new AtomicBoolean(false);
    AtomicReference<A2aTaskState> observed = new AtomicReference<>();

    Thread subscriber =
        new Thread(
            () -> {
              AssertSubscriber<StreamingEventKind> sub =
                  eventHub.subscribe(taskId).subscribe().withSubscriber(AssertSubscriber.create(5));
              subscribed.countDown();
              sub.awaitNextItems(1, Duration.ofSeconds(5));
              TaskStatusUpdateEvent event = (TaskStatusUpdateEvent) sub.getItems().get(0);
              observed.set(
                  switch (event.status().state()) {
                    case TASK_STATE_WORKING -> A2aTaskState.WORKING;
                    case TASK_STATE_INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
                    case TASK_STATE_COMPLETED -> A2aTaskState.COMPLETED;
                    case TASK_STATE_FAILED -> A2aTaskState.FAILED;
                    default -> A2aTaskState.WORKING;
                  });
              Optional<A2aPersistedTask> row = taskRepository.findByTaskId(taskId);
              if (row.isEmpty() || row.get().state() != observed.get()) {
                sawBeforeReadable.set(true);
              }
            });
    subscriber.start();
    assertTrue(subscribed.await(2, TimeUnit.SECONDS));

    persistState(taskId, A2aTaskState.INPUT_REQUIRED);
    subscriber.join(5_000);
    assertFalse(sawBeforeReadable.get(), "subscriber must not observe a revision before JDBC");
    assertEquals(A2aTaskState.INPUT_REQUIRED, observed.get());
  }

  @Test
  void unknownAndTerminalSubscribeAreRejected() throws Exception {
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(URI.create("/tasks/missing-" + UUID.randomUUID() + ":subscribe"))
        .then()
        .statusCode(404);

    String taskId = "term-" + UUID.randomUUID();
    persistState(taskId, A2aTaskState.COMPLETED);
    List<JsonNode> events =
        A2aSseTestSupport.collectSseEvents(
            "POST", "/tasks/" + taskId + ":subscribe", "{}", Duration.ofSeconds(10));
    assertFalse(events.isEmpty());
    String raw = events.get(0).toString();
    assertTrue(
        raw.contains("UNSUPPORTED_OPERATION") || raw.contains("terminal"),
        "terminal subscribe must surface UnsupportedOperation: " + raw);
  }

  @Test
  void transportDisconnectDoesNotCancelPipeline() throws Exception {
    String taskId =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .contentType(ContentType.JSON)
            .body(
                A2aSseTestSupport.textMessageBody(
                    UUID.randomUUID().toString(), null, "disconnect me"))
            .when()
            .post(URI.create("/message:send"))
            .then()
            .statusCode(200)
            .extract()
            .path("task.id");

    A2aPersistedTask before = taskRepository.findByTaskId(taskId).orElseThrow();
    // Canceling a subscribe to an already-closed INPUT_REQUIRED task must not mutate state.
    A2aSseTestSupport.collectSseEvents(
        "POST", "/tasks/" + taskId + ":subscribe", "{}", Duration.ofSeconds(10));
    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(before.state(), after.state());
    assertEquals(before.revision(), after.revision());
  }

  @Test
  void terminalDuringReconcileReadIsNotFilteredOut() throws Exception {
    String taskId = "reconcile-race-" + UUID.randomUUID();
    persistWorking(taskId);

    requestHandler.setAfterReconcileReadHook(
        () -> {
          persistState(taskId, A2aTaskState.COMPLETED);
          eventHub.publish(
              taskId,
              A2aStreamingEventSupport.statusUpdate(
                  taskId, taskId, A2aTaskState.COMPLETED, "done"));
        });

    List<JsonNode> events =
        A2aSseTestSupport.collectSseEvents(
            "POST",
            "/tasks/" + taskId + ":subscribe",
            "{}",
            Duration.ofSeconds(10));
    assertFalse(events.isEmpty());
    assertEquals(
        "TASK_STATE_COMPLETED",
        A2aSseTestSupport.eventState(events.get(events.size() - 1)));
  }

  @Test
  void subscribePerformsExactlyOneDurableRead() throws Exception {
    String taskId = "one-read-" + UUID.randomUUID();
    persistWorking(taskId);
    persister.resetLoadDurableCallCountForTest();

    AssertSubscriber<StreamingEventKind> subscriber =
        Multi.createFrom()
            .publisher(
                requestHandler.onSubscribeToTask(
                    new org.a2aproject.sdk.spec.TaskIdParams(taskId),
                    mockServerCallContext()))
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));

    eventHub.publish(
        taskId,
        A2aStreamingEventSupport.statusUpdate(
            taskId, taskId, A2aTaskState.COMPLETED, "done"));
    subscriber.awaitCompletion(Duration.ofSeconds(5));
    assertEquals(1, persister.loadDurableCallCountForTest());
  }

  @Test
  void registerThenReadSeesWorkingPublishedBeforeRead() throws Exception {
    String taskId = "reg-then-read-" + UUID.randomUUID();
    persistWorking(taskId);
    persister.resetLoadDurableCallCountForTest();
    AtomicBoolean published = new AtomicBoolean();
    requestHandler.setAfterRegisterHook(
        () -> {
          persistState(taskId, A2aTaskState.WORKING);
          eventHub.publish(
              taskId,
              A2aStreamingEventSupport.statusUpdate(
                  taskId, taskId, A2aTaskState.WORKING, "live"),
              taskRepository.findByTaskId(taskId).orElseThrow().revision());
          published.set(true);
        });

    AssertSubscriber<StreamingEventKind> subscriber =
        Multi.createFrom()
            .publisher(
                requestHandler.onSubscribeToTask(
                    new org.a2aproject.sdk.spec.TaskIdParams(taskId),
                    mockServerCallContext()))
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));

    assertTrue(published.get());
    assertEquals(1, persister.loadDurableCallCountForTest());
    assertFalse(subscriber.getItems().isEmpty());
    subscriber.cancel();
  }

  private org.a2aproject.sdk.server.ServerCallContext mockServerCallContext() {
    return org.mockito.Mockito.mock(org.a2aproject.sdk.server.ServerCallContext.class);
  }

  private void persistWorking(String taskId) {
    persistState(taskId, A2aTaskState.WORKING);
  }

  private void persistState(String taskId, A2aTaskState state) {
    ProjectedTask projected =
        new ProjectedTask(
            taskId,
            state,
            new CreateChainExecutionSnapshot(
                taskId,
                "run-" + taskId,
                switch (state) {
                  case WORKING -> CreateChainExecutionStatus.WORKING;
                  case INPUT_REQUIRED -> CreateChainExecutionStatus.INPUT_REQUIRED;
                  case COMPLETED -> CreateChainExecutionStatus.COMPLETED;
                  case FAILED -> CreateChainExecutionStatus.FAILED;
                  default -> CreateChainExecutionStatus.WORKING;
                },
                1L,
                state == A2aTaskState.INPUT_REQUIRED
                    ? new CreateChainPendingAction.Clarify("need more", List.of())
                    : null,
                ""),
            state == A2aTaskState.INPUT_REQUIRED
                ? new CreateChainPendingAction.Clarify("need more", List.of())
                : null,
            state == A2aTaskState.INPUT_REQUIRED
                ? java.util.Map.of("action", "clarify")
                : java.util.Map.of(),
            state.name(),
            List.of());
    try {
      persister.persistAndBuildSdkTask(
          taskId, taskId, new CallerContext("local", "local-user"), projected, List.of());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
