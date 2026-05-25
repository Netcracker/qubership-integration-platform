package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.CreateChainPlanAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.CREATE_CHAIN_PLAN;

/** Scenario — draft and confirm a {@code ChainImplementationPlan} without catalog mutations. */
@ApplicationScoped
@ForScenario(CREATE_CHAIN_PLAN)
public class CreateChainPlanScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(CreateChainPlanScenario.class);

  private final CreateChainPlanAgent agent;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public CreateChainPlanScenario(
      CreateChainPlanAgent agent,
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
        CREATE_CHAIN_PLAN,
        conversationId,
        request,
        PromptProfile.PLANNING,
        agent::chat,
        true,
        hitlStreamRegistry);
  }
}
