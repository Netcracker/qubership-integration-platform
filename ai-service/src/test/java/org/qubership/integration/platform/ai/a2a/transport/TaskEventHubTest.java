package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

class TaskEventHubTest {

  @Test
  void deliversEventsInPerTaskOrderAndClosesOnInputRequired() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> subscriber =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));

    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.INPUT_REQUIRED));

    subscriber.awaitCompletion(Duration.ofSeconds(2));
    List<StreamingEventKind> items = subscriber.getItems();
    assertEquals(2, items.size());
    assertEquals(
        A2aTaskState.WORKING.toSdk(),
        ((TaskStatusUpdateEvent) items.get(0)).status().state());
    assertEquals(
        A2aTaskState.INPUT_REQUIRED.toSdk(),
        ((TaskStatusUpdateEvent) items.get(1)).status().state());
  }

  @Test
  void isolatesOrderingPerTask() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> a =
        hub.subscribe("a").subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<StreamingEventKind> b =
        hub.subscribe("b").subscribe().withSubscriber(AssertSubscriber.create(10));

    hub.publish("b", status("b", "ctx", A2aTaskState.WORKING));
    hub.publish("a", status("a", "ctx", A2aTaskState.WORKING));
    hub.publish("a", status("a", "ctx", A2aTaskState.COMPLETED));
    hub.publish("b", status("b", "ctx", A2aTaskState.FAILED));

    a.awaitCompletion(Duration.ofSeconds(2));
    b.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(
        A2aTaskState.COMPLETED.toSdk(),
        ((TaskStatusUpdateEvent) a.getItems().get(1)).status().state());
    assertEquals(
        A2aTaskState.FAILED.toSdk(),
        ((TaskStatusUpdateEvent) b.getItems().get(1)).status().state());
  }

  @Test
  void multipleSubscribersReceiveSameOrderedRevisions() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> one =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<StreamingEventKind> two =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));

    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.COMPLETED));

    one.awaitCompletion(Duration.ofSeconds(2));
    two.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(2, one.getItems().size());
    assertEquals(2, two.getItems().size());
    assertEquals(
        ((TaskStatusUpdateEvent) one.getItems().get(1)).status().state(),
        ((TaskStatusUpdateEvent) two.getItems().get(1)).status().state());
  }

  @Test
  void cancelingOneSubscriberLeavesOthersActive() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> one =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<StreamingEventKind> two =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));

    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    one.cancel();

    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.COMPLETED));
    two.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(2, two.getItems().size());
  }

  @Test
  void overflowFailsOnlySlowSubscriber() {
    TaskEventHub hub = new TaskEventHub(1);
    AssertSubscriber<StreamingEventKind> slow =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(0));
    AssertSubscriber<StreamingEventKind> fast =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));

    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.COMPLETED));

    fast.awaitCompletion(Duration.ofSeconds(2));
    assertTrue(fast.getItems().size() >= 2);
    slow.awaitFailure(Duration.ofSeconds(2));
    assertInstanceOf(IllegalStateException.class, slow.getFailure());
    assertTrue(slow.getFailure().getMessage().contains("overflow"));
  }

  @Test
  void closedEpisodeSubscribersDoNotReceiveLaterEpisodeEvents() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> subscriber =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.FAILED));
    subscriber.awaitCompletion(Duration.ofSeconds(2));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    assertEquals(1, subscriber.getItems().size());
  }

  @Test
  void reopensChannelAfterInputRequiredForNewSubscribeAndPublish() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> first =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.INPUT_REQUIRED));
    first.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(2, first.getItems().size());

    AssertSubscriber<StreamingEventKind> second =
        hub.subscribe("task-1").subscribe().withSubscriber(AssertSubscriber.create(10));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.WORKING));
    hub.publish("task-1", status("task-1", "ctx", A2aTaskState.COMPLETED));
    second.awaitCompletion(Duration.ofSeconds(2));

    List<StreamingEventKind> items = second.getItems();
    assertEquals(2, items.size());
    assertEquals(
        A2aTaskState.WORKING.toSdk(),
        ((TaskStatusUpdateEvent) items.get(0)).status().state());
    assertEquals(
        A2aTaskState.COMPLETED.toSdk(),
        ((TaskStatusUpdateEvent) items.get(1)).status().state());
  }

  @Test
  void rejectsNullTaskId() {
    TaskEventHub hub = new TaskEventHub(8);
    assertThrows(NullPointerException.class, () -> hub.subscribe(null));
    assertThrows(
        NullPointerException.class,
        () -> hub.publish(null, status("t", "c", A2aTaskState.WORKING)));
  }

  @Test
  void streamCloseStatesAreDetected() {
    assertTrue(TaskEventHub.closesStream(A2aTaskState.INPUT_REQUIRED));
    assertTrue(TaskEventHub.closesStream(A2aTaskState.COMPLETED));
    assertTrue(TaskEventHub.closesStream(A2aTaskState.FAILED));
    assertTrue(!TaskEventHub.closesStream(A2aTaskState.WORKING));
  }

  @Test
  void taskSnapshotIsStreamingEventKind() {
    Task task =
        A2aStreamingEventSupport.initialTask("t", "c", A2aTaskState.WORKING, "Working");
    assertInstanceOf(StreamingEventKind.class, task);
    assertEquals(A2aTaskState.WORKING.toSdk(), task.status().state());
  }

  @Test
  void eagerRegistrationBuffersTransitionBeforeDownstreamSubscribe() {
    TaskEventHub hub = new TaskEventHub(8);
    hub.setAfterRegisterHook(
        () -> hub.publish("race", status("race", "ctx", A2aTaskState.INPUT_REQUIRED)));

    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("race");
    // Baseline must be captured at open time (before reconcile), not after a later durable read.
    long baselineRevision = 0L;
    AssertSubscriber<StreamingEventKind> subscriber =
        handle
            .liveAfter(baselineRevision)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));

    subscriber.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(1, subscriber.getItems().size());
    assertEquals(
        A2aTaskState.INPUT_REQUIRED.toSdk(),
        ((TaskStatusUpdateEvent) subscriber.getItems().get(0)).status().state());
  }

  @Test
  void filteringAgainstPostReconcileRevisionDropsTerminal() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("drop");
    long baselineAtOpen = handle.currentRevision();
    hub.publish("drop", status("drop", "ctx", A2aTaskState.COMPLETED));
    long afterPublish = handle.currentRevision();
    assertTrue(afterPublish > baselineAtOpen);

    AssertSubscriber<StreamingEventKind> dropped =
        handle
            .liveAfter(afterPublish)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    dropped.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(0, dropped.getItems().size(), "post-reconcile revision filter hides terminal");

    TaskEventHub hub2 = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle2 = hub2.openSubscription("keep");
    long baseline2 = handle2.currentRevision();
    hub2.publish("keep", status("keep", "ctx", A2aTaskState.COMPLETED));
    AssertSubscriber<StreamingEventKind> kept =
        handle2
            .liveAfter(baseline2)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    kept.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(1, kept.getItems().size());
  }

  @Test
  void equalStatusEventsKeepOwnRevisionsAcrossReconcile() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("eq-status");
    TaskStatusUpdateEvent sameShape = status("eq-status", "ctx", A2aTaskState.WORKING);
    hub.publish("eq-status", sameShape, 1L);
    hub.publish("eq-status", sameShape, 3L);

    AssertSubscriber<StreamingEventKind> subscriber =
        handle.liveAfter(2L).subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, subscriber.getItems().size());
    assertEquals(
        A2aTaskState.WORKING.toSdk(),
        ((TaskStatusUpdateEvent) subscriber.getItems().get(0)).status().state());
  }

  @Test
  void equalArtifactEventsKeepOwnRevisionsAcrossReconcile() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("eq-art");
    var artifact =
        A2aStreamingEventSupport.artifactUpdate(
            "eq-art",
            "ctx",
            "art-1",
            "plan",
            new ObjectMapper().valueToTree(Map.of("hash", "abc")));
    hub.publish("eq-art", artifact, 1L);
    hub.publish("eq-art", artifact, 4L);

    AssertSubscriber<StreamingEventKind> subscriber =
        handle.liveAfter(2L).subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, subscriber.getItems().size());
  }

  @Test
  void envelopeAtSnapshotRevisionIsSuppressedOnce() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("snap-eq");
    TaskStatusUpdateEvent same = status("snap-eq", "ctx", A2aTaskState.WORKING);
    hub.publish("snap-eq", same, 5L);
    hub.publish("snap-eq", same, 6L);

    AssertSubscriber<StreamingEventKind> subscriber =
        handle.liveAfter(5L).subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, subscriber.getItems().size());
  }

  @Test
  void retainedStateStaysBoundedWhileDownstreamKeepsUp() {
    TaskEventHub hub = new TaskEventHub(4);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("bound");
    AssertSubscriber<StreamingEventKind> subscriber =
        handle.liveAfter(0L).subscribe().withSubscriber(AssertSubscriber.create(100));
    for (int i = 1; i <= 20; i++) {
      hub.publish("bound", status("bound", "ctx", A2aTaskState.WORKING), i);
    }
    assertTrue(subscriber.getItems().size() >= 20);
    assertTrue(
        handle.retainedEnvelopeCountForTest() <= 4,
        "retained envelopes must stay within buffer capacity");
  }

  @Test
  void twoSubscribersDeliverIndependentlyWithoutRevisionCrossTalk() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle one = hub.openSubscription("multi");
    TaskEventHub.SubscriptionHandle two = hub.openSubscription("multi");
    TaskStatusUpdateEvent same = status("multi", "ctx", A2aTaskState.WORKING);
    hub.publish("multi", same, 1L);
    hub.publish("multi", same, 2L);

    AssertSubscriber<StreamingEventKind> a =
        one.liveAfter(1L).subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<StreamingEventKind> b =
        two.liveAfter(0L).subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, a.getItems().size());
    assertEquals(2, b.getItems().size());
  }

  @Test
  void cancelClearsRetainedEnvelopes() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("cancel-clear");
    hub.publish("cancel-clear", status("cancel-clear", "ctx", A2aTaskState.WORKING), 1L);
    assertTrue(handle.retainedEnvelopeCountForTest() >= 1);
    handle.close();
    assertEquals(0, handle.retainedEnvelopeCountForTest());
  }

  @Test
  void sharedRevisionFramesPreservePublicationOrder() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("shared-rev");
    hub.publish("shared-rev", status("shared-rev", "ctx", A2aTaskState.WORKING), 7L);
    hub.publish(
        "shared-rev",
        A2aStreamingEventSupport.artifactUpdate(
            "shared-rev", "ctx", "a1", "plan", new ObjectMapper().valueToTree(Map.of("k", "v"))),
        7L);
    AssertSubscriber<StreamingEventKind> subscriber =
        handle.liveAfter(0L).subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(2, subscriber.getItems().size());
    assertInstanceOf(TaskStatusUpdateEvent.class, subscriber.getItems().get(0));
  }

  private static TaskStatusUpdateEvent status(String taskId, String contextId, A2aTaskState state) {
    return A2aStreamingEventSupport.statusUpdate(taskId, contextId, state, state.name());
  }
}
