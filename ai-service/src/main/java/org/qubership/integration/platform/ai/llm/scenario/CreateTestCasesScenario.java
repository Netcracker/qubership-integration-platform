package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.CreateTestCasesAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.CREATE_TEST_CASES;

/** Scenario 6 — Generate structured test cases from a QIP chain or design. */
@ApplicationScoped
@ForScenario(CREATE_TEST_CASES)
public class CreateTestCasesScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(CreateTestCasesScenario.class);

  private final CreateTestCasesAgent agent;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public CreateTestCasesScenario(
      CreateTestCasesAgent agent, StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG,
        CREATE_TEST_CASES,
        conversationId,
        request,
        PromptProfile.TESTING,
        agent::chat,
        false,
        null);
  }
}
