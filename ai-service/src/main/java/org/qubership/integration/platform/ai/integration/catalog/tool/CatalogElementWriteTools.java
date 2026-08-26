package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.integration.catalog.util.HttpMethodRestrictCatalogShape;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;

/** LangChain4j catalog write tools for create, update, and list on an existing chain. */
@ApplicationScoped
public class CatalogElementWriteTools {

  private static final String TOOL_CREATE_ELEMENT = "createElement";
  private static final String TOOL_UPDATE_ELEMENT = "updateElement";
  private static final String TOOL_LIST_ELEMENTS = "listElements";

  private static final Logger LOG = Logger.getLogger(CatalogElementWriteTools.class);

  private final CatalogRestClient catalogRestClient;
  private final CatalogToolSupport support;
  private final ObjectMapper objectMapper;

  @Inject
  public CatalogElementWriteTools(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogToolSupport support,
      ObjectMapper objectMapper) {
    this.catalogRestClient = catalogRestClient;
    this.support = support;
    this.objectMapper = objectMapper;
  }

  @Tool(
      "Create a catalog element on an existing chain. Only type (and optional parent) on create."
          + " Then call updateElement to set name and properties. Returns created element id(s).")
  public String createElement(
      @P("Catalog chain id") String chainId,
      @P("Element type, e.g. http-trigger") String type,
      @P("Optional parent element id") String parentElementId) {
    String trimmedChainId = CatalogStrings.blankToNull(chainId);
    if (trimmedChainId == null) {
      return support.catalogToolError(
          TOOL_CREATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "chainId is required",
          "Pass the catalog chain UUID.");
    }
    String trimmedType = CatalogStrings.blankToNull(type);
    if (trimmedType == null) {
      return support.catalogToolError(
          TOOL_CREATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "type is required",
          "Pass a catalog element type such as http-trigger or script.");
    }

    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL_CREATE_ELEMENT,
        trimmedChainId,
        "type=" + trimmedType + " parentElementId=" + parentElementId);
    try {
      CatalogCreateElementRequest request =
          new CatalogCreateElementRequest(
              trimmedType, CatalogStrings.blankToNull(parentElementId), null);
      CatalogRestClient.ChainDiffDto diff =
          catalogRestClient.createElement(trimmedChainId, request);
      String elementId = extractPrimaryCreatedElementId(diff, trimmedType);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("elementId", elementId);
      data.put("createdElements", summarizeCreatedElements(diff));
      String out = support.catalogToolSuccess(TOOL_CREATE_ELEMENT, data);
      support.logCatalogToolDone(TOOL_CREATE_ELEMENT, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_CREATE_ELEMENT, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      LOG.warnf(e, "createElement failed for chainId=%s type=%s", trimmedChainId, trimmedType);
      String out = support.catalogToolError(TOOL_CREATE_ELEMENT, e);
      support.logCatalogToolDone(TOOL_CREATE_ELEMENT, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_CREATE_ELEMENT, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    }
  }

  @Tool("PATCH an existing catalog element. Pass JSON object with name and/or properties map.")
  public String updateElement(
      @P("Catalog chain id") String chainId,
      @P("Element id from createElement/listElements") String elementId,
      @P("JSON object string for PATCH body") String patchJson) {
    String trimmedChainId = CatalogStrings.blankToNull(chainId);
    if (trimmedChainId == null) {
      return support.catalogToolError(
          TOOL_UPDATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "chainId is required",
          null);
    }
    String trimmedElementId = CatalogStrings.blankToNull(elementId);
    if (trimmedElementId == null) {
      return support.catalogToolError(
          TOOL_UPDATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "elementId is required",
          null);
    }
    if (patchJson == null || patchJson.isBlank()) {
      return support.catalogToolError(
          TOOL_UPDATE_ELEMENT,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "patchJson is required",
          "Pass a JSON object with name and/or properties.");
    }

    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL_UPDATE_ELEMENT,
        trimmedChainId,
        "elementId=" + trimmedElementId);
    try {
      Map<String, Object> patch = parsePatchJson(patchJson);
      // Catalog PatchElementRequest defaults properties to {} and MapStruct replaces the
      // whole map. Merge with the current element so partial patches keep mandatory defaults.
      Map<String, Object> mergedPatch =
          mergeWithCurrentElement(trimmedChainId, trimmedElementId, patch);
      HttpMethodRestrictCatalogShape.applyToPatchBody(mergedPatch);
      CatalogRestClient.ChainDiffDto diff =
          catalogRestClient.updateElement(trimmedChainId, trimmedElementId, mergedPatch);
      support.clearUpdateElementCatalogFailures(trimmedChainId, trimmedElementId);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("elementId", trimmedElementId);
      data.put("updatedElements", summarizeElementSummaries(diff != null ? diff.updatedElements() : null));
      String out = support.catalogToolSuccess(TOOL_UPDATE_ELEMENT, data);
      support.logCatalogToolDone(TOOL_UPDATE_ELEMENT, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_UPDATE_ELEMENT, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (CaptureValidationException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      String out =
          support.catalogToolError(
              TOOL_UPDATE_ELEMENT, CatalogToolResult.CODE_INVALID_ARGUMENT, e.getMessage());
      support.logCatalogToolDone(TOOL_UPDATE_ELEMENT, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_UPDATE_ELEMENT, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      LOG.warnf(
          e,
          "updateElement failed for chainId=%s elementId=%s",
          trimmedChainId,
          trimmedElementId);
      if (CatalogToolSupport.isRetryableCatalogClientError(e)) {
        int failures =
            support.recordUpdateElementCatalogFailure(trimmedChainId, trimmedElementId);
        if (failures >= CatalogToolSupport.MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS) {
          String detail = CatalogRestSupport.describeExceptionForToolResult(e);
          String message =
              CatalogToolSupport.UPDATE_ELEMENT_REPAIR_EXHAUSTED_HINT
                  + " Last error: "
                  + detail;
          ToolTraceLog.logToolFailed(
              LOG,
              TOOL_UPDATE_ELEMENT,
              trimmedChainId,
              System.currentTimeMillis() - startMs,
              e);
          // Streaming tool loops ignore soft tool-error JSON and keep retrying. This marker stops
          // the stream the same way plan-path capture tools stop after repeated validation failure.
          throw new CaptureValidationException(message);
        }
      }
      String out = support.catalogToolError(TOOL_UPDATE_ELEMENT, e);
      support.logCatalogToolDone(TOOL_UPDATE_ELEMENT, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_UPDATE_ELEMENT, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    }
  }

  @Tool("List elements on a chain as compact JSON [{id,name,type}].")
  public String listElements(@P("Catalog chain id") String chainId) {
    String trimmedChainId = CatalogStrings.blankToNull(chainId);
    if (trimmedChainId == null) {
      return support.catalogToolError(
          TOOL_LIST_ELEMENTS,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "chainId is required",
          null);
    }

    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL_LIST_ELEMENTS, trimmedChainId, null);
    try {
      List<CatalogElementResponseDto> roots = catalogRestClient.listElements(trimmedChainId);
      List<Map<String, String>> compact = new ArrayList<>();
      collectCompactElements(roots, compact);
      String out = support.catalogToolSuccess(TOOL_LIST_ELEMENTS, compact);
      support.logCatalogToolDone(TOOL_LIST_ELEMENTS, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_LIST_ELEMENTS, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      LOG.warnf(e, "listElements failed for chainId=%s", trimmedChainId);
      String out = support.catalogToolError(TOOL_LIST_ELEMENTS, e);
      support.logCatalogToolDone(TOOL_LIST_ELEMENTS, out);
      ToolTraceLog.logToolComplete(
          LOG, TOOL_LIST_ELEMENTS, trimmedChainId, System.currentTimeMillis() - startMs, out);
      return out;
    }
  }

  private Map<String, Object> parsePatchJson(String patchJson) {
    String trimmed = patchJson.trim();
    if (!trimmed.startsWith("{")) {
      throw new IllegalArgumentException("patchJson must be a JSON object");
    }
    try {
      Map<String, Object> patch =
          objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
      if (patch == null || patch.isEmpty()) {
        throw new IllegalArgumentException("patchJson must not be empty");
      }
      return patch;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("patchJson must be valid JSON: " + e.getMessage());
    }
  }

  private Map<String, Object> mergeWithCurrentElement(
      String chainId, String elementId, Map<String, Object> patch) {
    CatalogElementResponseDto current = catalogRestClient.getElement(chainId, elementId);
    Map<String, Object> merged = new LinkedHashMap<>();
    if (current != null && current.name != null && !current.name.isBlank()) {
      merged.put("name", current.name);
    }
    if (current != null && current.description != null) {
      merged.put("description", current.description);
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    if (current != null && current.properties != null) {
      properties.putAll(current.properties);
    }
    Object patchProperties = patch.get("properties");
    if (patchProperties instanceof Map<?, ?> patchMap) {
      for (Map.Entry<?, ?> entry : patchMap.entrySet()) {
        if (entry.getKey() != null) {
          properties.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
    }
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if ("properties".equals(entry.getKey())) {
        continue;
      }
      merged.put(entry.getKey(), entry.getValue());
    }
    merged.put("properties", properties);
    return merged;
  }

  private static String extractPrimaryCreatedElementId(
      CatalogRestClient.ChainDiffDto diff, String expectedType) {
    if (diff == null || diff.createdElements() == null || diff.createdElements().isEmpty()) {
      throw new IllegalStateException("createElement did not return created elements");
    }
    int primaryIdx = indexOfPrimaryCreated(diff.createdElements(), expectedType);
    if (primaryIdx < 0) {
      throw new IllegalStateException(
          "createElement response has no element of type " + expectedType);
    }
    String elementId = diff.createdElements().get(primaryIdx).id();
    if (elementId == null || elementId.isBlank()) {
      throw new IllegalStateException("createElement returned empty element id");
    }
    return elementId;
  }

  private static int indexOfPrimaryCreated(
      List<CatalogRestClient.ElementSummaryDto> created, String expectedType) {
    String want = expectedType != null ? expectedType.trim() : "";
    for (int i = 0; i < created.size(); i++) {
      CatalogRestClient.ElementSummaryDto element = created.get(i);
      if (element == null) {
        continue;
      }
      String type = element.type() != null ? element.type().trim() : "";
      if (want.equals(type)) {
        return i;
      }
    }
    return -1;
  }

  private static List<Map<String, String>> summarizeCreatedElements(
      CatalogRestClient.ChainDiffDto diff) {
    return summarizeElementSummaries(diff != null ? diff.createdElements() : null);
  }

  private static List<Map<String, String>> summarizeElementSummaries(
      List<CatalogRestClient.ElementSummaryDto> elements) {
    if (elements == null) {
      return List.of();
    }
    List<Map<String, String>> out = new ArrayList<>();
    for (CatalogRestClient.ElementSummaryDto element : elements) {
      if (element == null) {
        continue;
      }
      Map<String, String> row = new LinkedHashMap<>();
      row.put("id", Objects.toString(element.id(), ""));
      row.put("type", Objects.toString(element.type(), ""));
      out.add(row);
    }
    return out;
  }

  private static void collectCompactElements(
      List<CatalogElementResponseDto> elements, List<Map<String, String>> out) {
    if (elements == null) {
      return;
    }
    for (CatalogElementResponseDto element : elements) {
      if (element == null) {
        continue;
      }
      Map<String, String> row = new LinkedHashMap<>();
      row.put("id", element.id != null ? element.id : "");
      row.put("name", element.name != null ? element.name : "");
      row.put("type", element.type != null ? element.type : "");
      out.add(row);
      collectCompactElements(element.children, out);
    }
  }
}
