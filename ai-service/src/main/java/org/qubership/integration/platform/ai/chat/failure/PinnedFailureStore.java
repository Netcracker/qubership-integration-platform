package org.qubership.integration.platform.ai.chat.failure;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local pin of the last known catalog failure per conversation and chain. */
@ApplicationScoped
public class PinnedFailureStore {

  private final ConcurrentHashMap<String, PinnedFailure> pins = new ConcurrentHashMap<>();

  public void put(PinnedFailure failure) {
    pins.put(key(failure.conversationId(), failure.chainId()), failure);
  }

  public Optional<PinnedFailure> find(String conversationId, String chainId) {
    return Optional.ofNullable(pins.get(key(conversationId, chainId)));
  }

  public void clear(String conversationId, String chainId) {
    pins.remove(key(conversationId, chainId));
  }

  public void clearConversation(String conversationId) {
    String prefix = conversationPrefix(conversationId);
    pins.keySet().removeIf(key -> key.startsWith(prefix));
  }

  public void dropPinsMissingFrom(String conversationId, Collection<String> remainingContents) {
    String prefix = conversationPrefix(conversationId);
    pins.entrySet()
        .removeIf(
            entry ->
                entry.getKey().startsWith(prefix)
                    && !remainingContents.contains(entry.getValue().safeText()));
  }

  private static String key(String conversationId, String chainId) {
    return conversationId + '\0' + chainId;
  }

  private static String conversationPrefix(String conversationId) {
    return conversationId + '\0';
  }
}
