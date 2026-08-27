package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class ChainPlanPropertiesMaterializerTest {

  @Mock private CatalogRestClient catalogRestClient;
  @Mock private DeterministicElementSchemaService schemaService;

  private ChainPlanPropertiesMaterializer materializer;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    materializer = new ChainPlanPropertiesMaterializer(catalogRestClient, schemaService, objectMapper);
    // Matches the real method's own fallback: an untyped or unknown property keeps its raw string.
    // lenient(): most cases here have no properties to coerce, so the stub goes unused there.
    lenient()
        .when(schemaService.coercePatchPropertyValue(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(2));
  }

  @Test
  void patchesNameForPropertyLessShellNodes() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("condition")).thenReturn(Set.of());
    when(schemaService.validateElementPatch(eq("condition"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(schemaService.allowedPatchPropertyKeys("else")).thenReturn(Set.of());
    when(schemaService.validateElementPatch(eq("else"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.getElement(anyString(), anyString()))
        .thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode("router", "condition", "Minute parity", null, null, List.of()),
                new ChainPlanNode(
                    "else-shell", "else", "Odd minute", "router", null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("router", "el-cond", "else-shell", "el-else"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(2, result.patchedCount());
    assertTrue(result.failedNodeIds().isEmpty());

    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-cond"), patchCaptor.capture());
    assertEquals("Minute parity", patchCaptor.getValue().get("name"));
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-else"), patchCaptor.capture());
    assertEquals("Odd minute", patchCaptor.getValue().get("name"));
  }

  @Test
  void patchesScriptPropertyForScriptNode() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    when(schemaService.validateElementPatch(eq("script"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "exchange.setProperty('x', 1)")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    assertTrue(result.failedNodeIds().isEmpty());

    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> sent = patchCaptor.getValue();
    assertEquals("exchange.setProperty('x', 1)", ((Map<String, Object>) sent.get("properties")).get("script"));
  }

  @Test
  void patchesWithSchemaDefaultsWhenValidationReturnsPatchWithDefaults() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "accessControlType"));
    when(schemaService.validateElementPatch(eq("http-trigger"), anyString()))
        .thenReturn(
            """
            {
              "valid": true,
              "defaultsApplied": ["accessControlType"],
              "patchWithDefaults": {
                "name": "HTTP Trigger",
                "properties": {
                  "contextPath": "/greetings",
                  "accessControlType": "NONE"
                }
              }
            }
            """);
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "http-trigger",
                    "HTTP Trigger",
                    null,
                    null,
                    List.of(new PlanProperty("contextPath", "/greetings")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals("NONE", props.get("accessControlType"));
    assertEquals("/greetings", props.get("contextPath"));
  }

  @Test
  void preservesPlacementFieldsWhenPatchWithDefaultsReplacesBody() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    when(schemaService.validateElementPatch(eq("script"), anyString()))
        .thenReturn(
            """
            {
              "valid": true,
              "patchWithDefaults": {
                "name": "Script",
                "properties": {
                  "script": "body"
                }
              }
            }
            """);
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.parentElementId = "try-1";
    current.swimlaneId = "lane-1";
    when(catalogRestClient.getElement("chain-1", "el-1")).thenReturn(current);
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "script",
                    "Script",
                    "try-node",
                    null,
                    List.of(new PlanProperty("script", "body")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    assertEquals("try-1", patchCaptor.getValue().get("parentElementId"));
    assertEquals("lane-1", patchCaptor.getValue().get("swimlaneId"));
  }

  @Test
  void parsesBooleanPropertyBeforeValidationAndPatch() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "externalRoute", "accessControlType"));
    when(schemaService.validateElementPatch(eq("http-trigger"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "http-trigger",
                    "HTTP Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("contextPath", "/greetings"),
                        new PlanProperty("externalRoute", "true"),
                        new PlanProperty("accessControlType", "NONE")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<String> validationCaptor = ArgumentCaptor.forClass(String.class);
    verify(schemaService).validateElementPatch(eq("http-trigger"), validationCaptor.capture());
    assertTrue(validationCaptor.getValue().contains("\"externalRoute\":true"));

    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(Boolean.TRUE, props.get("externalRoute"));
  }

  @Test
  void coercesHttpMethodRestrictStringToCatalogObjectBeforePatch() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "httpMethodRestrict"));
    when(schemaService.validateElementPatch(eq("http-trigger"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "http-trigger",
                    "HTTP Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("contextPath", "/api/v1/orders"),
                        new PlanProperty("httpMethodRestrict", "GET")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(Map.of("httpMethods", List.of("GET")), props.get("httpMethodRestrict"));
  }

  @Test
  void coercesANumericPropertyToItsSchemaTypeRatherThanAString() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("service-call")).thenReturn(Set.of("connectTimeout"));
    when(schemaService.coercePatchPropertyValue("service-call", "connectTimeout", "30000"))
        .thenReturn(30000L);
    when(schemaService.validateElementPatch(eq("service-call"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "service-call",
                    "Call",
                    null,
                    null,
                    List.of(new PlanProperty("connectTimeout", "30000")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(30000L, props.get("connectTimeout"));
  }

  @Test
  void sendsBranchPriorityAsAnIntegerBesideTheLiveParentElementId() throws Exception {
    // Reordering a catch-2 branch is an ordinary priority property patch. Two things make the
    // catalog renumber the sibling branches rather than silently leaving two branches on the same
    // priority: the value must arrive as a number, and the body must carry the element's current
    // parentElementId -- runtime-catalog only runs updateRelativeProperties when the body's parent
    // matches the live one. Verified live: dropping parentElementId leaves both branches at the
    // same priority.
    when(schemaService.allowedPatchPropertyKeys("catch-2")).thenReturn(Set.of("exception", "priority"));
    when(schemaService.coercePatchPropertyValue("catch-2", "priority", "0")).thenReturn(0L);
    when(schemaService.validateElementPatch(eq("catch-2"), anyString())).thenReturn("{\"valid\":true}");
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.name = "Catch";
    current.parentElementId = "el-try-catch";
    current.properties = Map.of("exception", "java.lang.Exception", "priority", 1);
    when(catalogRestClient.getElement("chain-1", "el-catch")).thenReturn(current);
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "catch-node",
                    "catch-2",
                    null,
                    "tcf-node",
                    null,
                    List.of(new PlanProperty("priority", "0")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("catch-node", "el-catch"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-catch"), patchCaptor.capture());
    assertEquals("el-try-catch", patchCaptor.getValue().get("parentElementId"));
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(0L, props.get("priority"));
  }

  @Test
  void doesNotPatchWhenValidationReturnsInvalid() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    when(schemaService.validateElementPatch(eq("script"), anyString()))
        .thenReturn("{\"valid\":false}");

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "body")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(0, result.patchedCount());
    assertEquals(List.of("n1"), result.failedNodeIds());
    verify(catalogRestClient, never()).updateElement(anyString(), anyString(), anyMap());
  }

  @Test
  void filtersUnknownPropertyKeys() {
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    when(schemaService.validateElementPatch(eq("script"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(
                        new PlanProperty("script", "ok"),
                        new PlanProperty("unknownKey", "drop-me")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
  }

  @Test
  void recordsFailedNodeWhenCatalogPatchThrows() {
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    when(schemaService.validateElementPatch(eq("script"), anyString()))
        .thenReturn("{\"valid\":true}");
    doThrow(new RuntimeException("catalog down"))
        .when(catalogRestClient)
        .updateElement(anyString(), anyString(), anyMap());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "body")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(0, result.patchedCount());
    assertEquals(List.of("n1"), result.failedNodeIds());
  }

  @Test
  void patchesIntegrationOperationIdFromPlanProperties() {
    String operationId = "4f2ee806-d504-446b-9d45-ef72320346da-swagger-1.0.7-getPetById";
    when(schemaService.allowedPatchPropertyKeys("service-call"))
        .thenReturn(Set.of("integrationOperationId"));
    when(schemaService.validateElementPatch(eq("service-call"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.getElement(anyString(), anyString()))
        .thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("pet-lookup", null),
            List.of(
                new ChainPlanNode(
                    "n1",
                    "service-call",
                    "Get Pet",
                    null,
                    null,
                    List.of(new PlanProperty("integrationOperationId", operationId)))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals(operationId, props.get("integrationOperationId"));
  }

  @Test
  void patchesServiceCallIdentityPropertiesOntoTheCatalogElement() {
    when(schemaService.allowedPatchPropertyKeys("service-call"))
        .thenReturn(
            Set.of(
                "integrationOperationId",
                "serviceCallId",
                "semanticRevisionId",
                "semanticNodeId"));
    when(schemaService.validateElementPatch(eq("service-call"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.getElement(anyString(), anyString()))
        .thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("pet-lookup", null),
            List.of(
                new ChainPlanNode(
                    "node-a",
                    "service-call",
                    "Get Order",
                    null,
                    null,
                    List.of(
                        new PlanProperty("serviceCallId", "call-a"),
                        new PlanProperty("semanticRevisionId", "rev-1"),
                        new PlanProperty("semanticNodeId", "node-a"),
                        new PlanProperty("integrationOperationId", "op-shared")))),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("node-a", "el-1"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-1"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> materializedProperties =
        (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals("call-a", materializedProperties.get("serviceCallId"));
    assertEquals("node-a", materializedProperties.get("semanticNodeId"));
    assertEquals("rev-1", materializedProperties.get("semanticRevisionId"));
    assertEquals("op-shared", materializedProperties.get("integrationOperationId"));
  }

  @Test
  void mergesCurrentCatalogPropertiesSoNameOnlyPatchKeepsDefaults() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("catch-2")).thenReturn(Set.of("exception", "priority"));
    when(schemaService.validateElementPatch(eq("catch-2"), anyString()))
        .thenReturn("{\"valid\":true}");
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.name = "Catch";
    current.properties =
        Map.of(
            "exception", "java.lang.Exception",
            "priority", 0);
    when(catalogRestClient.getElement("chain-1", "el-catch")).thenReturn(current);
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(new ChainPlanNode("catch-2", "catch-2", "Catch block", "tcf", null, List.of())),
            List.of());
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("catch-2", "el-catch"));

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result = materializer.apply(graph, map);

    assertEquals(1, result.patchedCount());
    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq("chain-1"), eq("el-catch"), patchCaptor.capture());
    assertEquals("Catch block", patchCaptor.getValue().get("name"));
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals("java.lang.Exception", props.get("exception"));
    assertEquals(0, props.get("priority"));
  }
}
