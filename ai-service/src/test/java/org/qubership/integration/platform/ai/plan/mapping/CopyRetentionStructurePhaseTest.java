package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingCondition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

class CopyRetentionStructurePhaseTest {
  @Test
  void earlierInputGetsASaveSiteWithoutARequestMapping() {
    ChainPlanGraph result = place(graph(), "entry", MappingPort.OUTPUT);
    ChainPlanNode site = result.nodes().stream().filter(node -> "script".equals(node.type())).findFirst().orElseThrow();
    assertAll(
        () -> assertEquals(4, result.nodes().size()),
        () -> assertEquals(3, result.edges().size()),
        () -> assertEquals(1, result.edges().stream().filter(edge -> "entry".equals(edge.fromNodeId())
            && site.nodeId().equals(edge.toNodeId())).count()),
        () -> assertEquals(1, result.edges().stream().filter(edge -> site.nodeId().equals(edge.fromNodeId())
            && "call".equals(edge.toNodeId())).count()),
        () -> assertEquals(1, result.edges().stream().filter(edge -> "call".equals(edge.fromNodeId())
            && "result".equals(edge.toNodeId())).count()),
        () -> assertNull(MappingExecutionSite.mappingIntentId(site)),
        () -> assertNull(MappingExecutionSite.scriptBody(site)));
  }

  @Test
  void placingTheSameConfirmedOriginTwiceIsIdempotent() {
    ChainPlanGraph once = place(graph(), "entry", MappingPort.OUTPUT);
    assertEquals(once, place(once, "entry", MappingPort.OUTPUT));
  }

  @Test
  void currentBodyCopyDoesNotAddARetentionSite() {
    assertEquals(graph(), place(graph(), "call", MappingPort.RESPONSE));
  }

  @Test
  void branchingProducerCannotChooseAnArbitrarySaveBoundary() {
    var edges = new ArrayList<>(graph().edges());
    edges.add(new ChainPlanEdge("branch", "entry", "result", null));
    var branched = new ChainPlanGraph("1.0", null, graph().nodes(), edges);
    assertThrows(IllegalArgumentException.class, () -> place(branched, "entry", MappingPort.OUTPUT));
  }

  @Test
  void anUnownedNodeCannotBeReusedAsACompilerSaveSite() {
    String id = CopyMappingPlanner.retentionSiteId(new MappingSource.Message("entry", MappingPort.OUTPUT, "$.id"));
    var nodes = new ArrayList<>(graph().nodes());
    nodes.add(new ChainPlanNode(id, "script", "User script", null, null, List.of()));
    var occupied = new ChainPlanGraph("1.0", null, nodes, graph().edges());
    assertThrows(IllegalArgumentException.class, () -> place(occupied, "entry", MappingPort.OUTPUT));
  }

  @Test
  void removingDerivedSitesRestoresTheOriginalExecutionEdges() {
    var restored = CopyRetentionStructurePhase.remove(place(graph(), "entry", MappingPort.OUTPUT));
    assertAll(
        () -> assertEquals(graph().nodes(), restored.nodes()),
        () -> assertEquals(graph().edges().stream().sorted(java.util.Comparator.comparing(ChainPlanEdge::edgeId)).toList(),
            restored.edges().stream().sorted(java.util.Comparator.comparing(ChainPlanEdge::edgeId)).toList()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void approvedVerbatimBodyNeedsNoCompilerRetentionButGeneratedCandidateDoes(boolean supplied) {
    String body = "exchange.in.body = [resultId: 'supplied']";
    var nodes = new ArrayList<>(graph().nodes());
    nodes.add(new ChainPlanNode("source-body", "script", "Source body", null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "response"))));
    var graph = new ChainPlanGraph("1.0", null, nodes,
        List.of(new ChainPlanEdge("request", "entry", "call", null),
            new ChainPlanEdge("before-result", "call", "source-body", null),
            new ChainPlanEdge("after-result", "source-body", "result", null)));
    var binding = new org.qubership.integration.platform.ai.plan.RequirementBodyBinding(
        supplied ? org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY
            : org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.GENERATED_CANDIDATE,
        "source-body", "script", org.qubership.integration.platform.ai.plan.RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
        "generation", "turn", 0, body.length(), org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding.RAW);
    var source = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("source-body", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "call", null, null, null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body)), List.of(), binding);
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("source",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Use the source body",
        "", "", "", "", "", "", List.of("call"), null, source);
    var rule = new MappingIntentRule("copy", List.of("fact"), "$.resultId",
        new MappingValue.Copy(new MappingSource.Message("entry", MappingPort.OUTPUT, "$.id")),
        MappingCondition.always(), MappingRuleStatus.PROPOSED);
    var intent = new MappingIntent("response", "call", MappingPort.RESPONSE, "result", MappingPort.REQUEST, List.of(rule));
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("Source body",
        List.of(), List.of(), List.of(), List.of(), "Source body", "approved", "draft", List.of(fact),
        List.of(), List.of(), List.of(), List.of(intent));
    var result = CopyRetentionStructurePhase.placeApproved(graph, brief, (ref, ignored) -> ref);
    assertEquals(supplied ? 0 : 1, result.nodes().stream().filter(CopyMappingPlanner::nativeRetentionSite).count());
    if (supplied) {
      assertEquals(graph, result);
      var seeded = org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues.seedBodies(result, brief.facts());
      assertEquals(body, MappingExecutionSite.scriptBody(seeded.nodes().stream()
          .filter(node -> "source-body".equals(node.nodeId())).findFirst().orElseThrow()));
    }
  }

  @Test
  void anExistingRequestMappingStillGetsTheOwnedRetentionBoundary() {
    var requestRule = new MappingIntentRule("request-rule", List.of("fact"), "$.requestId",
        new MappingValue.Copy(new MappingSource.Message("entry", MappingPort.OUTPUT, "$.id")),
        MappingCondition.always(), MappingRuleStatus.PROPOSED);
    var responseRule = requestRule.withTargetPath("$.resultId");
    var intents = List.of(new MappingIntent("request-map", "entry", MappingPort.OUTPUT,
            "call", MappingPort.REQUEST, List.of(requestRule)),
        new MappingIntent("response-map", "call", MappingPort.RESPONSE,
            "result", MappingPort.REQUEST, List.of(responseRule)));
    var nodes = new ArrayList<>(graph().nodes());
    nodes.add(new ChainPlanNode("request-script", "script", "Request", null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "request-map"))));
    var withRequest = new ChainPlanGraph("1.0", null, nodes, List.of(
        new ChainPlanEdge("entry-map", "entry", "request-script", null),
        new ChainPlanEdge("map-call", "request-script", "call", null),
        new ChainPlanEdge("response", "call", "result", null)));
    var result = CopyRetentionStructurePhase.place(withRequest, intents, (ref, port) -> ref);
    String siteId = CopyMappingPlanner.retentionSiteId(new MappingSource.Message("entry", MappingPort.OUTPUT, "$.id"));
    assertAll(
        () -> assertEquals(5, result.nodes().size()),
        () -> assertEquals(1, result.edges().stream().filter(edge -> "entry".equals(edge.fromNodeId())
            && siteId.equals(edge.toNodeId())).count()),
        () -> assertEquals(1, result.edges().stream().filter(edge -> siteId.equals(edge.fromNodeId())
            && "request-script".equals(edge.toNodeId())).count()));
  }

  private static ChainPlanGraph place(ChainPlanGraph graph, String origin, MappingPort port) {
    var rule = new MappingIntentRule("copy", List.of("fact"), "$.resultId",
        new MappingValue.Copy(new MappingSource.Message(origin, port, "$.id")),
        MappingCondition.always(), MappingRuleStatus.PROPOSED);
    var intent = new MappingIntent("response", "call", MappingPort.RESPONSE,
        "result", MappingPort.REQUEST, List.of(rule));
    return CopyRetentionStructurePhase.place(graph, List.of(intent), (ref, ignored) -> ref);
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph("1.0", null, List.of(
        new ChainPlanNode("entry", "chain-trigger-2", "Entry", null, null, List.of()),
        new ChainPlanNode("call", "service-call", "Call", null, null, List.of()),
        new ChainPlanNode("result", "service-call", "Result", null, null, List.of())),
        List.of(new ChainPlanEdge("request", "entry", "call", null),
            new ChainPlanEdge("response", "call", "result", null)));
  }
}
