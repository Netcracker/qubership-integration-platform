package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportHandoffSupport;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * Routes to {@link ScenarioType#IMPORT_SPECIFICATION} when planning diary has a pending ApiHub
 * import handoff from HITL or the user message confirms import. Suppresses import routing for
 * short plan confirmations when import already completed or an active plan exists.
 */
public final class ImportSpecificationRoutingPolicy {

  private ImportSpecificationRoutingPolicy() {}

  public static ScenarioType effectiveScenario(
      ScenarioType chosen,
      String conversationId,
      String userMessage,
      ConversationPlanningDiaryService planningDiaryService) {
    return effectiveScenario(chosen, conversationId, userMessage, planningDiaryService, false);
  }

  public static ScenarioType effectiveScenario(
      ScenarioType chosen,
      String conversationId,
      String userMessage,
      ConversationPlanningDiaryService planningDiaryService,
      boolean hasActivePlan) {
    if (planningDiaryService == null || conversationId == null || conversationId.isBlank()) {
      return chosen;
    }
    ScenarioType routed = coercePendingImport(chosen, conversationId, userMessage, planningDiaryService);
    return suppressInappropriateImportRoute(
        routed, conversationId, userMessage, planningDiaryService, hasActivePlan);
  }

  private static ScenarioType coercePendingImport(
      ScenarioType chosen,
      String conversationId,
      String userMessage,
      ConversationPlanningDiaryService planningDiaryService) {
    if (!planningDiaryService.hasPendingApiHubImport(conversationId)) {
      return chosen;
    }
    if (planningDiaryService.isImportHandoffPending(conversationId)) {
      return ScenarioType.IMPORT_SPECIFICATION;
    }
    if (ApiHubImportHandoffSupport.isImportSpecificationAnswer(userMessage)
        || UserIntentPatterns.matchesExplicitImportRequest(userMessage)) {
      return ScenarioType.IMPORT_SPECIFICATION;
    }
    return chosen;
  }

  private static ScenarioType suppressInappropriateImportRoute(
      ScenarioType chosen,
      String conversationId,
      String userMessage,
      ConversationPlanningDiaryService planningDiaryService,
      boolean hasActivePlan) {
    if (chosen != ScenarioType.IMPORT_SPECIFICATION) {
      return chosen;
    }
    if (planningDiaryService.isImportHandoffPending(conversationId)) {
      return chosen;
    }
    boolean importCompleted = planningDiaryService.lastImportResult(conversationId).isPresent();
    boolean importPending = planningDiaryService.hasPendingApiHubImport(conversationId);
    boolean shortContinuation = UserIntentPatterns.matchesShortPlanContinuation(userMessage);
    boolean explicitImport = UserIntentPatterns.matchesExplicitImportRequest(userMessage);

    if (!importPending && !explicitImport) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    if (hasActivePlan && shortContinuation) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    if (importCompleted && shortContinuation) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    if (importCompleted && !explicitImport) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    if (explicitImport && !importCompleted) {
      return chosen;
    }
    return chosen;
  }
}
