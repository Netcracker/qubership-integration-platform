package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.CompareDesignAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.COMPARE_AND_PATCH;

/** Scenario 4 — Compare an updated design with an existing chain and apply changes. */
@ApplicationScoped
@ForScenario(COMPARE_AND_PATCH)
public class CompareAndPatchScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(CompareAndPatchScenario.class);

  private final CompareDesignAgent agent;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public CompareAndPatchScenario(
      CompareDesignAgent agent,
      HitlStreamRegistry hitlStreamRegistry,
      StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.hitlStreamRegistry = hitlStreamRegistry;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG,
        COMPARE_AND_PATCH,
        conversationId,
        request,
        PromptProfile.MINIMAL,
        agent::chat,
        true,
        hitlStreamRegistry);
  }
}
