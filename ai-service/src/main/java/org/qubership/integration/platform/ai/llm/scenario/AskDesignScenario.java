package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.AskDesignAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.ASK_DESIGN;

/** Scenario 2 — Query an existing design document (IDS-focused). */
@ApplicationScoped
@ForScenario(ASK_DESIGN)
public class AskDesignScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(AskDesignScenario.class);

  private final AskDesignAgent agent;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public AskDesignScenario(
      AskDesignAgent agent, StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG,
        ASK_DESIGN,
        conversationId,
        request,
        PromptProfile.ASK_DESIGN,
        agent::chat,
        false,
        null);
  }
}
