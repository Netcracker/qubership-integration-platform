package org.qubership.integration.platform.ai.integration.apihub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LangChain4j tool provider for APIHUB MCP operations.
 *
 * <p>These methods are exposed as {@code @Tool} to AI service agents so the LLM can call them when
 * it needs to look up or retrieve API specifications.
 */
@ApplicationScoped
public class ApiHubMcpTools {

  private static final Logger LOG = Logger.getLogger(ApiHubMcpTools.class);

  /** MCP remote tool name (must match APIHUB MCP server). */
  private static final String MCP_TOOL_SEARCH = "search_rest_api_operations";

  private static final String MCP_TOOL_GET_SPEC = "get_rest_api_operations_specification";
  private static final String MCP_METHOD_INITIALIZE = "initialize";
  private static final String MCP_METHOD_TOOLS_LIST = "tools/list";
  private static final String MCP_METHOD_TOOLS_CALL = "tools/call";
  private static final String JSONRPC_VERSION = "2.0";
  private static final String MCP_SESSION_HEADER = "Mcp-Session-Id";
  private static final String USER_ERROR_PREFIX = "Error ";
  private static final String NO_RESULTS_MESSAGE = "No results returned from APIHUB.";

  /** MCP session is created lazily via initialize and reused across requests. */
  private volatile String sessionId;

  @Inject @RestClient ApiHubMcpClient apiHubMcpClient;

  @Inject ObjectMapper objectMapper;

  @Inject AppConfig appConfig;

  /**
   * One-shot connectivity log: MCP initialize + tools/list. Controlled by {@code
   * qip.ai.apihub.probe-on-startup}. On success, caches {@link #sessionId} so the first real tool
   * call skips initialize.
   */
  void logApiHubMcpConnectivityOnStartup(@Observes StartupEvent event) {
    if (!appConfig.apihub().probeOnStartup()) {
      return;
    }
    String base = appConfig.apihub().baseUrl();
    if (base == null || base.isBlank()) {
      LOG.warn("APIHUB MCP startup probe skipped: qip.ai.apihub.base-url is empty");
      return;
    }
    String basePreview =
        AiTraceLog.previewOneLine(ApiHubMcpUrlHelper.normalizeTrailingSlash(base), 220);
    try {
      JsonNode initRequest =
          objectMapper.valueToTree(
              Map.of(
                  "jsonrpc",
                  JSONRPC_VERSION,
                  "id",
                  "probe-init-" + UUID.randomUUID(),
                  "method",
                  MCP_METHOD_INITIALIZE,
                  "params",
                  Map.of(
                      "protocolVersion", "2024-11-05",
                      "capabilities", Map.of(),
                      "clientInfo", Map.of("name", "qip-ai-service", "version", "1.0"))));
      ApiHubResult init = post(null, initRequest);
      if (init.status >= 400) {
        LOG.warnf(
            "APIHUB MCP startup probe: initialize failed, httpStatus=%s, baseUrl=%s,"
                + " bodyPreview=%s",
            init.status, basePreview, preview(init.body, 400));
        return;
      }
      if (init.sessionId == null || init.sessionId.isBlank()) {
        LOG.warnf(
            "APIHUB MCP startup probe: initialize HTTP %s but no %s header, baseUrl=%s",
            init.status, MCP_SESSION_HEADER, basePreview);
        return;
      }
      sessionId = init.sessionId;
      String sessionLog =
          init.sessionId.length() <= 12 ? init.sessionId : init.sessionId.substring(0, 8) + "…";

      JsonNode listRequest =
          objectMapper.valueToTree(
              Map.of(
                  "jsonrpc",
                  JSONRPC_VERSION,
                  "id",
                  "probe-tools-list-" + UUID.randomUUID(),
                  "method",
                  MCP_METHOD_TOOLS_LIST,
                  "params",
                  Map.of()));
      ApiHubResult listed = post(init.sessionId, listRequest);
      List<String> remoteNames = new ArrayList<>();
      String listError = null;
      if (listed.status >= 400) {
        listError = "httpStatus=" + listed.status + ", bodyPreview=" + preview(listed.body, 400);
      } else {
        try {
          JsonNode root = parseJson(listed.body);
          JsonNode rpcErr = root.path("error");
          if (!rpcErr.isMissingNode() && !rpcErr.isNull()) {
            listError = AiTraceLog.previewOneLine(rpcErr.toString(), 400);
          } else {
            remoteNames.addAll(parseRemoteToolNamesFromRoot(root));
          }
        } catch (JsonProcessingException e) {
          listError = "parse tools/list response: " + e.getMessage();
        }
      }

      boolean hasSearch = remoteNames.contains(MCP_TOOL_SEARCH);
      boolean hasSpec = remoteNames.contains(MCP_TOOL_GET_SPEC);
      if (listError != null) {
        LOG.warnf(
            "APIHUB MCP startup probe: connected (initialize OK), baseUrl=%s, session=%s, "
                + "tools/list issue: %s. LangChain tools use MCP names: %s, %s",
            basePreview, sessionLog, listError, MCP_TOOL_SEARCH, MCP_TOOL_GET_SPEC);
      } else {
        LOG.infof(
            "APIHUB MCP startup probe: OK, baseUrl=%s, session=%s, remoteToolCount=%d, "
                + "remoteTools=%s, expectedForAiService=[%s=%s, %s=%s]",
            basePreview,
            sessionLog,
            remoteNames.size(),
            remoteNames,
            MCP_TOOL_SEARCH,
            hasSearch,
            MCP_TOOL_GET_SPEC,
            hasSpec);
      }
    } catch (Exception e) {
      LOG.warnf(e, "APIHUB MCP startup probe failed, baseUrl=%s: %s", basePreview, e.getMessage());
    }
  }

  private List<String> parseRemoteToolNamesFromRoot(JsonNode root) {
    List<String> names = new ArrayList<>();
    if (root == null || root.isMissingNode() || root.isNull()) {
      return names;
    }
    JsonNode tools = root.path("result").path("tools");
    if (!tools.isArray()) {
      tools = root.path("tools");
    }
    if (!tools.isArray()) {
      return names;
    }
    for (JsonNode t : tools) {
      String n = t.path("name").asText("");
      if (!n.isBlank()) {
        names.add(n);
      }
    }
    return names;
  }

  /**
   * Searches APIHUB for REST API operations matching the query. Use underscores instead of spaces
   * in the query. Do not use TMF or CloudOSS prefixes.
   *
   * @param query operation name to search for (e.g. "Retrieve_Quote")
   * @param release optional API release version filter (e.g. "2025.2"); use "2025.2" if not
   *     specified
   * @return JSON string with matching operations: operationId, packageId, version
   */
  @Tool(
      "Search APIHUB for REST API operations matching the query. "
          + "Use underscores instead of spaces. Do not use TMF prefix. "
          + "Pass release as 'YYYY.Q' format. Returns operationId, packageId, version.")
  public String searchRestApiOperations(
      @P("Operation name query, use underscores for spaces") String query,
      @P("API release version e.g. 2025.2") String release) {

    Map<String, Object> args =
        release != null && !release.isBlank()
            ? Map.of("query", query, "release", release)
            : Map.of("query", query);

    try {
      logMcpInvocation(MCP_TOOL_SEARCH, args);
      JsonNode response = callToolWithSession(MCP_TOOL_SEARCH, args);
      return extractAndLogToolResult(MCP_TOOL_SEARCH, response);
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP %s failed: %s", MCP_TOOL_SEARCH, e.getMessage());
      return mapApiHubToolFailure("searching APIHUB", e);
    }
  }

  /**
   * Retrieves the full OpenAPI specification for a specific APIHUB operation. Call this ONLY after
   * searchRestApiOperations to get the exact operationId, packageId, version.
   *
   * @param operationId operation identifier from search result
   * @param packageId package identifier from search result
   * @param version version from search result
   * @return full OpenAPI spec JSON/YAML for the operation
   */
  @Tool(
      "Retrieve the full OpenAPI specification for a specific APIHUB operation. "
          + "Use ONLY after searchRestApiOperations. "
          + "Returns HTTP method, path, request parameters, response schemas.")
  public String getRestApiOperationSpecification(
      @P("Operation ID from search result") String operationId,
      @P("Package ID from search result") String packageId,
      @P("Version from search result") String version) {

    Map<String, Object> args =
        Map.of(
            "operationId", operationId,
            "packageId", packageId,
            "version", version);

    try {
      logMcpInvocation(MCP_TOOL_GET_SPEC, args);
      JsonNode response = callToolWithSession(MCP_TOOL_GET_SPEC, args);
      return extractAndLogToolResult(MCP_TOOL_GET_SPEC, response);
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP %s failed: %s", MCP_TOOL_GET_SPEC, e.getMessage());
      return mapApiHubToolFailure("retrieving spec from APIHUB", e);
    }
  }

  private JsonNode callToolWithSession(String toolName, Map<String, Object> args)
      throws JsonProcessingException {
    JsonNode request = buildToolsCallRequest(toolName, args);
    ensureSession();
    ApiHubResult first = post(sessionId, request);
    if (isInvalidSession(first.status, first.body)) {
      sessionId = null;
      ensureSession();
      ApiHubResult second = post(sessionId, request);
      if (second.status >= 400) {
        throw toWebException(second);
      }
      return parseJson(second.body);
    }
    if (first.status >= 400) {
      throw toWebException(first);
    }
    return parseJson(first.body);
  }

  private synchronized void ensureSession() {
    if (sessionId != null && !sessionId.isBlank()) {
      return;
    }
    JsonNode initRequest =
        objectMapper.valueToTree(
            Map.of(
                "jsonrpc",
                JSONRPC_VERSION,
                "id",
                "init-" + UUID.randomUUID(),
                "method",
                MCP_METHOD_INITIALIZE,
                "params",
                Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "qip-ai-service", "version", "1.0"))));
    ApiHubResult init = post(null, initRequest);
    if (init.status >= 400) {
      throw toWebException(init);
    }
    if (init.sessionId == null || init.sessionId.isBlank()) {
      throw new IllegalStateException("APIHUB MCP initialize did not return Mcp-Session-Id header");
    }
    sessionId = init.sessionId;
  }

  private ApiHubResult post(String mcpSessionId, JsonNode body) {
    try (Response response = apiHubMcpClient.post(mcpSessionId, body)) {
      int status = response.getStatus();
      String sessionHeader = response.getHeaderString(MCP_SESSION_HEADER);
      String rawBody = response.hasEntity() ? response.readEntity(String.class) : "";
      return new ApiHubResult(status, rawBody, sessionHeader);
    }
  }

  private JsonNode buildToolsCallRequest(String toolName, Map<String, Object> args) {
    return objectMapper.valueToTree(
        Map.of(
            "jsonrpc",
            JSONRPC_VERSION,
            "id",
            "tool-" + UUID.randomUUID(),
            "method",
            MCP_METHOD_TOOLS_CALL,
            "params",
            Map.of("name", toolName, "arguments", args)));
  }

  private JsonNode parseJson(String rawBody) throws JsonProcessingException {
    return objectMapper.readTree(rawBody == null ? "" : rawBody);
  }

  private WebApplicationException toWebException(ApiHubResult result) {
    String msg = "APIHUB MCP HTTP " + result.status + " body: " + preview(result.body, 500);
    return new WebApplicationException(
        msg, Response.status(result.status).entity(result.body).build());
  }

  private boolean isInvalidSession(int status, String body) {
    return status == 404 && body != null && body.contains("Invalid session ID");
  }

  private String mapApiHubToolFailure(String userVerb, Exception e) {
    if (e instanceof WebApplicationException wae) {
      var resp = wae.getResponse();
      if (resp != null && resp.getStatus() == 421) {
        return USER_ERROR_PREFIX
            + userVerb
            + " (HTTP 421): API Hub returned \"Requested unknown endpoint\". "
            + "Use MCP base URL ending with /mcp/ (e.g. https://apihub.netcracker.com/api/v1/mcp/) "
            + "and header api-key (set APIHUB_MCP_API_KEY or APIHUB_MCP_BEARER). "
            + "Technical detail: "
            + e.getMessage();
      }
      if (resp != null && resp.getStatus() == 404) {
        String body = resp.getEntity() != null ? String.valueOf(resp.getEntity()) : "";
        if (body.contains("Invalid session ID")) {
          return USER_ERROR_PREFIX
              + userVerb
              + ": API Hub MCP rejected session (Invalid session ID). "
              + "This is not 'API not found' — MCP session initialization/propagation failed.";
        }
      }
    }
    return USER_ERROR_PREFIX + userVerb + ": " + e.getMessage();
  }

  private void logMcpInvocation(String toolName, Map<String, Object> args) {
    try {
      String json = objectMapper.writeValueAsString(args);
      String compact = json.length() > 400 ? AiTraceLog.previewOneLine(json, 400) : json;
      LOG.infof("APIHUB MCP tool invoked: tool=%s, argumentsJson=%s", toolName, compact);
    } catch (JsonProcessingException e) {
      LOG.infof("APIHUB MCP tool invoked: tool=%s, arguments=%s", toolName, args);
    }
  }

  private String extractAndLogToolResult(String toolName, JsonNode response) {
    String text = extractText(response);
    LOG.infof(
        "APIHUB MCP tool completed: tool=%s, textChars=%d, resultPreview=%s",
        toolName, text.length(), AiTraceLog.preview(text, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS));
    return text;
  }

  private String extractText(JsonNode response) {
    if (response == null || response.isMissingNode() || response.isNull()) {
      return NO_RESULTS_MESSAGE;
    }
    JsonNode errorNode = response.path("error");
    if (!errorNode.isMissingNode() && !errorNode.isNull()) {
      return "APIHUB MCP returned error: " + errorNode.toString();
    }
    JsonNode content = response.path("result").path("content");
    if (!content.isArray()) {
      content = response.path("content");
    }
    if (!content.isArray() || content.isEmpty()) {
      return NO_RESULTS_MESSAGE;
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode c : content) {
      if ("text".equals(c.path("type").asText()) && c.hasNonNull("text")) {
        if (!out.isEmpty()) {
          out.append('\n');
        }
        out.append(c.path("text").asText());
      }
    }
    return out.isEmpty() ? NO_RESULTS_MESSAGE : out.toString().trim();
  }

  private String preview(String body, int max) {
    if (body == null) {
      return "";
    }
    return body.length() <= max ? body : body.substring(0, max) + "…";
  }

  private record ApiHubResult(int status, String body, String sessionId) {}
}
