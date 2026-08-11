package org.qubership.integration.platform.ai.llm.routing;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.model.ScenarioType;

/** LLM fallback classifier when deterministic routing does not match. */
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
(COLD = no requirement draft; DISCOVERY / PLAN_DRAFT = draft plan; DESIGN_REVIEW = design gate;
planning approval phase = implementation plan review; PLAN_APPROVED = current generated bundle ready.)

Latest user message to classify (same as the last User line when history is present):
{message}

Reply with ONLY the scenario type name (e.g. IMPLEMENT_CHAIN). No explanation, no punctuation.\
""")
  ScenarioType classify(String recentConversation, String conversationPhase, String message);
}
