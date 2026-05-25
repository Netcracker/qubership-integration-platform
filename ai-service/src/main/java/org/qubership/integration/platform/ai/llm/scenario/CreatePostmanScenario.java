package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.CreatePostmanAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.CREATE_POSTMAN_COLLECTION;

/** Scenario 7 — Generate a Postman Collection v2.1 JSON from test cases. */
@ApplicationScoped
@ForScenario(CREATE_POSTMAN_COLLECTION)
public class CreatePostmanScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(CreatePostmanScenario.class);

  private final CreatePostmanAgent agent;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public CreatePostmanScenario(
      CreatePostmanAgent agent, StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    return streamingScenarioSupport.streamAgent(
        LOG,
        CREATE_POSTMAN_COLLECTION,
        conversationId,
        request,
        PromptProfile.MINIMAL,
        agent::chat,
        false,
        null);
  }
}
