package org.qubership.integration.platform.ai.chat.evidence;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory store of per-conversation evidence accumulators. */
@ApplicationScoped
public class ConversationEvidenceStore {

  private final ConcurrentHashMap<String, ConversationEvidenceAccumulator> accumulators =
      new ConcurrentHashMap<>();

  public Optional<ConversationEvidenceAccumulator> find(String conversationId) {
    if (conversationId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(accumulators.get(conversationId));
  }

  public ConversationEvidenceAccumulator getOrCreate(String conversationId) {
    if (conversationId == null) {
      throw new IllegalArgumentException("conversationId is required");
    }
    return accumulators.computeIfAbsent(conversationId, ignored -> new ConversationEvidenceAccumulator());
  }

  public void clear(String conversationId) {
    if (conversationId != null) {
      accumulators.remove(conversationId);
    }
  }
}
