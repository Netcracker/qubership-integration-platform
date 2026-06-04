package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainPlanBindingPreflightServiceTest {

  @Test
  void enrichOperationBindingsWritesEnrichedProperties() {
    CatalogOperationBindingResolver resolver = mock(CatalogOperationBindingResolver.class);
    ChainPlanBindingPreflightService preflight = new ChainPlanBindingPreflightService(resolver);

    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-1");
    node.setType("service-call");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("integrationSpecificationId", "model-1");
    props.put("integrationOperationId", "model-1-op");
    node.setExpectedProperties(props);

    Map<String, Object> enriched =
        Map.of(
            "integrationSpecificationId",
            "model-1",
            "integrationOperationId",
            "model-1-op",
            "integrationOperationMethod",
            "GET",
            "integrationOperationPath",
            "/pet");
    when(resolver.enrichForProperties(any()))
        .thenReturn(CatalogOperationBindingEnrichResult.unchanged(enriched));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanBindingPreflightService.PreflightResult result =
        preflight.enrichOperationBindings(plan);

    assertTrue(result.openItems().isEmpty());
    assertEquals("GET", node.getExpectedProperties().get("integrationOperationMethod"));
    assertEquals("/pet", node.getExpectedProperties().get("integrationOperationPath"));
  }

  @Test
  void enrichOperationBindingsCollectsUnresolvedOpenItems() {
    CatalogOperationBindingResolver resolver = mock(CatalogOperationBindingResolver.class);
    ChainPlanBindingPreflightService preflight = new ChainPlanBindingPreflightService(resolver);

    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-1");
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSpecificationId",
            "model-x",
            "integrationOperationId",
            "missing-op"));
    when(resolver.enrichForProperties(any()))
        .thenReturn(
            CatalogOperationBindingEnrichResult.unresolved(
                node.getExpectedProperties(), "integrationOperationId not found in catalog"));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanBindingPreflightService.PreflightResult result =
        preflight.enrichOperationBindings(plan);

    assertEquals(1, result.openItems().size());
    assertEquals(PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED, result.openItems().get(0).kind());
    assertTrue(result.openItems().get(0).message().contains("not found"));
  }

  @Test
  void enrichOperationBindingsSkipsCatalogEnrichForMisplacedApiHubOperationId() {
    CatalogOperationBindingResolver resolver = mock(CatalogOperationBindingResolver.class);
    ChainPlanBindingPreflightService preflight = new ChainPlanBindingPreflightService(resolver);

    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-import");
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationOperationId",
            "serviceCatalogManagement-v4-serviceCatalogManagement-v4-serviceSpecification-_id_-get"));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanBindingPreflightService.PreflightResult result =
        preflight.enrichOperationBindings(plan);

    assertTrue(result.openItems().isEmpty());
  }

  @Test
  void enrichOperationBindingsSkipsCatalogEnrichForCompleteApiHubImportRow() {
    CatalogOperationBindingResolver resolver = mock(CatalogOperationBindingResolver.class);
    ChainPlanBindingPreflightService preflight = new ChainPlanBindingPreflightService(resolver);

    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-import");
    node.setType("service-call");
    node.setImportRequired(true);
    node.setApiHubPackageId("S.ActProv.SvcCat");
    node.setApiHubVersion("2026.1@1");
    node.setApiHubOperationId("op-get");
    node.setCatalogSystemName("Service Catalog");
    node.setCatalogSystemType("EXTERNAL");

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanBindingPreflightService.PreflightResult result =
        preflight.enrichOperationBindings(plan);

    assertTrue(result.openItems().isEmpty());
  }
}
