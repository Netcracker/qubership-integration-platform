package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.ApiHubExistingCatalogBinder;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;

/**
 * Structured gather tool that pins an API Hub import candidate for the active conversation.
 * Prefer this over stuffing {@code apiHubCandidate} into {@link RequirementDraftTool#captureRequirementDraft}.
 * When the runtime catalog already has a matching system/spec, binds that hierarchy instead of
 * offering the import decision.
 */
@ApplicationScoped
public class SelectApiHubCandidateTool {

  private static final Logger LOG = Logger.getLogger(SelectApiHubCandidateTool.class);

  static final String TOOL_NAME = "selectApiHubCandidate";

  private final RequirementDraftStore store;
  private final ConversationApiHubCache apiHubCache;
  private final ApiHubExistingCatalogBinder existingCatalogBinder;
  private final ObjectMapper objectMapper;

  @Inject
  public SelectApiHubCandidateTool(
      RequirementDraftStore store,
      ConversationApiHubCache apiHubCache,
      ApiHubExistingCatalogBinder existingCatalogBinder,
      ObjectMapper objectMapper) {
    this.store = store;
    this.apiHubCache = apiHubCache;
    this.existingCatalogBinder = existingCatalogBinder;
    this.objectMapper = objectMapper;
  }

  SelectApiHubCandidateTool(RequirementDraftStore store, ConversationApiHubCache apiHubCache) {
    this(store, apiHubCache, null, new ObjectMapper());
  }

  SelectApiHubCandidateTool(
      RequirementDraftStore store,
      ConversationApiHubCache apiHubCache,
      ApiHubExistingCatalogBinder existingCatalogBinder) {
    this(store, apiHubCache, existingCatalogBinder, new ObjectMapper());
  }

  @Tool("""
      Pin the API Hub package/operation for this conversation after searchApiOperations
      (or getApiOperationSpecification) returns a match. The server validates refs and either
      binds an existing runtime-catalog hierarchy or stores an import candidate — do NOT put
      apiHubCandidate on captureRequirementDraft.
      Required: packageId, version, and either operationId or documentId (use documentId=api when
      importing the whole package). Optional: apiType (default rest), packageName.
      Returns JSON: { ok, tool, candidate, nextStep, openQuestion?, catalogBinding? }.
      """)
  public String selectApiHubCandidate(
      @P("API Hub packageId from the search hit, e.g. S.ProdCat.PartyMgmt") String packageId,
      @P("API Hub version from the search hit, e.g. 2026.2@1") String version,
      @P("Optional operationId from the search hit") String operationId,
      @P("Optional documentId/slug; use api when importing the whole package") String documentId,
      @P("Optional apiType: rest, graphql, or asyncapi (default rest)") String apiType,
      @P("Optional human-readable packageName from the search hit") String packageName) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL_NAME,
        conversationId,
        "packageId="
            + packageId
            + " version="
            + version
            + " operationId="
            + AiTraceLog.preview(operationId, 40));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(
            conversationId,
            startMs,
            errorJson("conversationId is required (no active chat session)"));
      }

      String resolvedPackageId = CatalogStrings.blankToNull(packageId);
      String resolvedVersion = CatalogStrings.blankToNull(version);
      String resolvedOperationId = CatalogStrings.blankToNull(operationId);
      String resolvedDocumentId = CatalogStrings.blankToNull(documentId);
      if (resolvedOperationId == null && resolvedDocumentId == null) {
        resolvedDocumentId = ApiHubRequirementRefs.DEFAULT_DOCUMENT_SLUG;
      }

      ApiHubRequirementRefs candidate =
          new ApiHubRequirementRefs(
              resolvedPackageId,
              resolvedVersion,
              resolvedOperationId,
              resolvedDocumentId,
              CatalogStrings.blankToNull(apiType),
              CatalogStrings.blankToNull(packageName),
              null);

      if (!candidate.hasImportableRefs()) {
        return finish(
            conversationId,
            startMs,
            errorJson(
                "selectApiHubCandidate requires packageId, version, and operationId or"
                    + " documentId"));
      }

      if (apiHubCache != null) {
        apiHubCache.rememberCandidate(conversationId, candidate);
      }

      Optional<ResolvedCatalogBinding> existing =
          existingCatalogBinder == null
              ? Optional.empty()
              : existingCatalogBinder.resolve(conversationId, candidate);
      if (existing.isPresent()) {
        return finish(
            conversationId,
            startMs,
            storeExistingCatalogBinding(conversationId, candidate, existing.get()));
      }

      RequirementDraft previous = store.get(conversationId).orElse(null);
      RequirementDraft draft;
      if (previous != null) {
        draft = previous.withApiHubCandidate(candidate);
      } else {
        String assembled =
            "API Hub candidate selected: "
                + (CatalogStrings.blankToNull(candidate.packageName()) != null
                    ? candidate.packageName()
                    : candidate.packageId());
        draft =
            new RequirementDraft(
                false,
                assembled,
                DraftDecision.NEEDS_INPUT,
                List.of(),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "unknown",
                "unknown",
                candidate,
                null,
                false,
                List.of(),
                true,
                null);
      }
      store.put(conversationId, draft);
      store.markCaptured(conversationId);
      ProductCapabilityCaptureContext.offerDraft(draft);

      ObjectNode root = objectMapper.createObjectNode();
      root.put("ok", true);
      root.put("tool", TOOL_NAME);
      root.put("nextStep", "The reader is offered the import as a decision; do not ask for a phrase.");
      putCandidate(root, candidate);
      String json = objectMapper.writeValueAsString(root);
      LOG.infof(
          "selectApiHubCandidate: stored conversationId=%s packageId=%s version=%s"
              + " operationId=%s documentId=%s",
          conversationId,
          candidate.packageId(),
          candidate.version(),
          candidate.operationId(),
          candidate.documentId());
      return finish(conversationId, startMs, json);
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, e);
      return errorJson("Error selecting API Hub candidate: " + e.getMessage());
    }
  }

  private String storeExistingCatalogBinding(
      String conversationId, ApiHubRequirementRefs candidate, ResolvedCatalogBinding binding)
      throws Exception {
    RequirementDraft previous = store.get(conversationId).orElse(null);
    RequirementDraft draft;
    if (previous != null) {
      draft = previous.withCatalogBinding(binding);
    } else {
      String assembled =
          "Bound existing catalog service for "
              + (CatalogStrings.blankToNull(candidate.packageName()) != null
                  ? candidate.packageName()
                  : candidate.packageId());
      draft =
          new RequirementDraft(
                  false,
                  assembled,
                  DraftDecision.READY_FOR_PLAN,
                  List.of(),
                  RequirementDraftTool.SOURCE_SKILL_ID,
                  "unknown")
              .withCatalogBinding(binding);
    }
    store.put(conversationId, draft);
    store.markCaptured(conversationId);
    ProductCapabilityCaptureContext.offerDraft(draft);

    ObjectNode root = objectMapper.createObjectNode();
    root.put("ok", true);
    root.put("tool", TOOL_NAME);
    root.put(
        "nextStep",
        "Runtime catalog already has this service. Continue gathering remaining requirements,"
            + " then captureRequirementDraft with READY_FOR_PLAN (do not ask for Import"
            + " specification).");
    putCandidate(root, candidate);
    ObjectNode bound = root.putObject("catalogBinding");
    bound.put("systemId", binding.systemId());
    bound.put("specificationId", binding.specificationId());
    bound.put("specificationGroupId", binding.specificationGroupId());
    if (CatalogStrings.blankToNull(binding.integrationOperationId()) != null) {
      bound.put("integrationOperationId", binding.integrationOperationId());
    }
    LOG.infof(
        "selectApiHubCandidate: bound existing catalog conversationId=%s systemId=%s"
            + " packageId=%s",
        conversationId, binding.systemId(), candidate.packageId());
    return objectMapper.writeValueAsString(root);
  }

  private void putCandidate(ObjectNode root, ApiHubRequirementRefs candidate) {
    ObjectNode cand = root.putObject("candidate");
    cand.put("packageId", candidate.packageId());
    cand.put("version", candidate.version());
    if (CatalogStrings.blankToNull(candidate.operationId()) != null) {
      cand.put("operationId", candidate.operationId());
    }
    if (CatalogStrings.blankToNull(candidate.documentId()) != null) {
      cand.put("documentId", candidate.documentId());
    }
    cand.put("apiType", candidate.resolvedApiType());
    if (CatalogStrings.blankToNull(candidate.packageName()) != null) {
      cand.put("packageName", candidate.packageName());
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String errorJson(String message) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("ok", false);
      root.put("tool", TOOL_NAME);
      root.put("error", message);
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"ok\":false,\"tool\":\"" + TOOL_NAME + "\",\"error\":\"" + message + "\"}";
    }
  }
}
