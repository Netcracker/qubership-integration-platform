package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

/** Shared logging, JSON, and result formatting for LangChain4j catalog {@code @Tool} beans. */
@ApplicationScoped
public class CatalogToolSupport {

  static final int MAX_UPDATE_ELEMENT_CATALOG_ATTEMPTS = 3;

  static final String UPDATE_ELEMENT_REPAIR_EXHAUSTED_HINT =
      "Repair budget exhausted after repeated updateElement failures (catalog HTTP 400 retries or"
          + " local schema validation). Stop tool calls. Summarize the last catalog errorMessage"
          + " and the patch keys you attempted; do not retry the same incomplete PATCH.";

  static final String TOOL_STEP_UPDATE_ELEMENT = "updateElement";

  static final String TOOL_STEP_CREATE_ELEMENTS_BY_JSON = "createElementsByJson";

  private static final Logger LOG = Logger.getLogger(CatalogToolSupport.class);

  private final ConcurrentHashMap<String, AtomicInteger> updateElementFailures =
      new ConcurrentHashMap<>();

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Inject ObjectMapper objectMapper;

  /**
   * Records one failed {@code updateElement} catalog call for {@code chainId}/{@code elementId}.
   *
   * @return the failure count after this record (including the current failure)
   */
  int recordUpdateElementCatalogFailure(String chainId, String elementId) {
    String key = updateElementFailureKey(chainId, elementId);
    return updateElementFailures
        .computeIfAbsent(key, ignored -> new AtomicInteger())
        .incrementAndGet();
  }

  void clearUpdateElementCatalogFailures(String chainId, String elementId) {
    updateElementFailures.remove(updateElementFailureKey(chainId, elementId));
  }

  static String updateElementFailureKey(String chainId, String elementId) {
    return chainId + "|" + elementId;
  }

  static boolean isRetryableCatalogClientError(Exception e) {
    WebApplicationException wae = CatalogRestSupport.findWebApplicationException(e);
    if (wae == null || wae.getResponse() == null) {
      return false;
    }
    int status = wae.getResponse().getStatus();
    return status == 400 || status == 422;
  }

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

  String toJson(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return obj.toString();
    }
  }
}
