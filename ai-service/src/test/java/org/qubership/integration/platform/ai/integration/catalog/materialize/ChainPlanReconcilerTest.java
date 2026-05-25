package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChainPlanReconcilerTest {

  private CatalogRestClient catalogRestClient;
  private ChainPlanReconciler reconciler;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    reconciler = new ChainPlanReconciler(catalogRestClient);
  }

  @Test
  void orphanCatalogRootWithoutParentListsOrphanRootType() {
    CatalogElementResponseDto orphan = new CatalogElementResponseDto();
    orphan.id = "orphan-el";
    orphan.type = "script";
    orphan.parentElementId = null;

    Map<String, CatalogElementResponseDto> byId = new HashMap<>();
    byId.put("orphan-el", orphan);

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    report.clientIds.put("plan-client", "plan-catalog-only");

    ChainImplementationPlan plan = new ChainImplementationPlan();
    ElementPlan root = new ElementPlan();
    root.setClientId("plan-client");
    root.setElementId("plan-catalog-only");
    plan.setElements(List.of(root));

    ChainPlanReconciler.ReconcileOutcome outcome =
        ChainPlanReconciler.buildOutcome(plan, report, byId, List.of());

    assertEquals(List.of("orphan-el:script"), outcome.orphanRootTypes());
  }

  @Test
  void parentCatalogDiffersFromPlanRecordsParentMismatch() {
    ElementPlan child = new ElementPlan();
    child.setClientId("c-child");
    child.setElementId("cat-child");
    child.setParentElementId("expected-parent");

    ElementPlan root = new ElementPlan();
    root.setClientId("c-root");
    root.setElementId("cat-root");
    root.setChildren(List.of(child));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(root));

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    CatalogElementResponseDto liveChild = new CatalogElementResponseDto();
    liveChild.id = "cat-child";
    liveChild.parentElementId = "catalog-parent-y";

    Map<String, CatalogElementResponseDto> byId = new HashMap<>();
    byId.put("cat-child", liveChild);

    ChainPlanReconciler.ReconcileOutcome outcome =
        ChainPlanReconciler.buildOutcome(plan, report, byId, List.of());

    assertEquals(1, outcome.parentMismatches().size());
    assertTrue(outcome.parentMismatches().get(0).contains("expectedParent=expected-parent"));
    assertTrue(outcome.parentMismatches().get(0).contains("catalogParent=catalog-parent-y"));
  }

  @Test
  void planNodeWithoutElementIdRecordsMissingPlanCatalogId() {
    ElementPlan node = new ElementPlan();
    node.setClientId("no-catalog");
    node.setElementId(null);

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    ChainPlanReconciler.ReconcileOutcome outcome =
        ChainPlanReconciler.buildOutcome(plan, report, Map.of(), List.of());

    assertEquals(List.of("no-catalog"), outcome.missingPlanCatalogIds());
  }

  @Test
  void planEdgeMissingInCatalogListsMissingDependencyPair() {
    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId("a");
    edge.setToClientId("b");

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(List.of(edge));

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    report.clientIds.put("a", "id-a");
    report.clientIds.put("b", "id-b");

    ChainPlanReconciler.ReconcileOutcome outcome =
        ChainPlanReconciler.buildOutcome(plan, report, Map.of(), List.of());

    assertEquals(List.of("a->b"), outcome.missingDependencyPairs());
  }

  @Test
  void listElementsFailsSkipsDependenciesAndSummary() {
    ChainImplementationPlan plan = new ChainImplementationPlan();
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    doThrow(new RuntimeException("list boom")).when(catalogRestClient).listElements("chain-x");

    reconciler.reconcileAndLog("chain-x", plan, report);

    verify(catalogRestClient).listElements("chain-x");
    verify(catalogRestClient, never()).listDependencies(anyString());
  }

  @Test
  void dependencyPresentDoesNotFlagMissingPair() {
    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId("a");
    edge.setToClientId("b");

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(List.of(edge));

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    report.clientIds.put("a", "id-a");
    report.clientIds.put("b", "id-b");

    CatalogDependencyDto dep = new CatalogDependencyDto();
    dep.from = "id-a";
    dep.to = "id-b";

    ChainPlanReconciler.ReconcileOutcome outcome =
        ChainPlanReconciler.buildOutcome(plan, report, new LinkedHashMap<>(), List.of(dep));

    assertTrue(outcome.missingDependencyPairs().isEmpty());
  }
}
