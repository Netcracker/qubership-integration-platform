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
        phase, userMessage, hasActivePlan, hasCurrentBundle, hasChainContext, false, false);
  }

  public static Optional<ScenarioType> tryResolve(
      ConversationPhase phase,
      String userMessage,
      boolean hasActivePlan,
      boolean hasCurrentBundle,
      boolean hasChainContext,
      boolean hasReadyDraft) {
    return tryResolve(
        phase,
        userMessage,
        hasActivePlan,
        hasCurrentBundle,
        hasChainContext,
        hasReadyDraft,
        false);
  }

  /**
   * @param hasImportConfirmOpenQuestion current open question is the pinned import-confirm prompt
   *     (Agree answers that question only; see ADR decision 4)
   */
  public static Optional<ScenarioType> tryResolve(
      ConversationPhase phase,
      String userMessage,
      boolean hasActivePlan,
      boolean hasCurrentBundle,
      boolean hasChainContext,
      boolean hasReadyDraft,
      boolean hasImportConfirmOpenQuestion) {
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String msg = userMessage.trim();

    if (hasChainContext && UserIntentPatterns.matchesChainQuestion(msg)) {
      return Optional.of(ScenarioType.ASK_CHAIN);
    }

    if (phase == ConversationPhase.IMPORT_PENDING) {
      if (UserIntentPatterns.matchesImportSpecificationCommand(msg)
          || UserIntentPatterns.matchesExplicitImportRequest(msg)) {
        return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
      }
      // Agree ≡ pinned import-confirm only; mixed / non-import open-Q stays on gather.
      if (hasImportConfirmOpenQuestion
          && UserIntentPatterns.matchesShortPlanContinuation(msg)) {
        return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
      }
      return Optional.of(ScenarioType.GATHER_REQUIREMENTS);
    }

    if (phase == ConversationPhase.DISCOVERY) {
      // Ready draft + Agree leaves gather for product CREATE planning.
      // Do not Agree→IMPORT from DISCOVERY (ADR decision 4 / IMPORT_PENDING).
      if (hasReadyDraft && UserIntentPatterns.matchesShortPlanContinuation(msg)) {
        return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
      }
      return Optional.of(ScenarioType.GATHER_REQUIREMENTS);
    }

    if (phase == ConversationPhase.DESIGN_REVIEW) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_DRAFT) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    // Product planning approval phase. Compact Agree / build / execute advances CREATE.
    if (phase == ConversationPhase.PLAN_CANDIDATE
        && (UserIntentPatterns.matchesShortPlanContinuation(msg)
            || UserIntentPatterns.matchesStrongImplementChainIntent(msg))) {
      return Optional.of(
          hasCurrentBundle ? ScenarioType.IMPLEMENT_CHAIN : ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_CANDIDATE) {
      // Compact non-approve stays on product CREATE planning. Rich briefs fall through.
      if (!UserIntentPatterns.isCompactIntentMessage(msg)) {
        return Optional.empty();
      }
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (phase == ConversationPhase.PLAN_APPROVED) {
      if (UserIntentPatterns.matchesPlanQuestion(msg)) {
        return Optional.of(ScenarioType.ASK_PLAN);
      }
      // Compact implement / Agree only — rich prompts fall through to LLM; ScenarioRouter
      // capability ladder still demotes IMPLEMENT_CHAIN when derived artifacts are missing.
      if (UserIntentPatterns.matchesStrongImplementChainIntent(msg)
          || UserIntentPatterns.matchesShortPlanContinuation(msg)) {
        return Optional.of(
            hasCurrentBundle ? ScenarioType.IMPLEMENT_CHAIN : ScenarioType.CREATE_CHAIN_PLAN);
      }
      if (UserIntentPatterns.matchesSpineRetryContinuation(msg)) {
        return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
      }
    }

    return Optional.empty();
  }
}
