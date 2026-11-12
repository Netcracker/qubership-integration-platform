package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

/**
 * Holds an edit that stopped to ask whether a specification may be imported.
 *
 * <p>The resolved intent fixes the target when the edit resumes. The author source preserves
 * any Script body supplied before the import decision.
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
      String candidateId,
      StructuralBindingContinuation continuation) {
    public PendingChainEdit(
        String chainId,
        String userRequest,
        ChainEditIntent intent,
        ApiHubRequirementRefs refs,
        String candidateId) {
      this(chainId, userRequest, intent, refs, candidateId, null);
    }
  }
}
