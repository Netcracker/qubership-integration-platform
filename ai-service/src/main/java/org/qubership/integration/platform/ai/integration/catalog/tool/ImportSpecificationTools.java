package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidate;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportResult;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Catalog import tools for IMPORT_SPECIFICATION scenario. */
@ApplicationScoped
public class ImportSpecificationTools {

  private static final String TOOL = "importApiHubSpecificationToCatalog";

  private final ApiHubSpecificationImportService importService;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ObjectMapper objectMapper;

  @Inject
  public ImportSpecificationTools(
      ApiHubSpecificationImportService importService,
      ConversationPlanningDiaryService planningDiaryService,
      ObjectMapper objectMapper) {
    this.importService = importService;
    this.planningDiaryService = planningDiaryService;
    this.objectMapper = objectMapper;
  }

  @Tool(
      "Import the full ApiHub specification from a saved import candidate into runtime-catalog."
          + " Fetches get_document, uploads via POST /v1/specificationGroups/import, polls import"
          + " status, and returns systemId/specificationId. Continue planning with"
          + " CREATE_CHAIN_PLAN after success.")
  public String importApiHubSpecificationToCatalog(
      @P("Optional candidateId from diary; omit to use the latest candidate") String candidateId) {
    String blocked = rejectUnlessImportScenario();
    if (blocked != null) {
      return blocked;
    }
    String conversationId = resolveConversationIdFromMdc();
    if (conversationId.isBlank()) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "conversationId is required");
    }
    Optional<ApiHubImportCandidate> candidateOpt =
        planningDiaryService.resolveImportCandidate(conversationId, candidateId);
    if (candidateOpt.isEmpty()) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "No import candidate in the planning diary. Run CREATE_CHAIN_PLAN discovery first or"
              + " call recordApiHubImportCandidate.",
          "Search ApiHub, save a candidate, then run IMPORT_SPECIFICATION.");
    }
    try {
      ApiHubSpecificationImportService.ImportOutcome outcome =
          importService.importCandidate(conversationId, candidateOpt.get());
      ApiHubImportResult result = outcome.result();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("systemId", result.systemId());
      data.put("specificationId", result.specificationId());
      data.put("specificationGroupId", result.specificationGroupId());
      data.put("importId", result.importId());
      data.put("apiHubSpecificationName", result.apiHubSpecificationName());
      data.put("nextScenario", ScenarioType.CREATE_CHAIN_PLAN.name());
      return CatalogToolResult.successMessage(
          objectMapper,
          TOOL,
          "Specification imported. Continue with CREATE_CHAIN_PLAN to bind catalog operations.",
          data);
    } catch (Exception e) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_TOOL_EXECUTION_ERROR,
          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
  }

  static String rejectUnlessImportScenario() {
    String scenarioRaw = MDC.get(ChatMdc.SCENARIO_TYPE);
    if (scenarioRaw == null || scenarioRaw.isBlank()) {
      return null;
    }
    ScenarioType scenario;
    try {
      scenario = ScenarioType.valueOf(scenarioRaw.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (scenario == ScenarioType.IMPORT_SPECIFICATION) {
      return null;
    }
    return CatalogToolResult.error(
        new ObjectMapper(),
        TOOL,
        CatalogToolResult.CODE_MUTATION_NOT_ALLOWED,
        "ApiHub specification import is only allowed in scenario IMPORT_SPECIFICATION.",
        "Use scenarioHint=IMPORT_SPECIFICATION after saving an import candidate in planning.");
  }

  private static String resolveConversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}
