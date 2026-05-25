package org.qubership.integration.platform.ai.chat.chainplan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** In-memory conversation-scoped chain plan state and archive. */
@ApplicationScoped
class ConversationPlanStateStore {

  static final class ConversationPlanState {
    ActiveChainPlanSnapshot active;
    boolean approved;
    boolean implementGatePending;
    boolean implementGateAcknowledged;
    /** Set when user chose Agree in plan-approval HITL before stream capture completes. */
    boolean pendingHitlPlanApproval;
    /** Raw JSON from the last successful capture; skips redundant archive churn while streaming. */
    String lastCapturedPlanJson;
    String lastMergedOpenDebtFingerprint;
    final Deque<ActiveChainPlanSnapshot> archive = new ArrayDeque<>();
  }

  private final ConcurrentHashMap<String, ConversationPlanState> states = new ConcurrentHashMap<>();
  private final AppConfig appConfig;

  @Inject
  ConversationPlanStateStore(AppConfig appConfig) {
    this.appConfig = appConfig;
  }

  Optional<ConversationPlanState> get(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(states.get(conversationId));
  }

  ConversationPlanState compute(
      String conversationId,
      BiFunction<String, ConversationPlanState, ConversationPlanState> remappingFunction) {
    return states.compute(conversationId, remappingFunction);
  }

  ConversationPlanState computeIfPresent(
      String conversationId,
      BiFunction<String, ConversationPlanState, ConversationPlanState> remappingFunction) {
    return states.computeIfPresent(conversationId, remappingFunction);
  }

  void archiveActive(ConversationPlanState state) {
    state.approved = false;
    state.pendingHitlPlanApproval = false;
    state.lastMergedOpenDebtFingerprint = null;
    state.lastCapturedPlanJson = null;
    if (state.active != null) {
      pushArchive(state, state.active);
      state.active = null;
    }
  }

  void pushArchive(ConversationPlanState state, ActiveChainPlanSnapshot snapshot) {
    state.archive.addFirst(snapshot);
    int max = appConfig.chainPlan().archiveMax();
    while (state.archive.size() > max) {
      state.archive.removeLast();
    }
  }

  Optional<ActiveChainPlanSnapshot> peekLatestArchiveForTest(String conversationId) {
    ConversationPlanState state = states.get(conversationId);
    if (state == null || state.archive.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(state.archive.peekFirst());
  }

  ConversationPlanState newState() {
    return new ConversationPlanState();
  }
}
