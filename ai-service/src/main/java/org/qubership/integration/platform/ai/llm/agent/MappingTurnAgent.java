package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture;

/**
 * Interprets an author mapping message as typed changes. It holds no tools and does not replace
 * the requirement brief.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface MappingTurnAgent {

  @SystemMessage(fromResource = "prompts/roles/mapping-turn.md")
  @UserMessage(
      """
Approved flow:
{approvedFlow}

Current mapping intents:
{currentIntents}

Author message:
{authorMessage}\
""")
  MappingTurnCapture interpret(String approvedFlow, String currentIntents, String authorMessage);
}
