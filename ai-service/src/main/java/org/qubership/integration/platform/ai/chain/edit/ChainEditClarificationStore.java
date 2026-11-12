package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds an edit that stopped to ask which element or aspect the reader meant.
 *
 * <p>The intent and question provide context for classifying the next reply. The author source
 * preserves supplied Script bodies across target clarification.
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
  public record PendingClarification(
      String chainId,
      ChainEditIntent heldIntent,
      String question,
      StructuralBindingContinuation continuation,
      String scriptAuthorSource) {
    public PendingClarification(String chainId, ChainEditIntent heldIntent, String question,
        StructuralBindingContinuation continuation) {
      this(chainId, heldIntent, question, continuation, "");
    }

    public PendingClarification(String chainId, ChainEditIntent heldIntent, String question) {
      this(chainId, heldIntent, question, null);
    }
  }
}
