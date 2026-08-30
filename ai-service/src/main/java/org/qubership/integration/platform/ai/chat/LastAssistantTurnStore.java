package org.qubership.integration.platform.ai.chat;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local last assistant turn per conversation and open chain. */
@ApplicationScoped
public class LastAssistantTurnStore {

  private final ConcurrentHashMap<String, LastAssistantTurn> turns = new ConcurrentHashMap<>();

  public void put(String conversationId, String chainId, LastAssistantTurn turn) {
    turns.put(key(conversationId, chainId), turn);
  }

  public Optional<LastAssistantTurn> find(String conversationId, String chainId) {
    return Optional.ofNullable(turns.get(key(conversationId, chainId)));
  }

  public void clearConversation(String conversationId) {
    String prefix = conversationId + '\0';
    turns.keySet().removeIf(key -> key.startsWith(prefix));
  }

  private static String key(String conversationId, String chainId) {
    return conversationId + '\0' + chainId;
  }
}
