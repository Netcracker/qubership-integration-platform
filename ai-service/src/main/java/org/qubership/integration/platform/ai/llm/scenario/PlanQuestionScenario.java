package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationViewService;
import org.qubership.integration.platform.ai.presentation.QuestionIntent;
import org.qubership.integration.platform.ai.presentation.QuestionIntentClassifier;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

@ApplicationScoped
@ForScenario(ScenarioType.ASK_PLAN)
public class PlanQuestionScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(PlanQuestionScenario.class);
  private static final String NO_PLAN_MESSAGE =
      "No captured chain plan found for this conversation."
          + " Create a plan first, then ask questions about it.";

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final PlanPresentationViewService viewService;
  private final PlanPresentationFactsService factsService;

  @Inject
  public PlanQuestionScenario(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      PlanPresentationViewService viewService,
      PlanPresentationFactsService factsService) {
    this.runStore = runStore;
    this.artifactStore = artifactStore;
    this.viewService = viewService;
    this.factsService = factsService;
  }

  @Override
  public Multi<ChatEvent> handle(ChatRequest request, String conversationId, ScenarioType scenarioType) {
    String userMessage = request != null ? request.getEffectiveUserText() : "";
    ChainPlanGraph graph = latestProductGraph(conversationId).orElse(null);
    if (graph == null) {
      LOG.infof("ASK_PLAN without product graph conversationId=%s", conversationId);
      return Multi.createFrom().item(ChatEvent.token(NO_PLAN_MESSAGE));
    }

    SkillWorkspace workspace = new InMemorySkillWorkspace(conversationId);
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "product-pipeline",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));

    QuestionIntent intent = QuestionIntentClassifier.classify(userMessage);
    LOG.infof(
        "ASK_PLAN conversationId=%s intent=%s userChars=%d",
        conversationId, intent, userMessage != null ? userMessage.length() : 0);

    try {
      String answer = formatAnswer(workspace, graph, intent);
      return Multi.createFrom().item(ChatEvent.token(answer));
    } catch (JsonProcessingException e) {
      LOG.errorf(e, "Failed to format plan JSON conversationId=%s", conversationId);
      throw new RuntimeException(e);
    }
  }

  private java.util.Optional<ChainPlanGraph> latestProductGraph(String conversationId) {
    return runStore
        .loadByConversation(conversationId)
        .flatMap(
            doc ->
                artifactStore
                    .latest(doc.run().runId(), Kind.CHAIN_PLAN_GRAPH)
                    .map(revision -> artifactStore.payload(revision, ChainPlanGraph.class)));
  }

  private String formatAnswer(
      SkillWorkspace workspace, ChainPlanGraph graph, QuestionIntent intent)
      throws JsonProcessingException {
    return switch (intent) {
      case GRAPH -> viewService.formatMermaidFlowchart(graph);
      case TREE -> viewService.formatTree(graph);
      case JSON -> viewService.formatPrettyJson(graph);
      case SCRIPT -> viewService.formatScriptDetails(graph);
      case EXPLAIN -> factsService.formatFallbackSummary(factsService.build(workspace));
    };
  }
}
