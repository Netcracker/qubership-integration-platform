package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

/**
 * Holds an edit that stopped to ask whether a specification may be imported.
 *
 * <p>What is held is the resolved intent, not the reader's words. By the time an import is
 * approved, the conversation has moved on, and re-reading the original sentence would resolve a
 * target again with a model that may answer differently. The edit that resumes is the one the
 * reader was shown.
 */
@ApplicationScoped
public class ChainEditEscalationStore {

  private final ConcurrentHashMap<String, PendingChainEdit> pending = new ConcurrentHashMap<>();

  public void put(String conversationId, PendingChainEdit edit) {
    pending.put(conversationId, edit);
  }

  /** Reads and clears: an escalation can be answered once. */
  public Optional<PendingChainEdit> take(String conversationId) {
    return Optional.ofNullable(pending.remove(conversationId));
  }

  public void clear(String conversationId) {
    pending.remove(conversationId);
  }

  /** One edit waiting on an import decision. */
  public record PendingChainEdit(
      String chainId,
      String userRequest,
      ChainEditIntent intent,
      ApiHubRequirementRefs refs,
      String candidateId) {}
}
