package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Every subscriber-slot close path removes the slot from its channel, so a finished or failed
 * subscriber is never visited by a later publication.
 */
class TaskEventHubSlotLifecycleTest {

  @Test
  void overflowRemovesSlot() {
    TaskEventHub hub = new TaskEventHub(2);
    AssertSubscriber<StreamingEventKind> subscriber =
        hub.subscribe("task-overflow").subscribe().withSubscriber(AssertSubscriber.create(0));
    assertEquals(1, hub.subscriberCountForTest("task-overflow"));

    for (int i = 0; i < 5; i++) {
      hub.publish("task-overflow", status("task-overflow", A2aTaskState.WORKING));
    }

    subscriber.awaitFailure(Duration.ofSeconds(2));
    assertEquals(
        0, hub.subscriberCountForTest("task-overflow"), "overflowed slot must leave the channel");
  }

  @Test
  void terminalCompletionRemovesSlot() {
    TaskEventHub hub = new TaskEventHub(8);
    AssertSubscriber<StreamingEventKind> subscriber =
        hub.subscribe("task-terminal").subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, hub.subscriberCountForTest("task-terminal"));

    hub.publish("task-terminal", status("task-terminal", A2aTaskState.WORKING));
    hub.publish("task-terminal", status("task-terminal", A2aTaskState.COMPLETED));

    subscriber.awaitCompletion(Duration.ofSeconds(2));
    assertEquals(
        0,
        hub.subscriberCountForTest("task-terminal"),
        "completed slot must leave the channel");
  }

  @Test
  void cancellationRemovesSlot() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("task-cancel");
    handle.live().subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, hub.subscriberCountForTest("task-cancel"));

    handle.close();

    assertEquals(
        0, hub.subscriberCountForTest("task-cancel"), "closed handle must leave the channel");
  }

  @Test
  void closeIsIdempotent() {
    TaskEventHub hub = new TaskEventHub(8);
    TaskEventHub.SubscriptionHandle handle = hub.openSubscription("task-idempotent");
    handle.live().subscribe().withSubscriber(AssertSubscriber.create(10));

    handle.close();
    handle.close();

    assertEquals(0, hub.subscriberCountForTest("task-idempotent"));
  }

  @Test
  void failedSlotIsNotVisitedByLaterPublications() {
    TaskEventHub hub = new TaskEventHub(2);
    AssertSubscriber<StreamingEventKind> failing =
        hub.subscribe("task-mixed").subscribe().withSubscriber(AssertSubscriber.create(0));
    for (int i = 0; i < 5; i++) {
      hub.publish("task-mixed", status("task-mixed", A2aTaskState.WORKING));
    }
    failing.awaitFailure(Duration.ofSeconds(2));

    AssertSubscriber<StreamingEventKind> healthy =
        hub.subscribe("task-mixed").subscribe().withSubscriber(AssertSubscriber.create(10));
    assertEquals(1, hub.subscriberCountForTest("task-mixed"));

    hub.publish("task-mixed", status("task-mixed", A2aTaskState.COMPLETED));

    healthy.awaitCompletion(Duration.ofSeconds(2));
    assertTrue(healthy.getItems().size() >= 1);
    assertEquals(0, hub.subscriberCountForTest("task-mixed"));
  }

  private static TaskStatusUpdateEvent status(String taskId, A2aTaskState state) {
    return A2aStreamingEventSupport.statusUpdate(taskId, "ctx", state, state.name());
  }
}
