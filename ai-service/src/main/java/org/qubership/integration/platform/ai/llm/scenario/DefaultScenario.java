package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.DefaultAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.UNKNOWN;

/**
 * Default scenario for {@link ScenarioType#UNKNOWN} — general QIP help (default-system prompt) with
 * RAG.
 */
@ApplicationScoped
@ForScenario(UNKNOWN)
public class DefaultScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(DefaultScenario.class);

  private final DefaultAgent agent;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public DefaultScenario(DefaultAgent agent, StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG, UNKNOWN, conversationId, request, PromptProfile.GENERAL, agent::chat, false, null);
  }
}
