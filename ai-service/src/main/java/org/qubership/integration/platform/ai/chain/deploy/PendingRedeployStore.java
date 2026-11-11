package org.qubership.integration.platform.ai.chain.deploy;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;

/**
 * Keeps pending deployment operations and the cards waiting for an answer.
 *
 * <p>Keyed by conversation, not by thread: the turn that answers a card is not the one that offered
 * it.
 */
@ApplicationScoped
public class PendingRedeployStore {

  private record PendingState(PendingRedeploy operation, ChatEvent.Decision decision) {}

  private final ConcurrentHashMap<String, PendingState> pending = new ConcurrentHashMap<>();

  public void put(String conversationId, PendingRedeploy redeploy) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(redeploy, "redeploy");
    pending.put(conversationId, new PendingState(redeploy, null));
  }

  public Optional<PendingRedeploy> find(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return Optional.ofNullable(pending.get(conversationId)).map(PendingState::operation);
  }

  /** Keep the exact offered card so transcript reconciliation can recover it. */
  public void rememberDecision(String conversationId, ChatEvent.Decision decision) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(decision, "decision");
    pending.compute(conversationId, (id, state) ->
        new PendingState(state == null ? null : state.operation(), decision));
  }

  public Optional<ChatEvent.Decision> openDecision(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return Optional.ofNullable(pending.get(conversationId)).map(PendingState::decision);
  }

  /** Consume a status or failure card only when the answer matches its binding and action. */
  public void clearDecision(String conversationId, ChatDecisionCommand answer) {
    pending.computeIfPresent(conversationId, (id, state) -> {
      ChatEvent.Decision decision = state.decision();
      if (answer == null || decision == null
          || !Objects.equals(decision.artifactType(), answer.getArtifactType())
          || !Objects.equals(decision.artifactHash(), answer.getArtifactHash())
          || !decision.actions().contains(answer.getAction())) {
        return state;
      }
      return state.operation() == null ? null : new PendingState(state.operation(), null);
    });
  }

  /** Clears the pending replace once it is answered, so an answered card cannot be replayed. */
  public void clear(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    pending.remove(conversationId);
  }
}
