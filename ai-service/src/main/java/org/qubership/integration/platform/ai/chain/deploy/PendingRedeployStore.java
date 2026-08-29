package org.qubership.integration.platform.ai.chain.deploy;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries a pending replacement across the two turns it takes to confirm one.
 *
 * <p>Keyed by conversation, not by thread: the turn that answers a card is not the one that offered
 * it.
 */
@ApplicationScoped
public class PendingRedeployStore {

  private final ConcurrentHashMap<String, PendingRedeploy> pending = new ConcurrentHashMap<>();

  public void put(String conversationId, PendingRedeploy redeploy) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(redeploy, "redeploy");
    pending.put(conversationId, redeploy);
  }

  public Optional<PendingRedeploy> find(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return Optional.ofNullable(pending.get(conversationId));
  }

  /** Clears the pending replace once it is answered, so an answered card cannot be replayed. */
  public void clear(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    pending.remove(conversationId);
  }
}
