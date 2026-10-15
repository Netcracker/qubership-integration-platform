package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

class RequestedElementValuesTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"selected-trigger", "true"})
  void authorIdentityIsRejectedEvenWhenTheGraphAlreadyContainsIt(String boundTarget) {
    var graph = new ChainPlanGraph("1.0", new ChainSection("Call", ""), List.of(
        new ChainPlanNode("invoke-target", "chain-call-2", "Call", null, null,
            List.of(new PlanProperty("elementId", boundTarget)))), List.of());
    var intent = new RequirementElementIntent("invoke-target", "chain-call-2", Placement.ANCHOR,
        "invoke-target", null, null, null, List.of(new PlanProperty("elementId", "true")), List.of());

    var error = assertThrows(IllegalArgumentException.class,
        () -> RequestedElementValues.apply(graph, List.of(fact("chain-call-anchor", intent))));

    assertTrue(error.getMessage().contains("chain-call-anchor"), error.getMessage());
    assertEquals(boundTarget, graph.nodes().getFirst().properties().getFirst().value());
  }

  @Test
  void chainCallTimeoutAndNamePreserveTheResolvedTarget() {
    var graph = new ChainPlanGraph("1.0", new ChainSection("Call", ""), List.of(
        new ChainPlanNode("invoke-target", "chain-call-2", "Generated", null, null,
            List.of(new PlanProperty("elementId", "selected-trigger")))), List.of());
    var intent = new RequirementElementIntent("invoke-target", "chain-call-2", Placement.ANCHOR,
        "invoke-target", null, null, "Selected call", List.of(new PlanProperty("timeout", "30000")), List.of());

    var node = RequestedElementValues.apply(graph, List.of(fact("chain-call-anchor", intent))).nodes().getFirst();

    assertEquals("Selected call", node.label());
    assertEquals(List.of(new PlanProperty("elementId", "selected-trigger"), new PlanProperty("timeout", "30000")),
        node.properties());
  }

  @Test
  void requestedNamesAndConsumerOptionsOverrideGeneratedValues() throws Exception {
    var facts = List.of(
        fact("name", new RequirementElementIntent("chain", "chain", Placement.CHAIN,
            null, null, null, "Http sender", List.of(), List.of())),
        fact("call-name", new RequirementElementIntent("call", "service-call", Placement.ANCHOR,
            "call", null, null, "Publish message to kafka", List.of(), List.of())),
        fact("consumer-options", new RequirementElementIntent("consume", "async-api-trigger", Placement.ANCHOR,
            "consume", null, null, null, List.of(new PlanProperty("integrationOperationAsyncProperties",
                "{\"groupId\":\"CIP\"}")), List.of())));
    var generated = new NamingManifest(1, "Chain name: Http sender", Map.of("call", "Publish to Kafka", "unnamed", "Generated"),
        List.of(), List.of());
    var naming = RequestedElementValues.naming(generated, facts);
    assertEquals("Http sender", naming.chainName());
    assertEquals("Publish message to kafka", naming.labelsByRoleId().get("call"));
    assertEquals("Generated", naming.labelsByRoleId().get("unnamed"));
    var graph = new ChainPlanGraph("1.0", new ChainSection("Chain name: Http sender", "description"), List.of(
        new ChainPlanNode("call", "service-call", "Publish to Kafka", null, null, List.of()),
        new ChainPlanNode("consume", "async-api-trigger", "Consume", null, null, List.of(
            new PlanProperty("integrationOperationAsyncProperties", "{\"groupId\":\"qip\",\"maas.classifier.name\":\"wfms\"}")))), List.of());
    var applied = RequestedElementValues.apply(graph, facts);
    assertEquals("Http sender", applied.chain().name());
    assertEquals("Publish message to kafka", applied.nodes().getFirst().label());
    var options = new com.fasterxml.jackson.databind.ObjectMapper().readTree(applied.nodes().get(1).properties().getFirst().value());
    assertEquals("CIP", options.path("groupId").asText());
    assertEquals("wfms", options.path("maas.classifier.name").asText());
    assertEquals("Chain name: Http sender", graph.chain().name());
  }

  private static RequirementFact fact(String id, RequirementElementIntent intent) {
    return new RequirementFact(id, RequirementFactPolarity.POSITIVE, RequirementFactKind.PARAMETER,
        "", "Explicit author values", "", "", "", "", "", "",
        intent.ownerInteractionId() == null ? List.of() : List.of(intent.ownerInteractionId()), null, intent);
  }
}
