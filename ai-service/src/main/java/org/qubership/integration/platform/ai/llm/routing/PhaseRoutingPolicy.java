package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;

/** Deterministic routing shortcuts from {@link ConversationPhase} before LLM classification. */
public final class PhaseRoutingPolicy {

  private PhaseRoutingPolicy() {}

  public static Optional<ScenarioType> tryResolve(
      ConversationPhase phase,
      String userMessage,
      boolean hasActivePlan,
      boolean hasCurrentBundle,
      boolean hasChainContext) {
    return tryResolve(
        phase, userMessage, hasActivePlan, hasCurrentBundle, hasChainContext, false);
  }

  public static Optional<ScenarioType> tryResolve(
      ConversationPhase phase,
      String userMessage,
      boolean hasActivePlan,
      boolean hasCurrentBundle,
      boolean hasChainContext,
      boolean hasReadyDraft) {
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String msg = userMessage.trim();

    if (UserIntentPatterns.matchesSnapshotIntent(msg)) {
      return Optional.of(ScenarioType.DEPLOY_CHAIN);
    }

    if (hasChainContext && UserIntentPatterns.matchesDeployIntent(msg)) {
      return Optional.of(ScenarioType.DEPLOY_CHAIN);
    }

    if (hasChainContext && UserIntentPatterns.matchesChainQuestion(msg)) {
      return Optional.of(ScenarioType.ASK_CHAIN);
    }

    if (phase == ConversationPhase.IMPORT_PENDING) {
      // The import arrives as a decision carrying its own scenario, so nothing here has to read
      // the reader's words to spot it.
      return Optional.of(ScenarioType.GATHER_REQUIREMENTS);
    }

    // The blanket phase routes below send a turn to CREATE without reading it. That is right while
    // a chain is being drafted and wrong once one exists: with a chain open, "delete the audit
    // step" is a change to that chain, and only the classifier can tell it from a new integration
    // being described. Phase alone must not keep the conversation in creation.
    if (phase == ConversationPhase.DISCOVERY && !hasChainContext) {
      return Optional.of(ScenarioType.GATHER_REQUIREMENTS);
    }

    if (phase == ConversationPhase.DESIGN_REVIEW && !hasChainContext) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_DRAFT && !hasChainContext) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    // Product planning phase. Compact implement wording advances CREATE / IMPLEMENT; short Agree
    // is not an approval shortcut — approvals arrive as decision commands.
    if (phase == ConversationPhase.PLAN_CANDIDATE
        && UserIntentPatterns.matchesStrongImplementChainIntent(msg)) {
      return Optional.of(
          hasCurrentBundle ? ScenarioType.IMPLEMENT_CHAIN : ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_CANDIDATE && !hasChainContext) {
      // Compact non-implement stays on product CREATE planning. Rich briefs fall through.
      if (!UserIntentPatterns.isCompactIntentMessage(msg)) {
        return Optional.empty();
      }
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_APPROVED) {
      if (UserIntentPatterns.matchesPlanQuestion(msg)) {
        return Optional.of(ScenarioType.ASK_PLAN);
      }
      // Compact implement only — rich prompts fall through to LLM; ScenarioRouter capability
      // ladder still demotes IMPLEMENT_CHAIN when derived artifacts are missing.
      if (UserIntentPatterns.matchesStrongImplementChainIntent(msg)) {
        return Optional.of(
            hasCurrentBundle ? ScenarioType.IMPLEMENT_CHAIN : ScenarioType.CREATE_CHAIN_PLAN);
      }
    }

    return Optional.empty();
  }
}
