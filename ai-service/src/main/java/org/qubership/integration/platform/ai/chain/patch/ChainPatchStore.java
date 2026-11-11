package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries a chain patch across the two turns it takes to apply one.
 *
 * <p>A proposal is what the reader was shown and may answer next turn. Keyed by conversation, not
 * by thread: the turn that answers a card is not the one that offered it.
 */
@ApplicationScoped
public class ChainPatchStore {

  private final ConcurrentHashMap<String, ProposedChainPatch> proposals = new ConcurrentHashMap<>();

  public void putProposal(String conversationId, ProposedChainPatch proposal) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(proposal, "proposal");
    proposals.put(conversationId, proposal);
  }

  public Optional<ProposedChainPatch> findProposal(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return Optional.ofNullable(proposals.get(conversationId));
  }

  /** Clears the proposal once it is answered, so an answered card cannot be replayed. */
  public void clearProposal(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    proposals.remove(conversationId);
  }
}
