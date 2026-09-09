package org.qubership.integration.platform.ai.chat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Process-local last assistant turn per conversation and open chain. */
@ApplicationScoped
public class LastAssistantTurnStore {

  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(1);

  private final Cache<String, LastAssistantTurn> turns;

  public LastAssistantTurnStore() {
    this(DEFAULT_IDLE_TIMEOUT, Ticker.systemTicker());
  }

  @Inject
  public LastAssistantTurnStore(AppConfig appConfig) {
    this(appConfig.conversation().idleTimeout(), Ticker.systemTicker());
  }

  LastAssistantTurnStore(Duration idleTimeout, Ticker ticker) {
    this.turns =
        Caffeine.newBuilder().expireAfterAccess(idleTimeout).ticker(ticker).build();
  }

  public void put(String conversationId, String chainId, LastAssistantTurn turn) {
    turns.put(key(conversationId, chainId), turn);
  }

  public Optional<LastAssistantTurn> find(String conversationId, String chainId) {
    return Optional.ofNullable(turns.getIfPresent(key(conversationId, chainId)));
  }

  public void clearConversation(String conversationId) {
    String prefix = conversationId + '\0';
    turns.asMap().keySet().removeIf(key -> key.startsWith(prefix));
  }

  void cleanUp() {
    turns.cleanUp();
  }

  private static String key(String conversationId, String chainId) {
    return conversationId + '\0' + chainId;
  }
}
