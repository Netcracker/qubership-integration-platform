package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds an edit that stopped to ask which element or aspect the reader meant.
 *
 * <p>What is held is the partly resolved intent and the question the reader was shown, not the
 * reader's original words. The next turn feeds both back to the classifier alongside whatever the
 * reader says next, so answering the question continues this same edit instead of resolving the
 * reply with no record of having asked. This mirrors {@link ChainEditEscalationStore}, which holds
 * an edit the same way while it waits on an import decision.
 */
@ApplicationScoped
public class ChainEditClarificationStore {

  private final ConcurrentHashMap<String, PendingClarification> pending = new ConcurrentHashMap<>();

  public void put(String conversationId, PendingClarification clarification) {
    pending.put(conversationId, clarification);
  }

  /** Reads and clears: a clarification can be answered once. */
  public Optional<PendingClarification> take(String conversationId) {
    return Optional.ofNullable(pending.remove(conversationId));
  }

  public void clear(String conversationId) {
    pending.remove(conversationId);
  }

  /** One edit waiting on an answer to a clarifying question. */
  public record PendingClarification(String chainId, ChainEditIntent heldIntent, String question) {}
}
