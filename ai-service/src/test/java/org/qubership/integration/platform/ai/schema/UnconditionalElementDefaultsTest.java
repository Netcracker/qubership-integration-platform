package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class UnconditionalElementDefaultsTest {

  @Test
  void fillsServiceCallRetryDefaultsWithoutAskingTheAuthor() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    SchemaRefResolver resolver =
        new SchemaRefResolver(new SchemaResourceLoader(), new QipSchemaYamlParser());
    ChainPlanNode node =
        new ChainPlanNode(
            "call-1",
            "service-call",
            "Get inventory",
            null,
            null,
            List.of(
                new PlanProperty("systemType", "EXTERNAL"),
                new PlanProperty("integrationOperationProtocolType", "http"),
                new PlanProperty("integrationSystemId", "sys-1"),
                new PlanProperty("integrationSpecificationGroupId", "grp-1"),
                new PlanProperty("integrationSpecificationId", "spec-1"),
                new PlanProperty("integrationOperationId", "op-1"),
                new PlanProperty("integrationOperationMethod", "GET"),
                new PlanProperty("integrationOperationPath", "/store/inventory")));
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("c1", "HealthProxy"), List.of(node), List.of());

    ChainPlanGraph filled = UnconditionalElementDefaults.apply(graph, mapper, resolver);

    assertEquals("0", property(filled.nodes().get(0), "retryCount"));
    assertEquals("5000", property(filled.nodes().get(0), "retryDelay"));
  }

  @Test
  void keepsExplicitRetryCountAndFillsMissingRetryDelay() {
    ObjectMapper mapper = new ObjectMapper();
    SchemaRefResolver resolver =
        new SchemaRefResolver(new SchemaResourceLoader(), new QipSchemaYamlParser());
    ChainPlanNode node =
        new ChainPlanNode(
            "call-1",
            "service-call",
            "Get inventory",
            null,
            null,
            List.of(
                new PlanProperty("systemType", "EXTERNAL"),
                new PlanProperty("integrationOperationProtocolType", "http"),
                new PlanProperty("integrationSystemId", "sys-1"),
                new PlanProperty("integrationSpecificationGroupId", "grp-1"),
                new PlanProperty("integrationSpecificationId", "spec-1"),
                new PlanProperty("integrationOperationId", "op-1"),
                new PlanProperty("integrationOperationMethod", "GET"),
                new PlanProperty("integrationOperationPath", "/store/inventory"),
                new PlanProperty("retryCount", "3")));
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("c1", "HealthProxy"), List.of(node), List.of());

    ChainPlanGraph filled = UnconditionalElementDefaults.apply(graph, mapper, resolver);

    assertEquals("3", property(filled.nodes().get(0), "retryCount"));
    assertEquals("5000", property(filled.nodes().get(0), "retryDelay"));
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }
}
