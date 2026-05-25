package org.qubership.integration.platform.ai.llm.scenario;

import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.model.ScenarioType;

/** Shared scenario turn logging. */
public final class ScenarioLogging {

  private ScenarioLogging() {}

  public static void logScenarioStart(
      Logger log, ScenarioType scenarioType, String conversationId, ChatRequest request) {
    log.infof(
        "Scenario %s: conversationId=%s, userPreview=%s",
        scenarioType,
        conversationId,
        AiTraceLog.previewOneLine(
            request.getEffectiveUserText(), AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));
  }
}
