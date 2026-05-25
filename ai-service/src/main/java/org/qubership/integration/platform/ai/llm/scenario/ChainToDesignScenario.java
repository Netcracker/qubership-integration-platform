package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.ChainToDesignAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.CHAIN_TO_DESIGN;

/** Scenario 5 — Reverse-engineer a QIP chain into an IDS design document. */
@ApplicationScoped
@ForScenario(CHAIN_TO_DESIGN)
public class ChainToDesignScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainToDesignScenario.class);

  private final ChainToDesignAgent agent;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public ChainToDesignScenario(
      ChainToDesignAgent agent, StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG,
        CHAIN_TO_DESIGN,
        conversationId,
        request,
        PromptProfile.CHAIN_TO_DESIGN,
        agent::chat,
        false,
        null);
  }
}
