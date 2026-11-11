package org.qubership.integration.platform.ai.chat.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PinnedFailureStoreTest {

  private final PinnedFailureStore store = new PinnedFailureStore();

  @Test
  void pinIsKeyedByConversationAndChain() {
    store.put(
        new PinnedFailure(
            "c1", "chain-a", "Couldn't finish this catalog request.", "TimeoutException"));
    assertTrue(store.find("c1", "chain-b").isEmpty());
    assertEquals(
        "Couldn't finish this catalog request.",
        store.find("c1", "chain-a").orElseThrow().safeText());
  }

  @Test
  void putOverwritesPinForSameConversationAndChain() {
    store.put(new PinnedFailure("c1", "chain-a", "first", "TimeoutException"));
    store.put(new PinnedFailure("c1", "chain-a", "second", "TimeoutException"));
    assertEquals("second", store.find("c1", "chain-a").orElseThrow().safeText());
  }

  @Test
  void clearRemovesOnlyThatChainPin() {
    store.put(new PinnedFailure("c1", "chain-a", "text-a", "TimeoutException"));
    store.put(new PinnedFailure("c1", "chain-b", "text-b", "TimeoutException"));
    store.clear("c1", "chain-a");
    assertTrue(store.find("c1", "chain-a").isEmpty());
    assertEquals("text-b", store.find("c1", "chain-b").orElseThrow().safeText());
  }

  @Test
  void clearConversationRemovesEveryPinForThatConversation() {
    store.put(new PinnedFailure("c1", "chain-a", "text-a", "TimeoutException"));
    store.put(new PinnedFailure("c1", "chain-b", "text-b", "TimeoutException"));
    store.put(new PinnedFailure("c2", "chain-a", "text-c", "TimeoutException"));
    store.clearConversation("c1");
    assertTrue(store.find("c1", "chain-a").isEmpty());
    assertTrue(store.find("c1", "chain-b").isEmpty());
    assertEquals("text-c", store.find("c2", "chain-a").orElseThrow().safeText());
  }

  @Test
  void dropPinsMissingFromRemovesPinsWhoseSafeTextIsGone() {
    store.put(new PinnedFailure("c1", "chain-a", "keep-me", "TimeoutException"));
    store.put(new PinnedFailure("c1", "chain-b", "drop-me", "TimeoutException"));
    store.put(new PinnedFailure("c2", "chain-a", "other-conv", "TimeoutException"));
    store.dropPinsMissingFrom("c1", List.of("keep-me", "unrelated"));
    assertEquals("keep-me", store.find("c1", "chain-a").orElseThrow().safeText());
    assertTrue(store.find("c1", "chain-b").isEmpty());
    assertEquals("other-conv", store.find("c2", "chain-a").orElseThrow().safeText());
  }
}
