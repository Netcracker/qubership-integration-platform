package org.qubership.integration.platform.ai.llm.routing;

import java.util.Optional;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;

/** Managed ApiHub import handoff routing before phase policy and scenario hints. */
public final class ImportSpecificationRoutingPolicy {

  static final String AWAITING_PLAN_CONTINUATION_MESSAGE =
      "The specification is imported. Reply \"Continue\", then Agree to approve the requirement"
          + " and proceed toward the chain plan.";

  static final String ALREADY_IMPORTED_MESSAGE =
      "The specification is already imported. Reply \"Continue\" to create the implementation plan.";

  private ImportSpecificationRoutingPolicy() {}

  public static Optional<ScenarioRouter.RoutingOutcome> tryResolveManagedImportRouting(
      ChatRequest request, String conversationId, RequirementDraftStore draftStore) {
    if (conversationId == null || conversationId.isBlank() || draftStore == null) {
      return Optional.empty();
    }
    RequirementDraft draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return Optional.empty();
    }

    String userMessage = request != null ? request.getEffectiveUserText() : null;
    ScenarioType hint = request != null ? request.getScenarioHint() : null;

    if (draft.awaitingPlanContinuation()) {
      if (UserIntentPatterns.matchesImportPlanContinuation(userMessage)) {
        if (!draftStore.clearAwaitingPlanContinuation(conversationId)) {
          return Optional.of(ScenarioRouter.RoutingOutcome.terminal(AWAITING_PLAN_CONTINUATION_MESSAGE));
        }
        return Optional.of(ScenarioRouter.RoutingOutcome.scenario(ScenarioType.CREATE_CHAIN_PLAN));
      }
      return Optional.of(ScenarioRouter.RoutingOutcome.terminal(AWAITING_PLAN_CONTINUATION_MESSAGE));
    }

    if (draft.catalogBinding() != null && hint == ScenarioType.IMPORT_SPECIFICATION) {
      return Optional.of(ScenarioRouter.RoutingOutcome.terminal(ALREADY_IMPORTED_MESSAGE));
    }

    if (!draft.hasPendingImport()) {
      return Optional.empty();
    }

    // Only the decision advances the import: it states the scenario outright, so no wording has
    // to be recognized.
    if (hint == ScenarioType.IMPORT_SPECIFICATION) {
      return Optional.of(ScenarioRouter.RoutingOutcome.scenario(ScenarioType.IMPORT_SPECIFICATION));
    }

    return Optional.empty();
  }
}
