package org.qubership.integration.platform.ai.integration.apihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiHubMcpToolsTest {

  @Mock ApiHubMcpClient apiHubMcpClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConversationPlanningDiaryService planningDiaryService =
      new ConversationPlanningDiaryService();
  private ApiHubMcpTools tools;

  @BeforeEach
  void setUp() {
    tools =
        new ApiHubMcpTools(
            apiHubMcpClient,
            objectMapper,
            mock(AppConfig.class),
            mock(org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidateRecorder.class),
            planningDiaryService);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(ChatMdc.SCENARIO_TYPE);
  }

  @Test
  void searchApiOperationsBuildsCanonicalToolsCall() {
    stubMcpSessionAndToolResponse("{\"items\":[]}");

    tools.searchApiOperations("Get_Order", "rest", "2025.2", 1, 50, "pkg-1");

    JsonNode request = capturePostedJsonRpc();
    assertEquals("tools/call", request.path("method").asText());
    JsonNode args = request.path("params").path("arguments");
    assertEquals("search_api_operations", request.path("params").path("name").asText());
    assertEquals("Get_Order", args.path("query").asText());
    assertEquals("rest", args.path("apiType").asText());
    assertEquals("2025.2", args.path("release").asText());
    assertEquals(1, args.path("page").asInt());
    assertEquals(50, args.path("limit").asInt());
    assertEquals("pkg-1", args.path("group").asText());
  }

  @Test
  void searchApiOperationsBlockedWhenCatalogCandidateWasNotProbed() {
    String conversationId = "conv-catalog-first";
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.CREATE_CHAIN_PLAN.name());
    planningDiaryService.recordCatalogSystemsFound(
        conversationId,
        "Service Catalog Management",
        java.util.List.of(
            new CatalogRestClient.SystemDto(
                "1dcba94b-9cec-477e-9e5b-4e24363d6a25",
                "Service Catalog Management",
                "INTERNAL",
                "http")));

    String out =
        tools.searchApiOperations(
            "retrieveServiceSpecification", "rest", null, 0, 100, "S.ActProv.SvcCat");

    assertTrue(out.contains("APIHub lookup blocked"), out);
    assertTrue(out.contains("getApiSpecifications"), out);
    verifyNoInteractions(apiHubMcpClient);
  }

  @Test
  void getApiOperationSpecificationBuildsCanonicalToolsCall() {
    stubMcpSessionAndToolResponse("{\"operationData\":{}}");

    tools.getApiOperationSpecification("op-1", "pkg-1", "1.0.0", "asyncapi");

    JsonNode request = capturePostedJsonRpc();
    assertEquals("get_api_operation_specification", request.path("params").path("name").asText());
    JsonNode args = request.path("params").path("arguments");
    assertEquals("op-1", args.path("operationId").asText());
    assertEquals("asyncapi", args.path("apiType").asText());
  }

  @Test
  void listApiHubPackagesBuildsResourcesRead() throws Exception {
    stubMcpSessionAndResourceResponse("{\"packages\":[]}");

    tools.listApiHubPackages();

    JsonNode request = capturePostedJsonRpc();
    assertEquals("resources/read", request.path("method").asText());
    assertEquals(
        ApiHubMcpTools.MCP_RESOURCE_PACKAGES_LIST, request.path("params").path("uri").asText());
  }

  @Test
  void getApiHubDocumentBuildsCanonicalToolsCall() {
    stubMcpSessionAndToolResponse("{\"documentData\":{}}");

    tools.getApiHubDocument("pkg-1", "2.0.0", "spec-slug", "graphql");

    JsonNode request = capturePostedJsonRpc();
    assertEquals("get_document", request.path("params").path("name").asText());
    JsonNode args = request.path("params").path("arguments");
    assertEquals("spec-slug", args.path("slug").asText());
    assertEquals("graphql", args.path("apiType").asText());
  }

  @Test
  void extractTextStructuredContentWhenNoTextBlocks() throws Exception {
    String json =
        """
        {"jsonrpc":"2.0","id":"1","result":{"structuredContent":{"items":[{"operationId":"x"}]}}}
        """;
    JsonNode root = objectMapper.readTree(json);
    String text = ApiHubMcpTools.extractText(root, objectMapper);
    assertTrue(text.contains("operationId"));
    assertTrue(text.contains("x"));
  }

  @Test
  void extractTextResourceContents() throws Exception {
    String json =
        """
        {"jsonrpc":"2.0","id":"1","result":{"contents":[{"uri":"api-packages-list","text":"{\\"packages\\":[]}"}]}}
        """;
    JsonNode root = objectMapper.readTree(json);
    String text = ApiHubMcpTools.extractText(root, objectMapper);
    assertTrue(text.contains("packages"));
  }

  private void stubMcpSessionAndToolResponse(String structuredPayload) {
    String initBody =
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
    String toolBody =
        "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"structuredContent\":"
            + structuredPayload
            + "}}";

    when(apiHubMcpClient.post(isNull(), any()))
        .thenReturn(httpResponse(200, initBody, "session-abc"));
    when(apiHubMcpClient.post(any(), any()))
        .thenReturn(httpResponse(200, toolBody, "session-abc"));
  }

  private void stubMcpSessionAndResourceResponse(String resourceText) throws Exception {
    String initBody =
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
    String resourceBody =
        "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"contents\":[{\"uri\":\"api-packages-list\",\"mimeType\":\"application/json\",\"text\":"
            + objectMapper.writeValueAsString(resourceText)
            + "}]}}";

    when(apiHubMcpClient.post(isNull(), any()))
        .thenReturn(httpResponse(200, initBody, "session-abc"));
    when(apiHubMcpClient.post(any(), any()))
        .thenReturn(httpResponse(200, resourceBody, "session-abc"));
  }

  private JsonNode capturePostedJsonRpc() {
    ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
    verify(apiHubMcpClient, atLeastOnce()).post(any(), captor.capture());
    return captor.getAllValues().get(captor.getAllValues().size() - 1);
  }

  private static Response httpResponse(int status, String body, String sessionId) {
    Response.ResponseBuilder builder = Response.status(status).entity(body);
    if (sessionId != null) {
      builder.header("Mcp-Session-Id", sessionId);
    }
    return builder.build();
  }

}
