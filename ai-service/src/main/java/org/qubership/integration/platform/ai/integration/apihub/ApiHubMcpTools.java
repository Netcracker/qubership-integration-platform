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
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidateRecorder;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

  static final String MCP_TOOL_SEARCH = "search_api_operations";
  static final String MCP_TOOL_GET_SPEC = "get_api_operation_specification";
  static final String MCP_TOOL_GET_DOCUMENT = "get_document";
  static final String MCP_RESOURCE_PACKAGES_LIST = "api-packages-list";

  private static final String LEGACY_TOOL_SEARCH = "search_rest_api_operations";
  private static final String LEGACY_TOOL_GET_SPEC = "get_rest_api_operations_specification";

  private static final String MCP_METHOD_INITIALIZE = "initialize";
  private static final String MCP_METHOD_TOOLS_LIST = "tools/list";
  private static final String MCP_METHOD_TOOLS_CALL = "tools/call";
  private static final String MCP_METHOD_RESOURCES_READ = "resources/read";
  private static final String JSONRPC_VERSION = "2.0";
  private static final String MCP_SESSION_HEADER = "Mcp-Session-Id";
  private static final String USER_ERROR_PREFIX = "Error ";
  private static final String NO_RESULTS_MESSAGE = "No results returned from APIHUB.";
  private static final int DEFAULT_SEARCH_LIMIT = 100;

  /** MCP session is created lazily via initialize and reused across requests. */
  private volatile String sessionId;

  private final ApiHubMcpClient apiHubMcpClient;
  private final ObjectMapper objectMapper;
  private final AppConfig appConfig;
  private final ApiHubImportCandidateRecorder importCandidateRecorder;
  private final ConversationPlanningDiaryService planningDiaryService;

  @Inject
  ApiHubMcpTools(
      @RestClient ApiHubMcpClient apiHubMcpClient,
      ObjectMapper objectMapper,
      AppConfig appConfig,
      ApiHubImportCandidateRecorder importCandidateRecorder,
      ConversationPlanningDiaryService planningDiaryService) {
    this.apiHubMcpClient = apiHubMcpClient;
    this.objectMapper = objectMapper;
    this.appConfig = appConfig;
    this.importCandidateRecorder = importCandidateRecorder;
    this.planningDiaryService = planningDiaryService;
  }

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
      ApiHubResult init = post(null, buildInitializeRequest("probe-init-"));
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

      ApiHubResult listed =
          post(init.sessionId, buildJsonRpcRequest(MCP_METHOD_TOOLS_LIST, Map.of()));
      ToolsListProbeResult toolsList = parseToolsListProbeResult(listed);
      List<String> remoteNames = toolsList.remoteNames();
      String listError = toolsList.error();

      boolean hasSearch = remoteNames.contains(MCP_TOOL_SEARCH);
      boolean hasSpec = remoteNames.contains(MCP_TOOL_GET_SPEC);
      boolean hasDocument = remoteNames.contains(MCP_TOOL_GET_DOCUMENT);
      boolean hasLegacySearch = remoteNames.contains(LEGACY_TOOL_SEARCH);
      boolean hasLegacySpec = remoteNames.contains(LEGACY_TOOL_GET_SPEC);

      if (listError != null) {
        LOG.warnf(
            "APIHUB MCP startup probe: connected (initialize OK), baseUrl=%s, session=%s, "
                + "tools/list issue: %s",
            basePreview, sessionLog, listError);
      } else if (!hasSearch || !hasSpec || !hasDocument) {
        LOG.warnf(
            "APIHUB MCP startup probe: connected, baseUrl=%s, session=%s, remoteToolCount=%d, "
                + "remoteTools=%s, canonical=[%s=%s, %s=%s, %s=%s], legacy=[%s=%s, %s=%s]. "
                + "Upgrade API Hub MCP if canonical tools are missing.",
            basePreview,
            sessionLog,
            remoteNames.size(),
            remoteNames,
            MCP_TOOL_SEARCH,
            hasSearch,
            MCP_TOOL_GET_SPEC,
            hasSpec,
            MCP_TOOL_GET_DOCUMENT,
            hasDocument,
            LEGACY_TOOL_SEARCH,
            hasLegacySearch,
            LEGACY_TOOL_GET_SPEC,
            hasLegacySpec);
      } else {
        LOG.infof(
            "APIHUB MCP startup probe: OK, baseUrl=%s, session=%s, remoteToolCount=%d, "
                + "canonicalTools present: %s, %s, %s",
            basePreview,
            sessionLog,
            remoteNames.size(),
            MCP_TOOL_SEARCH,
            MCP_TOOL_GET_SPEC,
            MCP_TOOL_GET_DOCUMENT);
      }
    } catch (Exception e) {
      LOG.warnf(e, "APIHUB MCP startup probe failed, baseUrl=%s: %s", basePreview, e.getMessage());
    }
  }

  @Tool(
      "Search APIHUB for API operations. apiType: rest, graphql, or asyncapi (required). "
          + "Lexical full-text search — use operation title phrases with spaces (e.g. Retrieve "
          + "serviceSpecification), not bare resource names or operationId strings. Use limit 100. "
          + "When packageId is known, pass group=packageId and do NOT pass release on search "
          + "(release on search often returns empty; use IDS version only for "
          + "getApiOperationSpecification and plan apiHubVersion). If empty: listApiHubPackages, "
          + "retry with group and title-like queries without release, or getApiOperationSpecification "
          + "when IDS lists **APIHub:** ids. Returns operationId, packageId, version, documentId.")
  public String searchApiOperations(
      @P("Search query; use operation title phrases with spaces (e.g. Retrieve serviceSpecification)")
          String query,
      @P("API type: rest, graphql, or asyncapi") String apiType,
      @P("Optional release version e.g. 2025.2 or package-specific semver") String release,
      @P("Optional page number, default 0") Integer page,
      @P("Optional result limit 10-100, default 100") Integer limit,
      @P("Optional packageId filter (group)") String group) {

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("query", query);
    args.put("apiType", apiType);
    putIfPresent(args, "release", release);
    putIfPresent(args, "group", group);
    args.put("page", page != null ? page : 0);
    args.put("limit", limit != null ? limit : DEFAULT_SEARCH_LIMIT);

    return invokeToolSafely(MCP_TOOL_SEARCH, args, "searching APIHUB", true);
  }

  @Tool(
      "Get operation-level specification from APIHUB (OpenAPI or AsyncAPI). "
          + "apiType: rest or asyncapi only. CREATE_CHAIN_PLAN and IMPLEMENT_CHAIN may call this "
          + "for HTTP method and path after searchApiOperations, or skip search when IDS lists "
          + "**APIHub:** packageId, version, and operationId. Use version from the search hit or "
          + "listApiHubPackages — not an assumed calendar quarter. Returns operationData JSON.")
  public String getApiOperationSpecification(
      @P("Operation ID from search result") String operationId,
      @P("Package ID from search result") String packageId,
      @P("Version from search result") String version,
      @P("API type: rest or asyncapi") String apiType) {

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("operationId", operationId);
    args.put("packageId", packageId);
    args.put("version", version);
    args.put("apiType", apiType);

    return invokeToolSafely(MCP_TOOL_GET_SPEC, args, "retrieving operation spec from APIHUB", false);
  }

  /**
   * Fetches the full source API specification document from APIHUB ({@code get_document}) for
   * catalog file import. Use {@code documentId} from {@code searchApiOperations} as {@code slug}
   * (typically {@code api}).
   *
   * @throws IllegalStateException when MCP returns an error or no document payload
   */
  public ApiHubDocumentPayload fetchApiHubDocument(
      String packageId, String version, String slug, String apiType) {
    String type =
        apiType == null || apiType.isBlank()
            ? "rest"
            : apiType.trim().toLowerCase(Locale.ROOT);
    String resolvedSlug =
        slug == null || slug.isBlank() ? "api" : slug.trim();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("packageId", packageId.trim());
    args.put("version", version.trim());
    args.put("slug", resolvedSlug);
    args.put("apiType", type);
    try {
      JsonNode response = invokeTool(MCP_TOOL_GET_DOCUMENT, args);
      JsonNode errorNode = response.path("error");
      if (!errorNode.isMissingNode() && !errorNode.isNull()) {
        throw new IllegalStateException("APIHUB MCP error: " + errorNode);
      }
      JsonNode structured = response.path("result").path("structuredContent");
      JsonNode documentData = structured.path("documentData");
      String format = structured.path("format").asText("json").trim();
      String fileName =
          format.toLowerCase(Locale.ROOT).contains("yaml") ? "openapi.yaml" : "openapi.json";
      if (!documentData.isMissingNode() && !documentData.isNull()) {
        if (documentData.isTextual()) {
          return new ApiHubDocumentPayload(
              documentData.asText().getBytes(StandardCharsets.UTF_8), fileName);
        }
        return new ApiHubDocumentPayload(objectMapper.writeValueAsBytes(documentData), fileName);
      }
      String text = extractText(response, objectMapper);
      if (text.startsWith(USER_ERROR_PREFIX) || text.startsWith("APIHUB MCP returned error:")) {
        throw new IllegalStateException(text);
      }
      JsonNode parsed = objectMapper.readTree(text);
      JsonNode fromPayload = parsed.path("documentData");
      if (!fromPayload.isMissingNode() && !fromPayload.isNull()) {
        if (fromPayload.isTextual()) {
          return new ApiHubDocumentPayload(
              fromPayload.asText().getBytes(StandardCharsets.UTF_8), fileName);
        }
        return new ApiHubDocumentPayload(objectMapper.writeValueAsBytes(fromPayload), fileName);
      }
      byte[] content =
          parsed.isTextual()
              ? parsed.asText().getBytes(StandardCharsets.UTF_8)
              : objectMapper.writeValueAsBytes(parsed);
      return new ApiHubDocumentPayload(content, fileName);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to fetch APIHUB document slug="
              + resolvedSlug
              + " packageId="
              + packageId
              + " version="
              + version
              + ": "
              + e.getMessage(),
          e);
    }
  }

  /**
   * Fetches operation-level OpenAPI JSON from APIHUB for server-side catalog file import.
   *
   * @throws IllegalStateException when MCP returns an error or no operation payload
   */
  public byte[] fetchOperationOpenApiJson(
      String packageId, String version, String operationId, String apiType) {
    String type =
        apiType == null || apiType.isBlank()
            ? "rest"
            : apiType.trim().toLowerCase(Locale.ROOT);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("packageId", packageId.trim());
    args.put("version", version.trim());
    args.put("operationId", operationId.trim());
    args.put("apiType", type);
    try {
      JsonNode response = invokeTool(MCP_TOOL_GET_SPEC, args);
      JsonNode errorNode = response.path("error");
      if (!errorNode.isMissingNode() && !errorNode.isNull()) {
        throw new IllegalStateException("APIHUB MCP error: " + errorNode);
      }
      JsonNode operationData = response.path("result").path("structuredContent").path("operationData");
      if (!operationData.isMissingNode() && !operationData.isNull()) {
        return objectMapper.writeValueAsBytes(operationData);
      }
      String text = extractText(response, objectMapper);
      if (text.startsWith(USER_ERROR_PREFIX) || text.startsWith("APIHUB MCP returned error:")) {
        throw new IllegalStateException(text);
      }
      JsonNode parsed = objectMapper.readTree(text);
      JsonNode fromPayload = parsed.path("operationData");
      if (!fromPayload.isMissingNode() && !fromPayload.isNull()) {
        return objectMapper.writeValueAsBytes(fromPayload);
      }
      return parsed.isTextual()
          ? parsed.asText().getBytes(StandardCharsets.UTF_8)
          : objectMapper.writeValueAsBytes(parsed);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to fetch OpenAPI from APIHUB for operationId="
              + operationId
              + " packageId="
              + packageId
              + ": "
              + e.getMessage(),
          e);
    }
  }

  @Tool(
      "List APIHUB packages in the configured workspace with release versions (MCP resource "
          + "api-packages-list). Use when searchApiOperations returns no hits or package/version is "
          + "unknown. Pick packageId for search group and a real version from packages[].versions "
          + "for getApiOperationSpecification or import — not assumed calendar quarter.")
  public String listApiHubPackages() {
    try {
      String guard = blockApiHubWhenCatalogPathIncomplete(MCP_RESOURCE_PACKAGES_LIST);
      if (guard != null) {
        return guard;
      }
      logMcpInvocation(MCP_METHOD_RESOURCES_READ, Map.of("uri", MCP_RESOURCE_PACKAGES_LIST));
      JsonNode response = readResourceWithSession(MCP_RESOURCE_PACKAGES_LIST);
      String text = extractAndLogToolResult(MCP_RESOURCE_PACKAGES_LIST, response);
      importCandidateRecorder.recordFromPackagesListIfApplicable(text);
      return text;
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP resources/read failed: %s", e.getMessage());
      return mapApiHubToolFailure("listing APIHUB packages", e);
    }
  }

  @Tool(
      "Get full source API specification document from APIHUB by slug. "
          + "apiType: rest, graphql, or asyncapi. "
          + "Pass documentId from searchApiOperations as slug. "
          + "Required for GraphQL (operation-level spec tool does not support graphql). "
          + "For REST and AsyncAPI prefer getApiOperationSpecification when operationId is known.")
  public String getApiHubDocument(
      @P("Package ID from search result") String packageId,
      @P("Version from search result") String version,
      @P("Document slug (documentId from search)") String slug,
      @P("API type: rest, graphql, or asyncapi") String apiType) {

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("packageId", packageId);
    args.put("version", version);
    args.put("slug", slug);
    args.put("apiType", apiType);

    return invokeToolSafely(MCP_TOOL_GET_DOCUMENT, args, "retrieving document from APIHUB", false);
  }

  private String invokeToolSafely(
      String toolName, Map<String, Object> args, String userVerb, boolean recordImportCandidate) {
    try {
      String guard = blockApiHubWhenCatalogPathIncomplete(toolName);
      if (guard != null) {
        return guard;
      }
      logMcpInvocation(toolName, args);
      JsonNode response = invokeTool(toolName, args);
      String text = extractAndLogToolResult(toolName, response);
      if (recordImportCandidate) {
        importCandidateRecorder.recordFromSearchResultIfApplicable(text);
      }
      return text;
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP %s failed: %s", toolName, e.getMessage());
      return mapApiHubToolFailure(userVerb, e);
    }
  }

  private String invokeToolSafely(String toolName, Map<String, Object> args, String userVerb) {
    return invokeToolSafely(toolName, args, userVerb, false);
  }

  private String blockApiHubWhenCatalogPathIncomplete(String toolName) {
    if (!ScenarioType.CREATE_CHAIN_PLAN.name().equals(MDC.get(ChatMdc.SCENARIO_TYPE))) {
      return null;
    }
    String conversationId = MDC.get(ChatMdc.CONVERSATION_ID);
    if (conversationId == null || conversationId.isBlank()) {
      return null;
    }
    return planningDiaryService
        .apiHubBlockedByIncompleteCatalogPath(conversationId)
        .map(
            hint -> {
              LOG.infof(
                  "APIHUB tool %s blocked until catalog path is checked: conversationId=%s",
                  toolName,
                  conversationId);
              return USER_ERROR_PREFIX
                  + "APIHub lookup blocked: "
                  + hint
                  + " If catalog operations are missing after that, then use APIHub/import.";
            })
        .orElse(null);
  }

  private JsonNode invokeTool(String toolName, Map<String, Object> args)
      throws JsonProcessingException {
    JsonNode request =
        buildJsonRpcRequest(
            MCP_METHOD_TOOLS_CALL, Map.of("name", toolName, "arguments", args));
    return postJsonRpcWithSessionRetry(request);
  }

  private JsonNode readResourceWithSession(String uri) throws JsonProcessingException {
    JsonNode request = buildJsonRpcRequest(MCP_METHOD_RESOURCES_READ, Map.of("uri", uri));
    return postJsonRpcWithSessionRetry(request);
  }

  private JsonNode postJsonRpcWithSessionRetry(JsonNode request) throws JsonProcessingException {
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
    ApiHubResult init = post(null, buildInitializeRequest("init-"));
    if (init.status >= 400) {
      throw toWebException(init);
    }
    if (init.sessionId == null || init.sessionId.isBlank()) {
      throw new IllegalStateException("APIHUB MCP initialize did not return Mcp-Session-Id header");
    }
    sessionId = init.sessionId;
  }

  private JsonNode buildInitializeRequest(String idPrefix) {
    return buildJsonRpcRequest(
        MCP_METHOD_INITIALIZE,
        Map.of(
            "protocolVersion",
            "2024-11-05",
            "capabilities",
            Map.of(),
            "clientInfo",
            Map.of("name", "qip-ai-service", "version", "1.0")));
  }

  private JsonNode buildJsonRpcRequest(String method, Object params) {
    return objectMapper.valueToTree(
        Map.of(
            "jsonrpc",
            JSONRPC_VERSION,
            "id",
            idPrefixForMethod(method) + UUID.randomUUID(),
            "method",
            method,
            "params",
            params));
  }

  private static String idPrefixForMethod(String method) {
    return switch (method) {
      case MCP_METHOD_INITIALIZE -> "init-";
      case MCP_METHOD_TOOLS_LIST -> "tools-list-";
      case MCP_METHOD_TOOLS_CALL -> "tool-";
      case MCP_METHOD_RESOURCES_READ -> "resource-";
      default -> "rpc-";
    };
  }

  private static void putIfPresent(Map<String, Object> args, String key, String value) {
    if (value != null && !value.isBlank()) {
      args.put(key, value);
    }
  }

  private ToolsListProbeResult parseToolsListProbeResult(ApiHubResult listed) {
    if (listed.status >= 400) {
      return new ToolsListProbeResult(
          List.of(),
          "httpStatus=" + listed.status + ", bodyPreview=" + preview(listed.body, 400));
    }
    try {
      JsonNode root = parseJson(listed.body);
      JsonNode rpcErr = root.path("error");
      if (!rpcErr.isMissingNode() && !rpcErr.isNull()) {
        return new ToolsListProbeResult(
            List.of(), AiTraceLog.previewOneLine(rpcErr.toString(), 400));
      }
      return new ToolsListProbeResult(parseRemoteToolNamesFromRoot(root), null);
    } catch (JsonProcessingException e) {
      return new ToolsListProbeResult(
          List.of(), "parse tools/list response: " + e.getMessage());
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

  private ApiHubResult post(String mcpSessionId, JsonNode body) {
    try (Response response = apiHubMcpClient.post(mcpSessionId, body)) {
      int status = response.getStatus();
      String sessionHeader = response.getHeaderString(MCP_SESSION_HEADER);
      String rawBody = response.hasEntity() ? response.readEntity(String.class) : "";
      return new ApiHubResult(status, rawBody, sessionHeader);
    }
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
    String text = extractText(response, objectMapper);
    LOG.infof(
        "APIHUB MCP tool completed: tool=%s, textChars=%d, resultPreview=%s",
        toolName, text.length(), AiTraceLog.preview(text, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS));
    return text;
  }

  /**
   * Extracts LLM-visible text from an MCP JSON-RPC response (tools/call or resources/read).
   */
  static String extractText(JsonNode response, ObjectMapper mapper) {
    if (response == null || response.isMissingNode() || response.isNull()) {
      return NO_RESULTS_MESSAGE;
    }
    JsonNode errorNode = response.path("error");
    if (!errorNode.isMissingNode() && !errorNode.isNull()) {
      return "APIHUB MCP returned error: " + errorNode.toString();
    }

    JsonNode result = response.path("result");
    if (result.isMissingNode() || result.isNull()) {
      return NO_RESULTS_MESSAGE;
    }

    String fromToolContent = extractTextContentBlocks(result.path("content"));
    if (!fromToolContent.isEmpty()) {
      return fromToolContent;
    }

    String fromResourceContents = extractResourceContents(result.path("contents"));
    if (!fromResourceContents.isEmpty()) {
      return fromResourceContents;
    }

    JsonNode structured = result.path("structuredContent");
    if (!structured.isMissingNode() && !structured.isNull()) {
      try {
        return mapper.writeValueAsString(structured);
      } catch (JsonProcessingException e) {
        return structured.toString();
      }
    }

    if (result.isObject() && !result.isEmpty()) {
      try {
        return mapper.writeValueAsString(result);
      } catch (JsonProcessingException e) {
        return result.toString();
      }
    }

    return NO_RESULTS_MESSAGE;
  }

  private static String extractTextContentBlocks(JsonNode content) {
    if (!content.isArray() || content.isEmpty()) {
      return "";
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
    return out.toString().trim();
  }

  private static String extractResourceContents(JsonNode contents) {
    if (!contents.isArray() || contents.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode c : contents) {
      if (c.hasNonNull("text")) {
        if (!out.isEmpty()) {
          out.append('\n');
        }
        out.append(c.path("text").asText());
      }
    }
    return out.toString().trim();
  }

  private String preview(String body, int max) {
    if (body == null) {
      return "";
    }
    return body.length() <= max ? body : body.substring(0, max) + "…";
  }

  private record ApiHubResult(int status, String body, String sessionId) {}

  private record ToolsListProbeResult(List<String> remoteNames, String error) {}
}
