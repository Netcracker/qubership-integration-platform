package org.qubership.integration.platform.ai.integration.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

@ExtendWith(MockitoExtension.class)
class CatalogElementWriteToolsTest {

  @Mock private CatalogRestClient catalogRestClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private CatalogToolSupport support;
  private CatalogElementWriteTools tools;

  @BeforeEach
  void setUp() throws Exception {
    support = new CatalogToolSupport();
    Field mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, objectMapper);
    tools = new CatalogElementWriteTools(catalogRestClient, support, objectMapper);
  }

  @Test
  void createElement_rejectsBlankChainId() throws Exception {
    String out = tools.createElement("  ", "http-trigger", null);

    assertFalse(CatalogToolResult.isSuccess(objectMapper, out));
    JsonNode error = objectMapper.readTree(out).path("error");
    assertEquals(CatalogToolResult.CODE_INVALID_ARGUMENT, error.path("code").asText());
  }

  @Test
  void createElement_rejectsBlankType() throws Exception {
    String out = tools.createElement("chain-1", " ", null);

    assertFalse(CatalogToolResult.isSuccess(objectMapper, out));
    JsonNode error = objectMapper.readTree(out).path("error");
    assertEquals(CatalogToolResult.CODE_INVALID_ARGUMENT, error.path("code").asText());
  }

  @Test
  void createElement_buildsRequestWithoutParentAndReturnsPrimaryId() throws Exception {
    CatalogRestClient.ChainDiffDto diff =
        new CatalogRestClient.ChainDiffDto(
            List.of(new CatalogRestClient.ElementSummaryDto("el-1", "http-trigger", Map.of())),
            List.of(),
            List.of());
    when(catalogRestClient.createElement(eq("chain-1"), any(CatalogCreateElementRequest.class)))
        .thenReturn(diff);

    String out = tools.createElement("chain-1", "http-trigger", null);

    ArgumentCaptor<CatalogCreateElementRequest> requestCaptor =
        ArgumentCaptor.forClass(CatalogCreateElementRequest.class);
    verify(catalogRestClient).createElement(eq("chain-1"), requestCaptor.capture());
    CatalogCreateElementRequest request = requestCaptor.getValue();
    assertEquals("http-trigger", request.type());
    assertEquals(null, request.parentElementId());
    assertEquals(null, request.swimlaneId());

    assertTrue(CatalogToolResult.isSuccess(objectMapper, out));
    JsonNode data = CatalogToolResult.dataOrNull(objectMapper, out);
    assertEquals("el-1", data.path("elementId").asText());
    assertEquals("el-1", data.path("createdElements").get(0).path("id").asText());
    assertEquals("http-trigger", data.path("createdElements").get(0).path("type").asText());
  }

  @Test
  void createElement_passesOptionalParentElementId() {
    CatalogRestClient.ChainDiffDto diff =
        new CatalogRestClient.ChainDiffDto(
            List.of(new CatalogRestClient.ElementSummaryDto("el-2", "script", Map.of())),
            List.of(),
            List.of());
    when(catalogRestClient.createElement(eq("chain-1"), any(CatalogCreateElementRequest.class)))
        .thenReturn(diff);

    tools.createElement("chain-1", "script", "parent-1");

    ArgumentCaptor<CatalogCreateElementRequest> requestCaptor =
        ArgumentCaptor.forClass(CatalogCreateElementRequest.class);
    verify(catalogRestClient).createElement(eq("chain-1"), requestCaptor.capture());
    assertEquals("script", requestCaptor.getValue().type());
    assertEquals("parent-1", requestCaptor.getValue().parentElementId());
    assertEquals(null, requestCaptor.getValue().swimlaneId());
  }

  @Test
  void updateElement_mergesPartialPropertiesWithCurrentElement() throws Exception {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.id = "el-1";
    current.name = "HTTP Trigger";
    current.properties =
        Map.of("accessControlType", "NONE", "connectTimeout", 120000, "externalRoute", true);
    when(catalogRestClient.getElement("chain-1", "el-1")).thenReturn(current);
    when(catalogRestClient.updateElement(eq("chain-1"), eq("el-1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    String patchJson =
        """
        {"name":"http-trigger-external-get","properties":{"contextPath":"/api"}}
        """;
    tools.updateElement("chain-1", "el-1", patchJson);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    Map<String, Object> patch = patchCaptor.getValue();
    assertEquals("http-trigger-external-get", patch.get("name"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) patch.get("properties");
    assertEquals("NONE", properties.get("accessControlType"));
    assertEquals(120000, properties.get("connectTimeout"));
    assertEquals(true, properties.get("externalRoute"));
    assertEquals("/api", properties.get("contextPath"));
  }

  @Test
  void updateElement_coercesHttpMethodRestrictStringToCatalogObject() throws Exception {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.id = "el-1";
    current.name = "HTTP Trigger";
    current.properties = Map.of("accessControlType", "NONE");
    when(catalogRestClient.getElement("chain-1", "el-1")).thenReturn(current);
    when(catalogRestClient.updateElement(eq("chain-1"), eq("el-1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    String patchJson =
        """
        {"properties":{"httpMethodRestrict":"GET","contextPath":"/api"}}
        """;
    tools.updateElement("chain-1", "el-1", patchJson);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(Map.of("httpMethods", List.of("GET")), properties.get("httpMethodRestrict"));
    assertEquals("/api", properties.get("contextPath"));
  }

  @Test
  void updateElement_nameOnlyKeepsExistingProperties() throws Exception {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.id = "el-1";
    current.name = "HTTP Trigger";
    current.properties = Map.of("accessControlType", "NONE");
    when(catalogRestClient.getElement("chain-1", "el-1")).thenReturn(current);
    when(catalogRestClient.updateElement(eq("chain-1"), eq("el-1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    tools.updateElement("chain-1", "el-1", "{\"name\":\"renamed\"}");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals("NONE", properties.get("accessControlType"));
    assertEquals("renamed", patchCaptor.getValue().get("name"));
  }

  @Test
  void updateElement_rejectsInvalidPatchJson() throws Exception {
    String out = tools.updateElement("chain-1", "el-1", "not-json");

    assertFalse(CatalogToolResult.isSuccess(objectMapper, out));
    JsonNode error = objectMapper.readTree(out).path("error");
    assertEquals(CatalogToolResult.CODE_INVALID_ARGUMENT, error.path("code").asText());
  }

  @Test
  void updateElement_stopsAfterRepeatedCatalogHttp400() {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.id = "el-1";
    current.name = "HTTP Trigger";
    current.properties = Map.of("accessControlType", "NONE");
    when(catalogRestClient.getElement("chain-1", "el-1")).thenReturn(current);
    Response response = mock(Response.class);
    when(response.getStatus()).thenReturn(400);
    when(response.getStatusInfo()).thenReturn(Response.Status.BAD_REQUEST);
    when(response.hasEntity()).thenReturn(false);
    WebApplicationException badRequest = new WebApplicationException(response);
    when(catalogRestClient.updateElement(eq("chain-1"), eq("el-1"), any()))
        .thenThrow(badRequest);

    String first = tools.updateElement("chain-1", "el-1", "{\"name\":\"a\"}");
    String second = tools.updateElement("chain-1", "el-1", "{\"name\":\"b\"}");
    assertFalse(CatalogToolResult.isSuccess(objectMapper, first));
    assertFalse(CatalogToolResult.isSuccess(objectMapper, second));

    CaptureValidationException exhausted =
        assertThrows(
            CaptureValidationException.class,
            () -> tools.updateElement("chain-1", "el-1", "{\"name\":\"c\"}"));
    assertTrue(exhausted.getMessage().contains("Repair budget exhausted"));
    verify(catalogRestClient, times(3)).updateElement(eq("chain-1"), eq("el-1"), any());
  }

  @Test
  void listElements_returnsCompactFlatJson() throws Exception {
    CatalogElementResponseDto child = new CatalogElementResponseDto();
    child.id = "child-1";
    child.name = "Script";
    child.type = "script";

    CatalogElementResponseDto root = new CatalogElementResponseDto();
    root.id = "root-1";
    root.name = "Trigger";
    root.type = "http-trigger";
    root.children = List.of(child);

    when(catalogRestClient.listElements("chain-1")).thenReturn(List.of(root));

    String out = tools.listElements("chain-1");

    assertTrue(CatalogToolResult.isSuccess(objectMapper, out));
    JsonNode data = CatalogToolResult.dataOrNull(objectMapper, out);
    assertTrue(data.isArray());
    assertEquals(2, data.size());
    assertEquals("root-1", data.get(0).path("id").asText());
    assertEquals("Trigger", data.get(0).path("name").asText());
    assertEquals("http-trigger", data.get(0).path("type").asText());
    assertEquals("child-1", data.get(1).path("id").asText());
  }
}
