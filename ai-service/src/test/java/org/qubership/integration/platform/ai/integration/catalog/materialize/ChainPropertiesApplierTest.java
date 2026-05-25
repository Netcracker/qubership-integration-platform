package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainPropertiesApplierTest {

  private ObjectMapper objectMapper;
  private CatalogPatchPreparationService patchService;
  private CatalogRestClient catalogRestClient;
  private DeterministicElementSchemaService schemaService;
  private CatalogOperationBindingResolver operationBindingResolver;
  private ChainPropertiesApplier applier;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    patchService = mock(CatalogPatchPreparationService.class);
    catalogRestClient = mock(CatalogRestClient.class);
    schemaService = mock(DeterministicElementSchemaService.class);
    operationBindingResolver = mock(CatalogOperationBindingResolver.class);
    when(schemaService.allowedPatchPropertyKeys(anyString())).thenReturn(Set.of());
    when(operationBindingResolver.enrichForProperties(any()))
        .thenAnswer(
            inv -> {
              ElementPlan node = inv.getArgument(0);
              Map<String, Object> props =
                  node.getExpectedProperties() != null ? node.getExpectedProperties() : Map.of();
              return CatalogOperationBindingEnrichResult.unchanged(props);
            });
    applier =
        new ChainPropertiesApplier(
            objectMapper, patchService, catalogRestClient, schemaService, operationBindingResolver);
  }

  @Test
  void unresolvedBindingSkipsPatchAndRecordsFailure() {
    ElementPlan node = new ElementPlan();
    node.setClientId("svc-1");
    node.setElementId("el-svc");
    node.setType("service-call");
    node.setExpectedProperties(Map.of("integrationOperationId", "operation-id-placeholder"));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    when(operationBindingResolver.enrichForProperties(node))
        .thenReturn(
            CatalogOperationBindingEnrichResult.unresolved(
                node.getExpectedProperties(), "integrationOperationId is a placeholder"));

    applier.applyPropertiesStage("chain-1", plan, report);

    assertFalse(report.stages.properties.ok);
    assertEquals(1, report.stages.properties.failures.size());
    assertTrue(report.stages.properties.failures.get(0).reason.contains("placeholder"));
    verify(patchService, never()).prepareUpdateElementBody(anyString(), anyString(), anyString());
  }

  @Test
  void emptyExpectedPropertiesSkipsPatch() {
    ElementPlan node = new ElementPlan();
    node.setClientId("c1");
    node.setElementId("el-1");
    node.setExpectedProperties(Map.of());
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    applier.applyPropertiesStage("chain-1", plan, report);

    assertTrue(report.stages.properties.ok);
    verify(patchService, never()).prepareUpdateElementBody(anyString(), anyString(), anyString());
    verify(catalogRestClient, never()).updateElement(anyString(), anyString(), any());
  }

  @Test
  void expectedPropertiesCallsPrepareAndUpdate() throws Exception {
    ElementPlan node = new ElementPlan();
    node.setClientId("c1");
    node.setElementId("el-1");
    node.setExpectedProperties(new LinkedHashMap<>(Map.of("httpMethod", "POST")));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    Map<String, Object> body =
        new LinkedHashMap<>(Map.of("properties", node.getExpectedProperties()));
    CatalogPatchPreparationService.PreparedUpdateBody prepared =
        new CatalogPatchPreparationService.PreparedUpdateBody(body, "http-trigger", true, true);
    String expectedPatch =
        objectMapper.writeValueAsString(Map.of("properties", node.getExpectedProperties()));
    when(patchService.prepareUpdateElementBody("chain-1", "el-1", expectedPatch))
        .thenReturn(prepared);

    applier.applyPropertiesStage("chain-1", plan, report);

    assertTrue(report.stages.properties.ok);
    verify(patchService).prepareUpdateElementBody("chain-1", "el-1", expectedPatch);
    verify(catalogRestClient).updateElement("chain-1", "el-1", body);
  }

  @Test
  void prepareThrowsIllegalArgumentRecordsMessage() {
    ElementPlan node = new ElementPlan();
    node.setClientId("c1");
    node.setElementId("el-1");
    node.setExpectedProperties(Map.of("x", 1));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    when(patchService.prepareUpdateElementBody(eq("chain-1"), eq("el-1"), anyString()))
        .thenThrow(new IllegalArgumentException("schema failed"));

    applier.applyPropertiesStage("chain-1", plan, report);

    assertFalse(report.stages.properties.ok);
    assertEquals(1, report.stages.properties.failures.size());
    assertEquals("c1", report.stages.properties.failures.get(0).clientId);
    assertEquals("el-1", report.stages.properties.failures.get(0).elementId);
    assertEquals("schema failed", report.stages.properties.failures.get(0).reason);
    verify(catalogRestClient, never()).updateElement(anyString(), anyString(), any());
  }

  @Test
  void updateThrowsOtherExceptionRecordsDescribedReason() {
    ElementPlan node = new ElementPlan();
    node.setClientId("c1");
    node.setElementId("el-1");
    node.setExpectedProperties(Map.of("k", "v"));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    Map<String, Object> body = Map.of("properties", node.getExpectedProperties());
    CatalogPatchPreparationService.PreparedUpdateBody prepared =
        new CatalogPatchPreparationService.PreparedUpdateBody(body, null, false, false);
    when(patchService.prepareUpdateElementBody(eq("chain-1"), eq("el-1"), anyString()))
        .thenReturn(prepared);
    doThrow(new RuntimeException("remote error"))
        .when(catalogRestClient)
        .updateElement("chain-1", "el-1", body);

    applier.applyPropertiesStage("chain-1", plan, report);

    assertFalse(report.stages.properties.ok);
    assertEquals(1, report.stages.properties.failures.size());
    assertEquals("remote error", report.stages.properties.failures.get(0).reason);
  }

  @Test
  void stripsUnknownKeysWhenSchemaAllowsSubset() throws Exception {
    ElementPlan node = new ElementPlan();
    node.setClientId("c1");
    node.setElementId("el-1");
    node.setType("http-trigger");
    node.setExpectedProperties(new LinkedHashMap<>(Map.of("httpMethod", "GET", "badKey", 1)));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    when(schemaService.allowedPatchPropertyKeys("http-trigger")).thenReturn(Set.of("httpMethod"));
    Map<String, Object> body = Map.of("properties", Map.of("httpMethod", "GET"));
    CatalogPatchPreparationService.PreparedUpdateBody prepared =
        new CatalogPatchPreparationService.PreparedUpdateBody(body, "http-trigger", true, true);
    String expectedPatch =
        objectMapper.writeValueAsString(Map.of("properties", Map.of("httpMethod", "GET")));
    when(patchService.prepareUpdateElementBody("chain-1", "el-1", expectedPatch))
        .thenReturn(prepared);

    applier.applyPropertiesStage("chain-1", plan, report);

    assertTrue(report.stages.properties.ok);
    assertEquals(1, report.skippedPropertyPatches.size());
    assertEquals(List.of("badKey"), report.skippedPropertyPatches.get(0).removedKeys);
    verify(patchService).prepareUpdateElementBody("chain-1", "el-1", expectedPatch);
  }

  @Test
  void missingElementIdOrClientIdSkipsWithoutPatch() {
    ElementPlan noCatalog = new ElementPlan();
    noCatalog.setClientId("a");
    noCatalog.setElementId(null);
    noCatalog.setExpectedProperties(Map.of("p", 1));
    ElementPlan noClient = new ElementPlan();
    noClient.setClientId("   ");
    noClient.setElementId("el-2");
    noClient.setExpectedProperties(Map.of("p", 2));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(noCatalog, noClient));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    applier.applyPropertiesStage("chain-1", plan, report);

    assertTrue(report.stages.properties.ok);
    verify(patchService, never()).prepareUpdateElementBody(anyString(), anyString(), anyString());
  }

  @Test
  void postOrderPatchesChildrenBeforeParent() {
    ElementPlan child = new ElementPlan();
    child.setClientId("child");
    child.setElementId("el-child");
    child.setExpectedProperties(Map.of("script", "x"));
    ElementPlan parent = new ElementPlan();
    parent.setClientId("parent");
    parent.setElementId("el-parent");
    parent.setExpectedProperties(Map.of("name", "p"));
    parent.setChildren(List.of(child));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(parent));
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    Map<String, Object> childBody = Map.of("properties", child.getExpectedProperties());
    Map<String, Object> parentBody = Map.of("properties", parent.getExpectedProperties());
    when(patchService.prepareUpdateElementBody(eq("c"), eq("el-child"), anyString()))
        .thenReturn(
            new CatalogPatchPreparationService.PreparedUpdateBody(
                childBody, "script", false, false));
    when(patchService.prepareUpdateElementBody(eq("c"), eq("el-parent"), anyString()))
        .thenReturn(
            new CatalogPatchPreparationService.PreparedUpdateBody(
                parentBody, "condition", false, false));

    applier.applyPropertiesStage("c", plan, report);

    ArgumentCaptor<String> elementIds = ArgumentCaptor.forClass(String.class);
    verify(catalogRestClient, times(2)).updateElement(eq("c"), elementIds.capture(), any());
    List<String> order = elementIds.getAllValues();
    assertEquals(List.of("el-child", "el-parent"), order);
  }
}
