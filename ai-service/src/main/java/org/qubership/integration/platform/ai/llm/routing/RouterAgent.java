package org.qubership.integration.platform.ai.llm.routing;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * AI service that classifies incoming user messages into a {@link ScenarioType}. Uses a
 * low-temperature call to produce a deterministic single-word response.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface RouterAgent {

  @SystemMessage(fromResource = "prompts/router-system.md")
  @UserMessage(
      """
Recent conversation (oldest first; may be brief on the first turn):
{recentConversation}

Current conversation phase: {conversationPhase}
(COLD = no captured plan; DISCOVERY / PLAN_DRAFT = draft plan; PLAN_APPROVED = plan approved for execution.)

Latest user message to classify (same as the last User line when history is present):
{message}

Reply with ONLY the scenario type name (e.g. IMPLEMENT_CHAIN). No explanation, no punctuation.\
""")
  ScenarioType classify(String recentConversation, String conversationPhase, String message);
}
