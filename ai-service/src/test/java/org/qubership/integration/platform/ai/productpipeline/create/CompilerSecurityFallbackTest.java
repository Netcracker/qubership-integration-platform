package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class CompilerSecurityFallbackTest {

  @Test
  void addsDefaultRoleToExternalRbacTriggerWithoutRoles() {
    ChainPlanGraph graph =
        graph(httpTrigger(List.of(
            new PlanProperty("externalRoute", "true"),
            new PlanProperty("accessControlType", "RBAC"))));

    ChainPlanGraph result = CompilerSecurityFallback.apply(graph);

    assertEquals("[\"qip-viewer\"]", propertyValue(result, "trigger", "roles"));
  }

  @Test
  void addsDefaultRoleWhenRolesAreEmptyArray() {
    ChainPlanGraph graph =
        graph(httpTrigger(List.of(
            new PlanProperty("externalRoute", "true"),
            new PlanProperty("accessControlType", "RBAC"),
            new PlanProperty("roles", "[]"))));

    ChainPlanGraph result = CompilerSecurityFallback.apply(graph);

    assertEquals("[\"qip-viewer\"]", propertyValue(result, "trigger", "roles"));
  }

  @Test
  void preservesExistingRoles() {
    ChainPlanGraph graph =
        graph(httpTrigger(List.of(
            new PlanProperty("externalRoute", "true"),
            new PlanProperty("accessControlType", "RBAC"),
            new PlanProperty("roles", "[\"custom-admin\"]"))));

    ChainPlanGraph result = CompilerSecurityFallback.apply(graph);

    assertEquals("[\"custom-admin\"]", propertyValue(result, "trigger", "roles"));
  }

  @Test
  void ignoresInternalTrigger() {
    ChainPlanGraph graph =
        graph(httpTrigger(List.of(
            new PlanProperty("externalRoute", "false"),
            new PlanProperty("accessControlType", "RBAC"))));

    ChainPlanGraph result = CompilerSecurityFallback.apply(graph);

    assertNull(propertyValue(result, "trigger", "roles"));
  }

  @Test
  void ignoresNonRbacExternalTrigger() {
    ChainPlanGraph graph =
        graph(httpTrigger(List.of(
            new PlanProperty("externalRoute", "true"),
            new PlanProperty("accessControlType", "NONE"))));

    ChainPlanGraph result = CompilerSecurityFallback.apply(graph);

    assertNull(propertyValue(result, "trigger", "roles"));
  }

  @Test
  void handlesNullGraph() {
    assertNull(CompilerSecurityFallback.apply(null));
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph("1.0", new ChainSection("test", "Test"), List.of(nodes), List.of());
  }

  private static ChainPlanNode httpTrigger(List<PlanProperty> properties) {
    return new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, properties);
  }

  private static String propertyValue(ChainPlanGraph graph, String nodeId, String key) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .flatMap(node -> node.properties().stream())
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }
}
