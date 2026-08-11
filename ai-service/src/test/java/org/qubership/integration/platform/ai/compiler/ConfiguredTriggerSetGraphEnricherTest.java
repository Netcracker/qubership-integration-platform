package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

class ConfiguredTriggerSetGraphEnricherTest {

  @Test
  void copiesBlankTriggerEndpointPropertiesOntoMatchingNode() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", null),
            List.of(new ChainPlanNode("http-trigger", "http-trigger", "HTTP Trigger", null, null, List.of())),
            List.of());
    ConfiguredTriggerSet triggers =
        new ConfiguredTriggerSet(
            1,
            List.of(
                new ConfiguredTrigger(
                    "http-entry",
                    "http-trigger",
                    "http-trigger",
                    "GET /v1/geo-site/{id}",
                    List.of(
                        new PlanProperty("contextPath", "/v1/geo-site/{id}"),
                        new PlanProperty("httpMethodRestrict", "GET"),
                        new PlanProperty("externalRoute", "true")))),
            List.of(),
            List.of());

    ChainPlanGraph enriched = ConfiguredTriggerSetGraphEnricher.enrich(graph, triggers);

    ChainPlanNode trigger = enriched.nodes().get(0);
    assertEquals(3, trigger.properties().size());
    assertEquals("/v1/geo-site/{id}", property(trigger, "contextPath"));
    assertEquals("GET", property(trigger, "httpMethodRestrict"));
    assertEquals("true", property(trigger, "externalRoute"));
  }

  @Test
  void doesNotOverwriteExistingNonBlankProperty() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", null),
            List.of(
                new ChainPlanNode(
                    "http-trigger",
                    "http-trigger",
                    "HTTP Trigger",
                    null,
                    null,
                    List.of(new PlanProperty("contextPath", "/already")))),
            List.of());
    ConfiguredTriggerSet triggers =
        new ConfiguredTriggerSet(
            1,
            List.of(
                new ConfiguredTrigger(
                    "http-entry",
                    "http-trigger",
                    "http-trigger",
                    "label",
                    List.of(new PlanProperty("contextPath", "/from-trigger")))),
            List.of(),
            List.of());

    ChainPlanGraph enriched = ConfiguredTriggerSetGraphEnricher.enrich(graph, triggers);

    assertEquals("/already", property(enriched.nodes().get(0), "contextPath"));
  }

  @Test
  void returnsSameGraphWhenNothingToMerge() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", null),
            List.of(new ChainPlanNode("http-trigger", "http-trigger", "HTTP Trigger", null, null, List.of())),
            List.of());

    assertSame(graph, ConfiguredTriggerSetGraphEnricher.enrich(graph, null));
  }

  private static String property(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(p -> key.equals(p.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }
}
