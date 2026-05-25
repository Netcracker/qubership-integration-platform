package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogElementsCreatorService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogPatchPreparationService;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogChainDiffs;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** LangChain4j catalog tools for element CRUD and batch materialization. */
@ApplicationScoped
public class CatalogElementTools {

  private static final String TOOL_CREATE_ELEMENT = "createElement";
  private static final String TOOL_GET_ELEMENTS = "getElements";
  private static final String TOOL_GET_ELEMENT = "getElement";
  private static final String TOOL_DELETE_ELEMENT = "deleteElement";
  private static final String TOOL_TRANSFER = "transferElements";

  private static final Logger LOG = Logger.getLogger(CatalogElementTools.class);

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Inject ObjectMapper objectMapper;

  @Inject CatalogPatchPreparationService catalogPatchPreparationService;

  @Inject CatalogElementPlacementRules catalogElementPlacementRules;

  @Inject CatalogElementsCreatorService catalogElementsCreatorService;

  @Inject CatalogToolSupport support;

  @Tool(
      "Create a QIP element skeleton in a chain. chainId is the catalog chainId returned by"
          + " createChain. Runtime-catalog create accepts only placement: type plus optional"
          + " parentElementId and swimlaneId. Business properties and display name must be applied"
          + " afterwards via updateElement (PATCH). Pass empty string for unused parentElementId or"
          + " swimlaneId. Placement is validated locally against embedded catalog descriptors"
          + " (parentRestriction, allowedInContainers, inbound trigger nesting): create `condition`"
          + " first for `if`/`else` branch shells, then reuse catalog-created shells or use"
          + " transferElements. When the parent descriptor marks the child multiplicity as `one` or"
          + " `one-or-zero` and a matching child already exists under that exact parentElementId,"
          + " this tool returns the existing element id (auto-shell rebound) instead of calling the"
          + " catalog again. For `one-or-many` / `two-or-many` children (e.g. extra `catch-2`,"
          + " extra `if`), a new catalog element is created. Returns JSON: { ok, tool, message,"
          + " data: { elementId } }.")
  public String createElement(
      @P("Catalog chainId returned by createChain") String chainId,
      @P("Element type (e.g. http-trigger, service-call, script)") String elementType,
      @P("Parent container element ID; empty string if root-level element") String parentElementId,
      @P("Swimlane ID; empty string if default placement") String swimlaneId) {
    String blocked = CatalogMutationGuard.rejectOrNull(TOOL_CREATE_ELEMENT);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool createElement: chainId=%s, type=%s, parentElementId=%s, swimlaneId=%s",
        chainId, elementType, parentElementId, swimlaneId);
    try {
      Optional<String> placementErr =
          catalogElementPlacementRules.validateCreatePlacement(
              chainId, elementType, parentElementId);
      if (placementErr.isPresent()) {
        return support.catalogToolError(
            TOOL_CREATE_ELEMENT,
            CatalogToolResult.CODE_INVALID_ARGUMENT,
            placementErr.get());
      }
      Optional<String> reboundId =
          support.tryReuseAutoShellChildId(chainId, elementType, parentElementId);
      if (reboundId.isPresent()) {
        String out =
            support.catalogToolSuccess(
                TOOL_CREATE_ELEMENT,
                "Auto-shell child reused.",
                Map.of("elementId", reboundId.get()));
        support.logCatalogToolDone(TOOL_CREATE_ELEMENT, out);
        return out;
      }
      CatalogRestClient.ChainDiffDto result =
          catalogRestClient.createElement(
              chainId,
              new CatalogCreateElementRequest(
                  elementType,
                  CatalogStrings.blankToNull(parentElementId),
                  CatalogStrings.blankToNull(swimlaneId)));
      String elementId = CatalogChainDiffs.firstCreatedElementIdOrUnknown(result);
      String out =
          support.catalogToolSuccess(
              TOOL_CREATE_ELEMENT,
              "Element created (skeleton).",
              Map.of("elementId", elementId));
      support.logCatalogToolDone(TOOL_CREATE_ELEMENT, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_CREATE_ELEMENT, e);
    }
  }

  @Tool(
      "Create chain elements from ChainImplementationPlan JSON. chainId is the catalog chainId"
          + " returned by createChain. Runs a three-stage materializer: (1) skeleton — depth-first"
          + " POST with read-back binding of existing auto-shell children under containers; (2)"
          + " connections — POST plan dependencies idempotently; (3) properties — PATCH non-empty"
          + " expectedProperties per node (post-order). Persists the mutated plan to the active"
          + " snapshot when conversationId is on MDC. Returns JSON: { ok, tool, message?, data:"
          + " CreateElementsByJsonReport } with data.clientIds and data.stages.{skeleton,connections,"
          + " properties}. Fatal validation errors return { ok:false, error }. When the plan is"
          + " approved for this conversation, pass only {} for batchJson — the server loads the"
          + " active snapshot. Use data.clientIds and data.stages.*.failures[] before"
          + " createConnection/updateElement.")
  public String createElementsByJson(
      @P("Catalog chainId returned by createChain") String chainId,
      @P(
              "Use {} when the server has an approved active plan; do not embed"
                  + " ChainImplementationPlan JSON here")
          String batchJson) {
    String blocked = CatalogMutationGuard.rejectOrNull("createElementsByJson");
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool createElementsByJson: chainId=%s, batchJsonPresent=%s",
        chainId, batchJson != null && !CatalogToolSupport.isBlankOrEmptyPlanJson(batchJson));
    String out = catalogElementsCreatorService.createFromBatchJson(chainId, batchJson);
    if (!support.isToolError(out)) {
      support.logCatalogToolDone(CatalogToolSupport.TOOL_STEP_CREATE_ELEMENTS_BY_JSON, out);
    }
    return out;
  }

  @Tool(
      "PATCH a QIP element. patchJson must be one serialized JSON string of the PATCH object,"
          + " rather than a nested structure separate from the string parameter. Optional keys"
          + " inside that object: name, description, type, parentElementId, swimlaneId,"
          + " mandatoryChecksPassed, properties (object). If you only send property keys at the"
          + " root (legacy), they are wrapped under properties automatically. Non-empty properties"
          + " are merged server-side with the current catalog element and validated before PATCH;"
          + " up to two automatic retries run after catalog HTTP 400. Returns JSON: { ok, tool,"
          + " message, data: { elementId } } or { ok:false, error }.")
  public String updateElement(
      @P("Chain ID") String chainId,
      @P("Element ID to update") String elementId,
      @P("Single JSON string: serialized PATCH object body") String patchJson) {
    String blocked = CatalogMutationGuard.rejectOrNull("updateElement");
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool updateElement: chainId=%s, elementId=%s, patchPreview=%s",
        chainId, elementId, AiTraceLog.preview(patchJson, 400));
    try {
      return executeUpdateElementWithRetries(chainId, elementId, patchJson);
    } catch (IllegalArgumentException badJson) {
      LOG.warnf(badJson, "Catalog tool updateElement: invalid patch JSON or schema validation");
      String msg = badJson.getMessage();
      if (msg != null && msg.contains("Schema validation failed")) {
        return support.catalogToolError(
            CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT,
            CatalogToolResult.CODE_CATALOG_SCHEMA_VALIDATION_ERROR,
            msg,
            CatalogToolSupport.UPDATE_ELEMENT_REPAIR_EXHAUSTED_HINT);
      }
      return support.catalogToolError(
          CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          msg != null ? msg : "Invalid patch JSON or schema validation");
    } catch (Exception e) {
      return support.catalogToolError(CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT, e);
    }
  }

  @Tool(
      "List all elements in a chain (tree). Use for batch read-back after PATCH operations. Returns"
          + " JSON: { ok, tool, data: element tree }.")
  public String getElements(@P("Chain ID") String chainId) {
    LOG.infof("Catalog tool getElements: chainId=%s", chainId);
    try {
      List<CatalogElementResponseDto> elements = catalogRestClient.listElements(chainId);
      String out = support.catalogToolSuccess(TOOL_GET_ELEMENTS, elements);
      support.logCatalogToolDone(TOOL_GET_ELEMENTS, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_GET_ELEMENTS, e);
    }
  }

  @Tool(
      "Get one element by ID (safe-mode read-back). Returns JSON: { ok, tool, data: element }.")
  public String getElement(@P("Chain ID") String chainId, @P("Element ID") String elementId) {
    LOG.infof("Catalog tool getElement: chainId=%s, elementId=%s", chainId, elementId);
    try {
      CatalogElementResponseDto el = catalogRestClient.getElement(chainId, elementId);
      String out = support.catalogToolSuccess(TOOL_GET_ELEMENT, el);
      support.logCatalogToolDone(TOOL_GET_ELEMENT, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_GET_ELEMENT, e);
    }
  }

  @Tool(
      "Delete a QIP element from a chain. Returns JSON: { ok, tool, message, data: { elementId }"
          + " }.")
  public String deleteElement(
      @P("Chain ID") String chainId, @P("Element ID to delete") String elementId) {
    String blocked = CatalogMutationGuard.rejectOrNull(TOOL_DELETE_ELEMENT);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof("Catalog tool deleteElement: chainId=%s, elementId=%s", chainId, elementId);
    try {
      catalogRestClient.deleteElement(chainId, elementId);
      String out =
          support.catalogToolSuccess(
              TOOL_DELETE_ELEMENT, "Element deleted.", Map.of("elementId", elementId));
      support.logCatalogToolDone(TOOL_DELETE_ELEMENT, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_DELETE_ELEMENT, e);
    }
  }

  @Tool(
      "Move element UUIDs that already exist on the chain under the correct parent (POST"
          + " .../elements/transfer). Repair-only when steps were created at chain root; prefer"
          + " createElement(..., parentElementId=<targetParentId>) or createElementsByJson from"
          + " ChainImplementationPlan. parentElementId is always the direct parent where the step"
          + " must live — often a child shell of a routing container, not the outer container"
          + " itself. condition element: its only allowed children are branch shells (if, else, ..)"
          + " — never use the condition id as parentElementId for any workflow step (script,"
          + " service-call, mapper-2, http-sender, kafka-sender-2, log-record, or any other"
          + " non-shell type). After createElement(condition), call getElement(conditionId) and use"
          + " children[].id: put each step under the if or else child that matches its branch"
          + " (transferElements(chainId, <ifOrElseChildId>, elementIdsJson, swimlaneId)). Same rule"
          + " for other routing parents (split-2, try-catch-finally-2, circuit-breaker-2, ...):"
          + " parentElementId = the auto-created child shell for that branch/block, not the outer"
          + " container id. Inbound triggers (http-trigger, kafka-trigger-2, ...): stay at chain"
          + " root; use createConnection, never transferElements into condition or its shells."
          + " transfer only sets tree parent; wire execution order with createConnection afterward."
          + " elementIdsJson: JSON array string e.g. [\"uuid-a\"]. swimlaneId: empty string if"
          + " unused. On rejection, response includes How to fix with shell child ids when"
          + " getElement returned children. Returns JSON: { ok, tool, message, data }.")
  public String transferElements(
      @P("Chain ID") String chainId,
      @P(
              "Direct parent id: child shell under condition/split/try-catch (if, else, try-2,"
                  + " ...), not condition id")
          String parentElementId,
      @P("JSON array string of element IDs to move, e.g. [\"id1\",\"id2\"]") String elementIdsJson,
      @P("Swimlane ID; empty string if unused") String swimlaneId) {
    String blocked = CatalogMutationGuard.rejectOrNull(TOOL_TRANSFER);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool transferElements: chainId=%s, parentElementId=%s, elementIdsPreview=%s",
        chainId, parentElementId, AiTraceLog.preview(elementIdsJson, 200));
    if (parentElementId == null || parentElementId.isBlank()) {
      return support.catalogToolError(
          TOOL_TRANSFER,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "parentElementId is required",
          null);
    }
    try {
      List<String> ids = parseElementIdsJson(elementIdsJson);
      String previewParent = parentElementId.trim();
      CatalogElementResponseDto parentEl = catalogRestClient.getElement(chainId, previewParent);
      for (String id : ids) {
        CatalogElementResponseDto moved = catalogRestClient.getElement(chainId, id);
        Optional<String> placementErr =
            catalogElementPlacementRules.validateTransfer(parentEl.type, moved.type);
        if (placementErr.isPresent()) {
          String msg =
              TransferElementsPlacementHints.enrichPlacementError(
                  parentEl, moved, placementErr.get());
          LOG.warnf(
              "Catalog tool transferElements: placement rejected chainId=%s parent=%s child=%s: %s",
              chainId, previewParent, id, msg);
          return support.catalogToolError(
              TOOL_TRANSFER, CatalogToolResult.CODE_INVALID_ARGUMENT, msg);
        }
      }
      CatalogTransferElementsRequest body =
          new CatalogTransferElementsRequest(
              previewParent, CatalogStrings.blankToNull(swimlaneId), ids);
      CatalogRestClient.ChainDiffDto result = catalogRestClient.transferElements(chainId, body);
      String out =
          support.catalogToolSuccess(
              TOOL_TRANSFER,
              "Elements transferred under parent=" + previewParent + ".",
              Map.of("catalogDiff", result));
      support.logCatalogToolDone(TOOL_TRANSFER, out);
      return out;
    } catch (IllegalArgumentException e) {
      LOG.warnf(e, "Catalog tool transferElements: invalid input");
      return support.catalogToolError(
          TOOL_TRANSFER, CatalogToolResult.CODE_INVALID_ARGUMENT, e.getMessage());
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Catalog tool transferElements: invalid elementIdsJson");
      return support.catalogToolError(
          TOOL_TRANSFER,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "invalid elementIdsJson — " + e.getOriginalMessage());
    } catch (Exception e) {
      return support.catalogToolError(TOOL_TRANSFER, e);
    }
  }

  private String executeUpdateElementWithRetries(
      String chainId, String elementId, String patchJson) {
    for (int attempt = 1;
        attempt <= CatalogToolSupport.MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS;
        attempt++) {
      CatalogPatchPreparationService.PreparedUpdateBody prepared =
          catalogPatchPreparationService.prepareUpdateElementBody(chainId, elementId, patchJson);
      try {
        CatalogRestClient.ChainDiffDto result =
            catalogRestClient.updateElement(chainId, elementId, prepared.body());
        String id = CatalogChainDiffs.firstUpdatedElementIdOrUnknown(result);
        String out =
            support.catalogToolSuccess(
                CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT,
                "Element updated.",
                Map.of("elementId", id));
        support.logCatalogToolDone(CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT, out);
        return out;
      } catch (Exception e) {
        WebApplicationException wae = CatalogRestSupport.findWebApplicationException(e);
        int status = wae != null && wae.getResponse() != null ? wae.getResponse().getStatus() : -1;
        boolean lastAttempt = attempt == CatalogToolSupport.MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS;
        if (status == 400 && !lastAttempt) {
          LOG.warnf(
              e,
              "Catalog tool updateElement: catalog HTTP 400, retrying (%d/%d)",
              attempt,
              CatalogToolSupport.MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS);
          continue;
        }
        if (status == 400 && lastAttempt) {
          return support.catalogToolError(
              CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT,
              CatalogToolResult.CODE_CATALOG_HTTP_ERROR,
              CatalogRestSupport.describeExceptionForToolResult(e),
              CatalogToolSupport.UPDATE_ELEMENT_REPAIR_EXHAUSTED_HINT);
        }
        return support.catalogToolError(CatalogToolSupport.TOOL_STEP_UPDATE_ELEMENT, e);
      }
    }
    throw new IllegalStateException("updateElement loop exited without return");
  }

  private List<String> parseElementIdsJson(String elementIdsJson) throws JsonProcessingException {
    if (elementIdsJson == null || elementIdsJson.isBlank()) {
      throw new IllegalArgumentException(
          "elementIdsJson must be a non-empty JSON array of string ids");
    }
    JsonNode node = objectMapper.readTree(elementIdsJson);
    if (!node.isArray() || node.isEmpty()) {
      throw new IllegalArgumentException("elementIdsJson must be a non-empty JSON array");
    }
    List<String> out = new ArrayList<>();
    for (JsonNode idNode : node) {
      if (!idNode.isTextual()) {
        throw new IllegalArgumentException("elementIdsJson must contain only string UUIDs");
      }
      String id = idNode.asText().trim();
      if (id.isBlank()) {
        throw new IllegalArgumentException("element id must be non-blank");
      }
      out.add(id);
    }
    return out;
  }
}
