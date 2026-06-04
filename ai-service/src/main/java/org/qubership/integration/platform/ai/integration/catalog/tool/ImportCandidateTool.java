package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidate;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists ApiHub import candidates in the conversation planning diary. */
@ApplicationScoped
public class ImportCandidateTool {

  private static final String TOOL = "recordApiHubImportCandidate";

  private final ConversationPlanningDiaryService planningDiaryService;
  private final ObjectMapper objectMapper;

  @Inject
  public ImportCandidateTool(
      ConversationPlanningDiaryService planningDiaryService, ObjectMapper objectMapper) {
    this.planningDiaryService = planningDiaryService;
    this.objectMapper = objectMapper;
  }

  @Tool(
      "Save an ApiHub specification import candidate in the conversation diary. Call when catalog"
          + " lookup failed but ApiHub search found a specification that must be imported before"
          + " planning can bind real catalog ids. Returns JSON with candidateId.")
  public String recordApiHubImportCandidate(
      @P("Target catalog system name (human label for createSystem/search)") String catalogSystemName,
      @P("Catalog system type: INTERNAL or EXTERNAL") String catalogSystemType,
      @P("ApiHub packageId from search or IDS") String apiHubPackageId,
      @P("ApiHub version from search or IDS (not release on search)") String apiHubVersion,
      @P("ApiHub documentId from search (usually api)") String apiHubDocumentId,
      @P("Specification group name for catalog import (from packageName)") String apiHubSpecificationName,
      @P("Optional note (operation title or IDS reference)") String sourceNote) {
    String conversationId = resolveConversationIdFromMdc();
    if (conversationId.isBlank()) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "conversationId is required");
    }
    ApiHubImportCandidate candidate =
        planningDiaryService.recordImportCandidate(
            conversationId,
            catalogSystemName,
            catalogSystemType,
            apiHubPackageId,
            apiHubVersion,
            apiHubDocumentId,
            apiHubSpecificationName,
            sourceNote);
    if (candidate == null || !candidate.hasRequiredFields()) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "Import candidate is missing required fields");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", candidate.candidateId());
    data.put("catalogSystemName", candidate.catalogSystemName());
    data.put("apiHubPackageId", candidate.apiHubPackageId());
    data.put("apiHubVersion", candidate.apiHubVersion());
    data.put("apiHubSpecificationName", candidate.apiHubSpecificationName());
    return CatalogToolResult.successMessage(
        objectMapper,
        TOOL,
        "Import candidate saved. Ask the user to confirm IMPORT_SPECIFICATION or send"
            + " scenarioHint=IMPORT_SPECIFICATION.",
        data);
  }

  private static String resolveConversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}
