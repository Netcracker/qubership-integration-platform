package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorResourceLoader;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared logging, JSON, and result formatting for LangChain4j catalog {@code @Tool} beans. */
@ApplicationScoped
public class CatalogToolSupport {

  static final int MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS = 3;

  static final String UPDATE_ELEMENT_REPAIR_EXHAUSTED_HINT =
      "Repair budget exhausted after repeated updateElement failures (catalog HTTP 400 retries or"
          + " local schema validation). Call requestConfirmation with a compact summary (element"
          + " type, last catalog errorMessage or validation text, patch keys attempted) and"
          + " suggested options such as Cancel,Provide missing value.";

  static final String TOOL_STEP_UPDATE_ELEMENT = "updateElement";

  static final String TOOL_STEP_CREATE_ELEMENTS_BY_JSON = "createElementsByJson";

  private static final Logger LOG = Logger.getLogger(CatalogToolSupport.class);

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Inject ObjectMapper objectMapper;

  @Inject CatalogDescriptorResourceLoader catalogDescriptorResourceLoader;

  void logCatalogToolDone(String toolName, String outcome) {
    LOG.infof(
        "Catalog tool completed [%s]: resultPreview=%s",
        toolName, AiTraceLog.preview(outcome, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS));
  }

  static boolean isBlankOrEmptyPlanJson(String batchJson) {
    if (batchJson == null || batchJson.isBlank()) {
      return true;
    }
    String t = batchJson.trim();
    return "{}".equals(t) || "null".equalsIgnoreCase(t);
  }

  boolean isToolError(String out) {
    return CatalogToolResult.isError(objectMapper, out);
  }

  String catalogToolSuccess(String tool, Object data) {
    return CatalogToolResult.success(objectMapper, tool, data);
  }

  String catalogToolSuccess(String tool, String message, Object data) {
    return CatalogToolResult.successMessage(objectMapper, tool, message, data);
  }

  String catalogToolError(String tool, CatalogToolResult.ErrorSpec spec) {
    return CatalogToolResult.error(
        objectMapper, tool, spec.code(), spec.message(), spec.hint());
  }

  String catalogToolError(String tool, String code, String message) {
    return CatalogToolResult.error(objectMapper, tool, code, message);
  }

  String catalogToolError(String tool, String code, String message, String hint) {
    return CatalogToolResult.error(objectMapper, tool, code, message, hint);
  }

  String catalogToolError(String toolStep, Exception e) {
    WebApplicationException wae = CatalogRestSupport.findWebApplicationException(e);
    if (wae != null && wae.getResponse() != null) {
      Response response = wae.getResponse();
      int status = response.getStatus();
      String bodySnippet = CatalogRestSupport.readResponseBodySnippet(response);
      if (bodySnippet != null && !bodySnippet.isBlank()) {
        LOG.warnf(
            e, "Catalog tool %s failed: HTTP %d, response body: %s", toolStep, status, bodySnippet);
      } else {
        LOG.warnf(e, "Catalog tool %s failed: HTTP %d", toolStep, status);
      }
      return catalogToolError(
          toolStep,
          CatalogToolResult.CODE_CATALOG_HTTP_ERROR,
          CatalogRestSupport.describeExceptionForToolResult(e));
    }
    LOG.warnf(e, "Catalog tool %s failed", toolStep);
    return catalogToolError(
        toolStep,
        CatalogToolResult.CODE_TOOL_EXECUTION_ERROR,
        CatalogRestSupport.describeExceptionForToolResult(e));
  }

  Optional<String> tryReuseAutoShellChildId(
      String chainId, String elementType, String parentElementId) {
    String pid = CatalogStrings.blankToNull(parentElementId);
    String typeKey = elementType != null ? elementType.trim() : "";
    if (pid == null || typeKey.isEmpty()) {
      return Optional.empty();
    }
    String cid = chainId != null ? chainId.trim() : "";
    if (cid.isEmpty()) {
      return Optional.empty();
    }
    CatalogElementResponseDto parentDto;
    try {
      parentDto = catalogRestClient.getElement(cid, pid);
    } catch (Exception e) {
      LOG.debugf(e, "createElement: failed to load parent %s for rebound, will POST", pid);
      return Optional.empty();
    }
    if (parentDto == null || parentDto.type == null || parentDto.type.isBlank()) {
      return Optional.empty();
    }
    Optional<CatalogElementDescriptorModel> parentDescOpt =
        catalogDescriptorResourceLoader.load(parentDto.type.trim());
    if (parentDescOpt.isEmpty()) {
      return Optional.empty();
    }
    CatalogElementDescriptorModel parentDesc = parentDescOpt.get();
    if (!Boolean.TRUE.equals(parentDesc.getContainer())) {
      return Optional.empty();
    }
    Map<String, String> allowed = parentDesc.getAllowedChildren();
    if (allowed == null || !allowed.containsKey(typeKey)) {
      return Optional.empty();
    }
    String mult = allowed.get(typeKey);
    if (mult == null || mult.isBlank()) {
      return Optional.empty();
    }
    String m = mult.trim();
    if (!"one".equalsIgnoreCase(m) && !"one-or-zero".equalsIgnoreCase(m)) {
      return Optional.empty();
    }
    List<CatalogElementResponseDto> children = parentDto.children;
    if (children == null || children.isEmpty()) {
      return Optional.empty();
    }
    List<CatalogElementResponseDto> matches =
        children.stream()
            .filter(ch -> ch != null && typeKey.equals(ch.type != null ? ch.type.trim() : null))
            .sorted(Comparator.comparing(ch -> ch.id != null ? ch.id : ""))
            .toList();
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.size() > 1) {
      LOG.warnf(
          "createElement rebound: %d children of type %s under parent %s (descriptor"
              + " multiplicity=%s); picking smallest id",
          matches.size(), typeKey, pid, m);
    }
    CatalogElementResponseDto pick = matches.get(0);
    if (pick.id == null || pick.id.isBlank()) {
      return Optional.empty();
    }
    String shellId = pick.id.trim();
    LOG.infof(
        "createElement auto-shell rebound: parentId=%s parentType=%s childType=%s shellId=%s"
            + " multiplicity=%s",
        pid, parentDto.type, typeKey, shellId, m);
    return Optional.of(shellId);
  }

  String toJson(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return obj.toString();
    }
  }
}
