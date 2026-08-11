package org.qubership.integration.platform.ai.plan;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Generic in-memory per-conversation value store. */
final class ConversationKeyedStore<T> {

  private final ConcurrentHashMap<String, T> store = new ConcurrentHashMap<>();

  void put(String conversationId, T value) {
    store.put(conversationId, value);
  }

  Optional<T> get(String conversationId) {
    return Optional.ofNullable(store.get(conversationId));
  }

  void remove(String conversationId) {
    store.remove(conversationId);
  }
}
