package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class ServiceCallCatalogIdentityTest {

  @Test
  void occurrenceIdPrefersStoredServiceCallId() {
    ChainPlanNode node =
        new ChainPlanNode(
            "imported-uuid",
            "service-call",
            "Call",
            null,
            null,
            List.of(new PlanProperty("serviceCallId", "call-petstore")));
    assertEquals("call-petstore", ServiceCallCatalogIdentity.occurrenceId(node));
  }

  @Test
  void occurrenceIdFallsBackToNodeId() {
    ChainPlanNode node = new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of());
    assertEquals("call-1", ServiceCallCatalogIdentity.occurrenceId(node));
  }

  @Test
  void upsertWritesHttpIdentityAndKeepsRetry() {
    ChainPlanGraph graph = graph("call-1", List.of(new PlanProperty("retryCount", "3")));
    ChainPlanGraph out = ServiceCallCatalogIdentity.upsert(graph, httpBinding("call-1", "call-1"));
    ChainPlanNode node = node(out, "call-1");
    assertEquals("sys-1", property(node, "integrationSystemId"));
    assertEquals("http", property(node, "integrationOperationProtocolType"));
    assertEquals("3", property(node, "retryCount"));
  }

  @Test
  void upsertDropsGraphqlKeysWhenWritingHttp() {
    ChainPlanGraph graph =
        graph(
            "call-1",
            List.of(
                new PlanProperty("integrationGqlQuery", "query { x }"),
                new PlanProperty("synchronousGrpcCall", "true")));
    ChainPlanGraph out = ServiceCallCatalogIdentity.upsert(graph, httpBinding("call-1", "call-1"));
    ChainPlanNode node = node(out, "call-1");
    assertNull(property(node, "integrationGqlQuery"));
    assertNull(property(node, "synchronousGrpcCall"));
    assertEquals("GET", property(node, "integrationOperationMethod"));
  }

  @Test
  void upsertWritesKafkaPathWhenPresent() {
    ChainPlanGraph out =
        ServiceCallCatalogIdentity.upsert(
            graph("call-1", List.of()), asyncBinding("kafka", "orders", "subscribe"));

    assertEquals("orders", property(node(out, "call-1"), "integrationOperationPath"));
  }

  @Test
  void upsertOmitsKafkaPathWhenBlank() {
    ChainPlanGraph out =
        ServiceCallCatalogIdentity.upsert(
            graph("call-1", List.of()), asyncBinding("kafka", " ", "subscribe"));

    assertNull(property(node(out, "call-1"), "integrationOperationPath"));
  }

  @Test
  void upsertWritesAmqpPathWhenPresent() {
    ChainPlanGraph out =
        ServiceCallCatalogIdentity.upsert(
            graph("call-1", List.of()), asyncBinding("amqp", "orders.exchange", "send"));

    assertEquals("orders.exchange", property(node(out, "call-1"), "integrationOperationPath"));
  }

  @Test
  void upsertOmitsAmqpPathWhenMissing() {
    ChainPlanGraph out =
        ServiceCallCatalogIdentity.upsert(
            graph("call-1", List.of()), asyncBinding("amqp", null, "receive"));

    assertNull(property(node(out, "call-1"), "integrationOperationPath"));
  }

  @Test
  void upsertRefusesToOverwriteDifferentServiceCallId() {
    ChainPlanGraph graph =
        graph("node-1", List.of(new PlanProperty("serviceCallId", "call-a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceCallCatalogIdentity.upsert(graph, httpBinding("node-1", "call-b")));
  }

  @Test
  void upsertRefusesDuplicateServiceCallId() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "a",
                    "service-call",
                    "A",
                    null,
                    null,
                    List.of(new PlanProperty("serviceCallId", "call-1"))),
                new ChainPlanNode("b", "service-call", "B", null, null, List.of())),
            List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceCallCatalogIdentity.upsert(graph, httpBinding("b", "call-1")));
  }

  private static ResolvedServiceCallBinding httpBinding(String targetNodeId, String serviceCallId) {
    return new ResolvedServiceCallBinding(
        targetNodeId,
        serviceCallId,
        "EXTERNAL",
        "sys-1",
        "grp-1",
        "spec-1",
        "op-1",
        "http",
        "GET",
        "/x",
        "Get X",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "",
        "ev",
        "");
  }

  private static ResolvedServiceCallBinding asyncBinding(
      String protocol, String path, String method) {
    return new ResolvedServiceCallBinding(
        "call-1",
        "call-1",
        "EXTERNAL",
        "sys-1",
        "grp-1",
        "spec-1",
        "op-1",
        protocol,
        method,
        path,
        "Orders",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "",
        "ev",
        "");
  }

  private static ChainPlanGraph graph(String nodeId, List<PlanProperty> properties) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("n", "n"),
        List.of(new ChainPlanNode(nodeId, "service-call", "Call", null, null, properties)),
        List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(n -> n.nodeId().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty planProperty : node.properties()) {
      if (key.equals(planProperty.key())) {
        return planProperty.value();
      }
    }
    return null;
  }
}
