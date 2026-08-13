package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries a chain patch across the two turns it takes to apply one.
 *
 * <p>A capture is what the model submitted this turn; a proposal is what the reader was shown and
 * may answer next turn. Both are keyed by conversation, not by thread: a LangChain4j tool callback
 * runs on a different worker than the turn that invoked it.
 */
@ApplicationScoped
public class ChainPatchStore {

  private final ConcurrentHashMap<String, ChainPatchCapture> captures = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ProposedChainPatch> proposals = new ConcurrentHashMap<>();

  public void putCapture(String conversationId, ChainPatchCapture capture) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(capture, "capture");
    captures.put(conversationId, capture);
  }

  /** Reads the capture and clears it, so a turn that captures nothing cannot reuse an old one. */
  public Optional<ChainPatchCapture> takeCapture(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return Optional.ofNullable(captures.remove(conversationId));
  }

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
