package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.chat.planning.ImportCandidateBootstrapService;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.ImportSpecificationAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.IMPORT_SPECIFICATION;

/** Scenario — import a full ApiHub specification into runtime-catalog before chain planning. */
@ApplicationScoped
@ForScenario(IMPORT_SPECIFICATION)
public class ImportSpecificationScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ImportSpecificationScenario.class);

  private final ImportSpecificationAgent agent;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final StreamingScenarioSupport streamingScenarioSupport;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ImportCandidateBootstrapService importCandidateBootstrapService;
  private final ConversationService conversationService;

  public ImportSpecificationScenario(
      ImportSpecificationAgent agent,
      HitlStreamRegistry hitlStreamRegistry,
      StreamingScenarioSupport streamingScenarioSupport,
      ConversationPlanningDiaryService planningDiaryService,
      ImportCandidateBootstrapService importCandidateBootstrapService,
      ConversationService conversationService) {
    this.agent = agent;
    this.hitlStreamRegistry = hitlStreamRegistry;
    this.streamingScenarioSupport = streamingScenarioSupport;
    this.planningDiaryService = planningDiaryService;
    this.importCandidateBootstrapService = importCandidateBootstrapService;
    this.conversationService = conversationService;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    if (planningDiaryService.resolveImportCandidate(conversationId, null).isEmpty()) {
      importCandidateBootstrapService.ensureCandidateForImport(
          conversationId, conversationService.getMessages(conversationId));
    }
    if (planningDiaryService.resolveImportCandidate(conversationId, null).isEmpty()) {
      LOG.infof(
          "Scenario IMPORT_SPECIFICATION: no import candidate in diary conversationId=%s —"
              + " agent will try recordApiHubImportCandidate from context",
          conversationId);
    }

    return streamingScenarioSupport.streamAgent(
        LOG,
        IMPORT_SPECIFICATION,
        conversationId,
        request,
        PromptProfile.IMPORT_SPECIFICATION,
        agent::chat,
        true,
        hitlStreamRegistry);
  }
}
