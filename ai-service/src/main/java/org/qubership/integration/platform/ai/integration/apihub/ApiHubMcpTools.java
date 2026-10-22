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
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;

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
 * <p>Adapted from ai-service: import candidate recording removed (PlanningDiaryService
 * not yet ported). The blockApiHubWhenCatalogPathIncomplete guard is also removed —
 * pipeline step context handles ordering instead.
 */
@ApplicationScoped
public class ApiHubMcpTools {

  private static final Logger LOG = Logger.getLogger(ApiHubMcpTools.class);

  static final String MCP_TOOL_SEARCH = "search_api_operations";
  static final String MCP_TOOL_GET_SPEC = "get_api_operation_specification";
  static final String MCP_TOOL_GET_DOCUMENT = "get_document";
  static final String MCP_RESOURCE_PACKAGES_LIST = "mcp://api-packages-list";

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

  private volatile String sessionId;

  private final ApiHubMcpClient apiHubMcpClient;
  private final ObjectMapper objectMapper;
  private final AppConfig appConfig;
  private final ConversationApiHubCache conversationApiHubCache;
  private final RequirementDraftStore draftStore;
  private final ApiHubSearchAuthorizations searchAuthorizations;

  @Inject
  ApiHubMcpTools(
      @RestClient ApiHubMcpClient apiHubMcpClient,
      ObjectMapper objectMapper,
      AppConfig appConfig,
      ConversationApiHubCache conversationApiHubCache,
      RequirementDraftStore draftStore,
      ApiHubSearchAuthorizations searchAuthorizations) {
    this.apiHubMcpClient = apiHubMcpClient;
    this.objectMapper = objectMapper;
    this.appConfig = appConfig;
    this.conversationApiHubCache = conversationApiHubCache;
    this.draftStore = draftStore;
    this.searchAuthorizations = searchAuthorizations;
  }

  /** Test / no-cache constructor. */
  ApiHubMcpTools(
      @RestClient ApiHubMcpClient apiHubMcpClient, ObjectMapper objectMapper, AppConfig appConfig) {
    this(apiHubMcpClient, objectMapper, appConfig, null, null, null);
  }

  /** Test constructor with conversation API Hub cache. */
  ApiHubMcpTools(
      @RestClient ApiHubMcpClient apiHubMcpClient,
      ObjectMapper objectMapper,
      AppConfig appConfig,
      ConversationApiHubCache conversationApiHubCache) {
    this(apiHubMcpClient, objectMapper, appConfig, conversationApiHubCache, null, null);
  }

  void logApiHubMcpConnectivityOnStartup(@Observes StartupEvent event) {
    if (!appConfig.apihub().probeOnStartup()) {
      return;
    }
    String base = appConfig.apihub().baseUrl();
    if (base == null || base.isBlank()) {
      LOG.warn("APIHUB MCP startup probe skipped: qip.ai.apihub.base-url is empty");
      return;
    }
    String basePreview = AiTraceLog.previewOneLine(ApiHubMcpUrlHelper.normalizeTrailingSlash(base), 220);
    try {
      ApiHubResult init = post(null, buildInitializeRequest());
      if (init.status >= 400) {
        LOG.warnf("APIHUB MCP startup probe: initialize failed, httpStatus=%s, baseUrl=%s, bodyPreview=%s",
            init.status, basePreview, preview(init.body, 400));
        return;
      }
      if (init.sessionId == null || init.sessionId.isBlank()) {
        LOG.warnf("APIHUB MCP startup probe: initialize HTTP %s but no %s header, baseUrl=%s",
            init.status, MCP_SESSION_HEADER, basePreview);
        return;
      }
      sessionId = init.sessionId;
      String sessionLog = init.sessionId.length() <= 12 ? init.sessionId : init.sessionId.substring(0, 8) + "…";

      ApiHubResult listed = post(init.sessionId, buildJsonRpcRequest(MCP_METHOD_TOOLS_LIST, Map.of()));
      ToolsListProbeResult toolsList = parseToolsListProbeResult(listed);
      List<String> remoteNames = toolsList.remoteNames();

      boolean hasSearch = remoteNames.contains(MCP_TOOL_SEARCH);
      boolean hasSpec = remoteNames.contains(MCP_TOOL_GET_SPEC);
      boolean hasDocument = remoteNames.contains(MCP_TOOL_GET_DOCUMENT);

      if (toolsList.error() != null) {
        LOG.warnf("APIHUB MCP startup probe: connected, baseUrl=%s, session=%s, tools/list issue: %s",
            basePreview, sessionLog, toolsList.error());
      } else if (!hasSearch || !hasSpec || !hasDocument) {
        LOG.warnf("APIHUB MCP startup probe: connected, baseUrl=%s, session=%s, remoteTools=%s",
            basePreview, sessionLog, remoteNames);
      } else {
        LOG.infof("APIHUB MCP startup probe: OK, baseUrl=%s, session=%s, tools=%s,%s,%s",
            basePreview, sessionLog, MCP_TOOL_SEARCH, MCP_TOOL_GET_SPEC, MCP_TOOL_GET_DOCUMENT);
      }
    } catch (Exception e) {
      LOG.warnf(e, "APIHUB MCP startup probe failed, baseUrl=%s: %s", basePreview, e.getMessage());
    }
  }

  @Tool("Search APIHUB for API operations. apiType: rest, graphql, or asyncapi (required). "
      + "Lexical full-text search — use operation title phrases with spaces (e.g. Retrieve "
      + "serviceSpecification), not bare resource names or operationId strings. Use limit 100. "
      + "When packageId is known, pass group=packageId and do NOT pass release on search. "
      + "Returns operationId, packageId, version, documentId.")
  public String searchApiOperations(
      @P("Search query; use operation title phrases with spaces") String query,
      @P("API type: rest, graphql, or asyncapi") String apiType,
      @P("Optional release version e.g. 2025.2") String release,
      @P("Optional page number, default 0") Integer page,
      @P("Optional result limit 10-100, default 100") Integer limit,
      @P("Optional packageId filter (group)") String group) {
    String refusal = refuseUnauthorizedSearch();
    if (refusal != null) {
      return refusal;
    }
    String resolvedGroup = CatalogStrings.blankToNull(group);
    if (resolvedGroup == null) {
      resolvedGroup = resolvePackageIdFromDraft();
    }
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("query", query);
    args.put("apiType", apiType);
    putIfPresent(args, "release", release);
    putIfPresent(args, "group", resolvedGroup);
    args.put("page", page != null ? page : 0);
    args.put("limit", limit != null ? limit : DEFAULT_SEARCH_LIMIT);
    String result = invokeToolSafely(MCP_TOOL_SEARCH, args, "searching APIHUB");
    if (isEmptySearchResult(result) && resolvedGroup != null) {
      String fallbackQuery = packageScopedFallbackQuery(resolvedGroup);
      if (fallbackQuery != null && !fallbackQuery.equalsIgnoreCase(query)) {
        LOG.infof(
            "searchApiOperations empty for query=%s group=%s; retrying with fallbackQuery=%s",
            AiTraceLog.preview(query, 60), resolvedGroup, fallbackQuery);
        Map<String, Object> retryArgs = new LinkedHashMap<>(args);
        retryArgs.put("query", fallbackQuery);
        result = invokeToolSafely(MCP_TOOL_SEARCH, retryArgs, "searching APIHUB");
      }
    }
    rememberClearSearchHit(result, apiType, resolvedGroup);
    return result;
  }

  /**
   * Refuses a search that no authorization covers, or null when one query was spent.
   *
   * <p>The tool description cannot enforce this. An authorization exists only where the server
   * established that the local catalog cannot answer, so its absence means the search would be
   * improvised.
   */
  private String refuseUnauthorizedSearch() {
    if (searchAuthorizations == null) {
      return null;
    }
    String conversationId = ToolSession.resolveConversationId();
    if (searchAuthorizations.consume(conversationId).isPresent()) {
      return null;
    }
    LOG.warnf("searchApiOperations refused: no API Hub authorization conversationId=%s", conversationId);
    return "API Hub search is not authorized for this conversation, or its query budget is spent."
        + " Resolve the service call with resolveApiOperation first: a confirmed catalog miss"
        + " authorizes one scoped search. No search was performed.";
  }

  @Tool("Get operation-level specification from APIHUB (OpenAPI or AsyncAPI). "
      + "apiType: rest or asyncapi only. Use version from the search hit or listApiHubPackages.")
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
    String result = invokeToolSafely(MCP_TOOL_GET_SPEC, args, "retrieving operation spec from APIHUB");
    rememberImportCandidate(packageId, version, operationId, null, apiType, result);
    return result;
  }

  @Tool("List APIHUB packages with release versions (MCP resource mcp://api-packages-list). "
      + "Use when searchApiOperations returns no hits or package/version is unknown.")
  public String listApiHubPackages() {
    try {
      logMcpInvocation(MCP_METHOD_RESOURCES_READ, Map.of("uri", MCP_RESOURCE_PACKAGES_LIST));
      JsonNode response = readResourceWithSession(MCP_RESOURCE_PACKAGES_LIST);
      String result = extractAndLogToolResult(MCP_RESOURCE_PACKAGES_LIST, response);
      rememberPackageCandidateFromList(result);
      return result;
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP resources/read failed: %s", e.getMessage());
      return mapApiHubToolFailure("listing APIHUB packages", e);
    }
  }

  @Tool("Get full source API specification document from APIHUB by slug. "
      + "apiType: rest, graphql, or asyncapi. Pass documentId from searchApiOperations as slug.")
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
    return invokeToolSafely(MCP_TOOL_GET_DOCUMENT, args, "retrieving document from APIHUB");
  }

  public ApiHubDocumentPayload fetchApiHubDocument(
      String packageId, String version, String slug, String apiType) {
    String type = apiType == null || apiType.isBlank() ? "rest" : apiType.trim().toLowerCase(Locale.ROOT);
    String resolvedSlug = slug == null || slug.isBlank() ? "api" : slug.trim();
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
      String fileName = format.toLowerCase(Locale.ROOT).contains("yaml") ? "openapi.yaml" : "openapi.json";
      if (!documentData.isMissingNode() && !documentData.isNull()) {
        if (documentData.isTextual()) {
          return new ApiHubDocumentPayload(documentData.asText().getBytes(StandardCharsets.UTF_8), fileName);
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
          return new ApiHubDocumentPayload(fromPayload.asText().getBytes(StandardCharsets.UTF_8), fileName);
        }
        return new ApiHubDocumentPayload(objectMapper.writeValueAsBytes(fromPayload), fileName);
      }
      byte[] content = parsed.isTextual()
          ? parsed.asText().getBytes(StandardCharsets.UTF_8)
          : objectMapper.writeValueAsBytes(parsed);
      return new ApiHubDocumentPayload(content, fileName);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to fetch APIHUB document slug=" + resolvedSlug + " packageId=" + packageId + ": " + e.getMessage(), e);
    }
  }

  public byte[] fetchOperationOpenApiJson(String packageId, String version, String operationId, String apiType) {
    String type = apiType == null || apiType.isBlank() ? "rest" : apiType.trim().toLowerCase(Locale.ROOT);
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
          "Failed to fetch OpenAPI from APIHUB for operationId=" + operationId + ": " + e.getMessage(), e);
    }
  }

  private String invokeToolSafely(String toolName, Map<String, Object> args, String userVerb) {
    try {
      logMcpInvocation(toolName, args);
      JsonNode response = invokeTool(toolName, args);
      return extractAndLogToolResult(toolName, response);
    } catch (Exception e) {
      LOG.errorf(e, "APIHUB MCP %s failed: %s", toolName, e.getMessage());
      return mapApiHubToolFailure(userVerb, e);
    }
  }

  private JsonNode invokeTool(String toolName, Map<String, Object> args) throws JsonProcessingException {
    JsonNode request = buildJsonRpcRequest(MCP_METHOD_TOOLS_CALL, Map.of("name", toolName, "arguments", args));
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
      if (second.status >= 400) throw toWebException(second);
      return parseJson(second.body);
    }
    if (first.status >= 400) throw toWebException(first);
    return parseJson(first.body);
  }

  private synchronized void ensureSession() {
    if (sessionId != null && !sessionId.isBlank()) return;
    ApiHubResult init = post(null, buildInitializeRequest());
    if (init.status >= 400) throw toWebException(init);
    if (init.sessionId == null || init.sessionId.isBlank()) {
      throw new IllegalStateException("APIHUB MCP initialize did not return Mcp-Session-Id header");
    }
    sessionId = init.sessionId;
  }

  private JsonNode buildInitializeRequest() {
    return buildJsonRpcRequest(MCP_METHOD_INITIALIZE, Map.of(
        "protocolVersion", "2024-11-05",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "qip-ai-assistant-2", "version", "1.0")));
  }

  private JsonNode buildJsonRpcRequest(String method, Object params) {
    return objectMapper.valueToTree(Map.of(
        "jsonrpc", JSONRPC_VERSION,
        "id", idPrefixForMethod(method) + UUID.randomUUID(),
        "method", method,
        "params", params));
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
    if (value != null && !value.isBlank()) args.put(key, value);
  }

  private ToolsListProbeResult parseToolsListProbeResult(ApiHubResult listed) {
    if (listed.status >= 400) {
      return new ToolsListProbeResult(List.of(), "httpStatus=" + listed.status + ", bodyPreview=" + preview(listed.body, 400));
    }
    try {
      JsonNode root = parseJson(listed.body);
      JsonNode rpcErr = root.path("error");
      if (!rpcErr.isMissingNode() && !rpcErr.isNull()) {
        return new ToolsListProbeResult(List.of(), AiTraceLog.previewOneLine(rpcErr.toString(), 400));
      }
      return new ToolsListProbeResult(parseRemoteToolNamesFromRoot(root), null);
    } catch (JsonProcessingException e) {
      return new ToolsListProbeResult(List.of(), "parse tools/list response: " + e.getMessage());
    }
  }

  private List<String> parseRemoteToolNamesFromRoot(JsonNode root) {
    List<String> names = new ArrayList<>();
    if (root == null || root.isMissingNode() || root.isNull()) return names;
    JsonNode tools = root.path("result").path("tools");
    if (!tools.isArray()) tools = root.path("tools");
    if (!tools.isArray()) return names;
    for (JsonNode t : tools) {
      String n = t.path("name").asText("");
      if (!n.isBlank()) names.add(n);
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
    return new WebApplicationException(
        "APIHUB MCP HTTP " + result.status + " body: " + preview(result.body, 500),
        Response.status(result.status).entity(result.body).build());
  }

  private boolean isInvalidSession(int status, String body) {
    return status == 404 && body != null && body.contains("Invalid session ID");
  }

  private String mapApiHubToolFailure(String userVerb, Exception e) {
    if (e instanceof WebApplicationException wae && wae.getResponse() != null) {
      int status = wae.getResponse().getStatus();
      if (status == 421) {
        return USER_ERROR_PREFIX + userVerb + " (HTTP 421): API Hub returned \"Requested unknown endpoint\". "
            + "Use MCP base URL ending with /mcp/ and header api-key. Technical detail: " + e.getMessage();
      }
      if (status == 404) {
        String body = wae.getResponse().getEntity() != null ? String.valueOf(wae.getResponse().getEntity()) : "";
        if (body.contains("Invalid session ID")) {
          return USER_ERROR_PREFIX + userVerb + ": API Hub MCP rejected session (Invalid session ID).";
        }
      }
    }
    return USER_ERROR_PREFIX + userVerb + ": " + e.getMessage();
  }

  private void logMcpInvocation(String toolName, Map<String, Object> args) {
    try {
      String json = objectMapper.writeValueAsString(args);
      ToolTraceLog.logToolInvoke(LOG, toolName, null, json);
    } catch (JsonProcessingException e) {
      ToolTraceLog.logToolInvoke(LOG, toolName, null, String.valueOf(args));
    }
  }

  private String extractAndLogToolResult(String toolName, JsonNode response) {
    String text = extractText(response, objectMapper);
    ToolTraceLog.logToolComplete(LOG, toolName, null, -1, text);
    return text;
  }

  static String extractText(JsonNode response, ObjectMapper mapper) {
    if (response == null || response.isMissingNode() || response.isNull()) return NO_RESULTS_MESSAGE;
    JsonNode errorNode = response.path("error");
    if (!errorNode.isMissingNode() && !errorNode.isNull()) {
      return "APIHUB MCP returned error: " + errorNode;
    }
    JsonNode result = response.path("result");
    if (result.isMissingNode() || result.isNull()) return NO_RESULTS_MESSAGE;

    String fromContent = extractTextContentBlocks(result.path("content"));
    if (!fromContent.isEmpty()) return fromContent;

    String fromResourceContents = extractResourceContents(result.path("contents"));
    if (!fromResourceContents.isEmpty()) return fromResourceContents;

    JsonNode structured = result.path("structuredContent");
    if (!structured.isMissingNode() && !structured.isNull()) {
      try { return mapper.writeValueAsString(structured); } catch (JsonProcessingException e) { return structured.toString(); }
    }
    if (result.isObject() && !result.isEmpty()) {
      try { return mapper.writeValueAsString(result); } catch (JsonProcessingException e) { return result.toString(); }
    }
    return NO_RESULTS_MESSAGE;
  }

  private static String extractTextContentBlocks(JsonNode content) {
    if (!content.isArray() || content.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (JsonNode c : content) {
      if ("text".equals(c.path("type").asText()) && c.hasNonNull("text")) {
        if (!out.isEmpty()) out.append('\n');
        out.append(c.path("text").asText());
      }
    }
    return out.toString().trim();
  }

  private static String extractResourceContents(JsonNode contents) {
    if (!contents.isArray() || contents.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (JsonNode c : contents) {
      if (c.hasNonNull("text")) {
        if (!out.isEmpty()) out.append('\n');
        out.append(c.path("text").asText());
      }
    }
    return out.toString().trim();
  }

  private void rememberImportCandidate(
      String packageId,
      String version,
      String operationId,
      String documentId,
      String apiType,
      String toolResult) {
    if (conversationApiHubCache == null || isToolError(toolResult)) {
      return;
    }
    conversationApiHubCache.rememberCandidate(
        resolveConversationId(),
        new ApiHubRequirementRefs(
            packageId, version, operationId, documentId, apiType, null, null));
  }

  private void rememberClearSearchHit(String toolResult, String apiType, String group) {
    if (conversationApiHubCache == null || isToolError(toolResult)) {
      return;
    }
    // Single clear hit, else primary GET-by-id in one package (even when group was omitted),
    // else package-level document candidate so IMPORT_PENDING is still reachable.
    ApiHubRequirementRefs parsed =
        ApiHubSearchHitParser.parseImportCandidate(toolResult, apiType, group);
    if (parsed == null) {
      return;
    }
    conversationApiHubCache.rememberCandidate(resolveConversationId(), parsed);
  }

  /**
   * When the user already named a package id and listApiHubPackages confirms it, seed a package-
   * level import candidate (documentId=api) so gather can recover even if the agent skips capture.
   */
  private void rememberPackageCandidateFromList(String packagesJson) {
    if (conversationApiHubCache == null || isToolError(packagesJson)) {
      return;
    }
    String conversationId = resolveConversationId();
    String packageId = resolvePackageIdFromDraft();
    if (conversationId == null || packageId == null) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(packagesJson);
      JsonNode packages = root.path("packages");
      if (!packages.isArray()) {
        return;
      }
      for (JsonNode pkg : packages) {
        if (!packageId.equals(CatalogStrings.blankToNull(pkg.path("packageId").asText(null)))) {
          continue;
        }
        JsonNode versions = pkg.path("versions");
        if (!versions.isArray() || versions.isEmpty()) {
          return;
        }
        String version = CatalogStrings.blankToNull(versions.get(0).path("version").asText(null));
        if (version == null) {
          return;
        }
        String packageName = CatalogStrings.blankToNull(pkg.path("name").asText(null));
        conversationApiHubCache.rememberCandidate(
            conversationId,
            new ApiHubRequirementRefs(
                packageId,
                version,
                null,
                ApiHubRequirementRefs.DEFAULT_DOCUMENT_SLUG,
                ApiHubRequirementRefs.DEFAULT_API_TYPE,
                packageName,
                null));
        LOG.infof(
            "listApiHubPackages: remembered package-level candidate conversationId=%s"
                + " packageId=%s version=%s",
            conversationId, packageId, version);
        return;
      }
    } catch (Exception e) {
      LOG.debugf(e, "listApiHubPackages: could not remember package candidate");
    }
  }

  private String resolvePackageIdFromDraft() {
    if (draftStore == null) {
      return null;
    }
    String conversationId = resolveConversationId();
    if (conversationId == null) {
      return null;
    }
    RequirementDraft draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return null;
    }
    if (draft.apiHubCandidate() != null
        && CatalogStrings.blankToNull(draft.apiHubCandidate().packageId()) != null) {
      return draft.apiHubCandidate().packageId();
    }
    return ApiHubPackageIdExtractor.extract(draft.assembledText());
  }

  private static String packageScopedFallbackQuery(String packageId) {
    // Lexical search often misses free-form goal text even with group= set. A short TMF-style
    // title verb scoped by packageId reliably returns operations for API Hub packages.
    if (CatalogStrings.blankToNull(packageId) == null) {
      return null;
    }
    return "Retrieve";
  }

  private static boolean isEmptySearchResult(String toolResult) {
    if (toolResult == null || toolResult.isBlank() || isToolError(toolResult)) {
      return true;
    }
    String trimmed = toolResult.trim();
    return trimmed.equals("{\"items\":[]}")
        || trimmed.equals("{\"operations\":[]}")
        || trimmed.equals("[]");
  }

  private static String resolveConversationId() {
    return org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
  }

  private static boolean isToolError(String toolResult) {
    if (toolResult == null || toolResult.isBlank()) {
      return true;
    }
    return toolResult.startsWith(USER_ERROR_PREFIX)
        || toolResult.startsWith("APIHUB MCP returned error:")
        || toolResult.equals(NO_RESULTS_MESSAGE);
  }

  private String preview(String body, int max) {
    if (body == null) return "";
    return body.length() <= max ? body : body.substring(0, max) + "…";
  }

  private record ApiHubResult(int status, String body, String sessionId) {}

  private record ToolsListProbeResult(List<String> remoteNames, String error) {}
}
