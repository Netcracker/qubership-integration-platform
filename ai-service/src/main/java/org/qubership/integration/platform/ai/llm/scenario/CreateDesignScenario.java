package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.CreateDesignAgent;
import org.qubership.integration.platform.ai.llm.artifact.DesignArtifactService;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;

import static org.qubership.integration.platform.ai.model.ScenarioType.CREATE_DESIGN;

/** Scenario 1 — Create Integration Design Specification from free text. */
@ApplicationScoped
@ForScenario(CREATE_DESIGN)
public class CreateDesignScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(CreateDesignScenario.class);

  private final CreateDesignAgent agent;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final DesignArtifactService designArtifactService;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public CreateDesignScenario(
      CreateDesignAgent agent,
      HitlStreamRegistry hitlStreamRegistry,
      DesignArtifactService designArtifactService,
      StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.hitlStreamRegistry = hitlStreamRegistry;
    this.designArtifactService = designArtifactService;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    ScenarioLogging.logScenarioStart(LOG, CREATE_DESIGN, conversationId, request);
    String augmentedMessage =
        streamingScenarioSupport.assemblePrompt(conversationId, request, PromptProfile.DESIGN);

    StringBuilder streamedIds = new StringBuilder();
    Multi<String> agentTokens =
        agent
            .chat(conversationId, augmentedMessage)
            .onItem()
            .invoke(streamedIds::append)
            .onFailure()
            .invoke(err -> LOG.errorf(err, "CreateDesign failed"));

    Multi<String> withArtifactFooter =
        Multi.createBy().concatenating().streams(agentTokens, artifactFooterMulti(streamedIds));

    return hitlStreamRegistry.wrapWithHitl(conversationId, withArtifactFooter);
  }

  private Multi<String> artifactFooterMulti(StringBuilder streamedIds) {
    Multi<Optional<String>> optionalFooter =
        Multi.createFrom()
            .uni(
                Uni.createFrom()
                    .item(() -> designArtifactService.buildPostStreamFooter(streamedIds.toString()))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    return optionalFooter
        .onItem()
        .transformToMultiAndConcatenate(
            opt ->
                opt.map(s -> Multi.createFrom().item(s))
                    .orElseGet(() -> Multi.createFrom().empty()));
  }
}
