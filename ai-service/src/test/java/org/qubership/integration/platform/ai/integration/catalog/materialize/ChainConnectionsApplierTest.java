package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.CatalogDependencyKeys;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainConnectionsApplierTest {

  private CatalogRestClient catalogRestClient;
  private ChainConnectionsApplier applier;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    applier = new ChainConnectionsApplier(catalogRestClient);
  }

  @Test
  void emptyEdgesFinalizesOkWithoutCallingListDependencies() {
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of());
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();

    applier.applyConnectionsStage("c1", plan, report);

    assertTrue(report.stages.connections.ok);
    assertTrue(report.stages.connections.failures.isEmpty());
    verify(catalogRestClient, never()).listDependencies(any());
    verify(catalogRestClient, never()).createConnection(any(), any());
  }

  @Test
  void listDependenciesThrowsRecordsFailureAndDoesNotCreate() {
    ChainImplementationPlan plan = planWithEdge("a", "b");
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a", "b", "id-b");
    when(catalogRestClient.listDependencies("c1")).thenThrow(new RuntimeException("net down"));

    applier.applyConnectionsStage("c1", plan, report);

    assertFalse(report.stages.connections.ok);
    assertEquals(1, report.stages.connections.failures.size());
    assertTrue(
        report.stages.connections.failures.get(0).reason.startsWith("listDependencies failed:"));
    verify(catalogRestClient, never()).createConnection(any(), any());
  }

  @Test
  void edgeMissingEndpointReportsMissingIds() {
    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId("a");
    edge.setToClientId("   ");
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(new ArrayList<>(List.of(edge)));
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a", "b", "id-b");
    when(catalogRestClient.listDependencies("c1")).thenReturn(List.of());

    applier.applyConnectionsStage("c1", plan, report);

    assertFalse(report.stages.connections.ok);
    assertEquals(1, report.stages.connections.failures.size());
    assertEquals(
        "connection missing fromClientId or toClientId",
        report.stages.connections.failures.get(0).reason);
    verify(catalogRestClient, never()).createConnection(any(), any());
  }

  @Test
  void unknownClientIdReportsFailure() {
    ChainImplementationPlan plan = planWithEdge("a", "missing");
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a");
    when(catalogRestClient.listDependencies("c1")).thenReturn(List.of());

    applier.applyConnectionsStage("c1", plan, report);

    assertFalse(report.stages.connections.ok);
    assertEquals(1, report.stages.connections.failures.size());
    assertTrue(report.stages.connections.failures.get(0).reason.contains("unknown clientId"));
    verify(catalogRestClient, never()).createConnection(any(), any());
  }

  @Test
  void existingDependencySkipsPost() {
    ChainImplementationPlan plan = planWithEdge("a", "b");
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a", "b", "id-b");
    CatalogDependencyDto existing = new CatalogDependencyDto();
    existing.from = "id-a";
    existing.to = "id-b";
    when(catalogRestClient.listDependencies("c1")).thenReturn(List.of(existing));

    applier.applyConnectionsStage("c1", plan, report);

    assertTrue(report.stages.connections.ok);
    verify(catalogRestClient, never()).createConnection(any(), any());
  }

  @Test
  void duplicatePlanEdgesSinglePost() {
    ConnectionPlan e1 = new ConnectionPlan();
    e1.setFromClientId("a");
    e1.setToClientId("b");
    ConnectionPlan e2 = new ConnectionPlan();
    e2.setFromClientId("a");
    e2.setToClientId("b");
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(new ArrayList<>(List.of(e1, e2)));
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a", "b", "id-b");
    when(catalogRestClient.listDependencies("c1")).thenReturn(List.of());

    applier.applyConnectionsStage("c1", plan, report);

    verify(catalogRestClient, times(1))
        .createConnection(eq("c1"), Mockito.any(CatalogCreateDependencyRequest.class));
    assertTrue(report.stages.connections.ok);
  }

  @Test
  void createConnectionThrowsReportsDescribeMessage() {
    ChainImplementationPlan plan = planWithEdge("a", "b");
    CreateElementsByJsonReport report = reportWithClientIds("a", "id-a", "b", "id-b");
    when(catalogRestClient.listDependencies("c1")).thenReturn(List.of());
    doThrow(new RuntimeException("boom")).when(catalogRestClient).createConnection(eq("c1"), any());

    applier.applyConnectionsStage("c1", plan, report);

    assertFalse(report.stages.connections.ok);
    assertEquals(1, report.stages.connections.failures.size());
    assertEquals("boom", report.stages.connections.failures.get(0).reason);
  }

  @Test
  void dedupeCollapsesSameClientPair() {
    ConnectionPlan e1 = new ConnectionPlan();
    e1.setFromClientId("x");
    e1.setToClientId("y");
    ConnectionPlan e2 = new ConnectionPlan();
    e2.setFromClientId("x");
    e2.setToClientId("y");
    List<ConnectionPlan> r = ChainConnectionsApplier.dedupe(List.of(e1, e2));
    assertEquals(1, r.size());
  }

  @Test
  void collectAllConnectionsMergesRootAndNestedEdges() {
    ConnectionPlan rootEdge = new ConnectionPlan();
    rootEdge.setFromClientId("a");
    rootEdge.setToClientId("b");
    ElementPlan child = new ElementPlan();
    child.setClientId("c");
    ConnectionPlan nestedEdge = new ConnectionPlan();
    nestedEdge.setFromClientId("c");
    nestedEdge.setToClientId("d");
    child.setConnections(List.of(nestedEdge));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(List.of(rootEdge));
    plan.setElements(List.of(child));

    List<ConnectionPlan> all = ChainConnectionsApplier.collectAllConnections(plan);

    assertEquals(2, all.size());
  }

  @Test
  void catalogDependencyKeysBuildsEdgeKeySet() {
    CatalogDependencyDto d = new CatalogDependencyDto();
    d.from = "  A ";
    d.to = "B";
    assertTrue(
        CatalogDependencyKeys.edgeKeysFromDependencies(List.of(d))
            .contains(CatalogDependencyKeys.edgeKey("A", "B")));
  }

  private static ChainImplementationPlan planWithEdge(String from, String to) {
    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId(from);
    edge.setToClientId(to);
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setConnections(new ArrayList<>(List.of(edge)));
    plan.setElements(List.of(new ElementPlan()));
    return plan;
  }

  private static CreateElementsByJsonReport reportWithClientIds(String... pairs) {
    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      report.clientIds.put(pairs[i], pairs[i + 1]);
    }
    return report;
  }
}
