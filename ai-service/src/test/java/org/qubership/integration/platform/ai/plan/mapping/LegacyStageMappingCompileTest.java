package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class LegacyStageMappingCompileTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PAYLOAD = "{\"id\":\"p-1\",\"name\":\"Ada\"}";

  @Test
  void legacyPassThroughProducesNoIntentDirectEdgeAndNoTransformSite() {
    RequirementDataMapping passThrough =
        mapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of());
    RequirementBrief legacy = briefWithMappings(List.of(passThrough));

    List<MappingIntent> intents = LegacyStageMappingAdapter.fromDataMappings(legacy.dataMappings());
    assertTrue(intents.isEmpty());
    assertEquals(List.of(passThrough), legacy.dataMappings());

    RequirementBrief adapted = LegacyStageMappingAdapter.ensureIntents(legacy);
    assertTrue(adapted.mappingIntents().isEmpty());
    ChainPlanGraph compiled = compile(oneCallGraph(), legacy);
    assertTrue(transformSites(compiled).isEmpty());
    assertTrue(hasEdge(compiled, "trigger-1", "call-1"));
    assertEquals(oneCallGraph().nodes().size(), compiled.nodes().size());
    assertTrue(
        MappingExecutionSiteValidator.validate(compiled, adapted.mappingIntents()).isEmpty());
  }

  @Test
  void legacyExplicitFieldRulesCompileToOneMapper2Site() throws Exception {
    RequirementDataMapping explicit =
        mapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.petId", null)));
    RequirementBrief adapted =
        LegacyStageMappingAdapter.ensureIntents(briefWithMappings(List.of(explicit)));

    assertEquals(1, adapted.mappingIntents().size());
    MappingIntent intent = adapted.mappingIntents().getFirst();
    assertEquals("map-init", intent.mappingIntentId());
    assertEquals("trigger-1", intent.sourceRef());
    assertEquals(MappingPort.OUTPUT, intent.sourcePort());
    assertEquals("call-1", intent.targetRef());
    assertEquals(MappingPort.REQUEST, intent.targetPort());
    assertEquals(MappingMechanism.MAPPER_2, MappingMechanismSelector.select(intent).orElse(null));

    ChainPlanGraph compiled = compile(oneCallGraph(), adapted);
    List<ChainPlanNode> sites = transformSites(compiled);
    assertEquals(1, sites.size());
    assertEquals("mapper-2", sites.getFirst().type());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(sites.getFirst()));
    assertTrue(MappingExecutionSite.isConfigured(sites.getFirst()));
    assertTrue(hasEdge(compiled, "trigger-1", sites.getFirst().nodeId()));
    assertTrue(hasEdge(compiled, sites.getFirst().nodeId(), "call-1"));
    assertFalse(hasEdge(compiled, "trigger-1", "call-1"));

    JsonNode body = JSON.readTree(MappingFlowExecutor.apply(compiled, PAYLOAD));
    assertEquals("p-1", body.path("petId").asText());
  }

  @Test
  void legacyExplicitWithScriptPreferenceCompilesToScript() {
    RequirementDataMapping explicit =
        mapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(
                new RequirementDataMapping.Rule("$.id", "$.petId", null),
                new RequirementDataMapping.Rule("$.name", "$.fullName", "uppercase the name")));
    RequirementBrief adapted =
        LegacyStageMappingAdapter.ensureIntents(briefWithMappings(List.of(explicit)));
    MappingIntent base = adapted.mappingIntents().getFirst();
    RequirementBrief preferred =
        adapted.withMappingIntents(
            List.of(
                new MappingIntent(
                    base.mappingIntentId(),
                    base.sourceRef(),
                    base.sourcePort(),
                    base.targetRef(),
                    base.targetPort(),
                    base.rules(),
                    "SCRIPT")));

    ChainPlanGraph compiled = compile(oneCallGraph(), preferred);
    List<ChainPlanNode> sites = transformSites(compiled);
    assertEquals(1, sites.size());
    assertEquals("script", sites.getFirst().type());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(sites.getFirst()));
    assertTrue(MappingExecutionSite.isConfigured(sites.getFirst()));
    assertTrue(mapper2Nodes(compiled).isEmpty());
  }

  @Test
  void linearStagesMapOntoTriggerAndCallPortsOnTheSameGraph() {
    RequirementBrief adapted =
        LegacyStageMappingAdapter.ensureIntents(
            briefWithMappings(
                List.of(
                    mapping(
                        "map-init",
                        RequirementDataMapping.Stage.INITIALIZATION,
                        "trigger-1",
                        "call-1",
                        RequirementDataMapping.Mode.EXPLICIT,
                        List.of(new RequirementDataMapping.Rule("$.id", "$.petId", null))),
                    mapping(
                        "map-conv",
                        RequirementDataMapping.Stage.CONVERSION,
                        "call-1",
                        "call-2",
                        RequirementDataMapping.Mode.EXPLICIT,
                        List.of(new RequirementDataMapping.Rule("$.petId", "$.accountId", null))),
                    mapping(
                        "map-resp",
                        RequirementDataMapping.Stage.RESPONSE,
                        "call-2",
                        "trigger-1",
                        RequirementDataMapping.Mode.EXPLICIT,
                        List.of(
                            new RequirementDataMapping.Rule("$.accountId", "$.result", null))))));

    assertEquals(3, adapted.mappingIntents().size());
    assertPorts(
        adapted.mappingIntents().get(0),
        "map-init",
        "trigger-1",
        MappingPort.OUTPUT,
        "call-1",
        MappingPort.REQUEST);
    assertPorts(
        adapted.mappingIntents().get(1),
        "map-conv",
        "call-1",
        MappingPort.RESPONSE,
        "call-2",
        MappingPort.REQUEST);
    assertPorts(
        adapted.mappingIntents().get(2),
        "map-resp",
        "call-2",
        MappingPort.RESPONSE,
        "trigger-1",
        MappingPort.OUTPUT);

    ChainPlanGraph compiled = compile(twoCallGraph(), adapted);
    ChainPlanNode init = requireSite(compiled, "map-init");
    ChainPlanNode conv = requireSite(compiled, "map-conv");
    ChainPlanNode resp = requireSite(compiled, "map-resp");
    assertEquals("mapper-2", init.type());
    assertEquals("mapper-2", conv.type());
    assertEquals("mapper-2", resp.type());
    assertTrue(hasEdge(compiled, "trigger-1", init.nodeId()));
    assertTrue(hasEdge(compiled, init.nodeId(), "call-1"));
    assertTrue(hasEdge(compiled, "call-1", conv.nodeId()));
    assertTrue(hasEdge(compiled, conv.nodeId(), "call-2"));
    assertTrue(hasEdge(compiled, "call-2", resp.nodeId()));
    assertFalse(hasEdge(compiled, "call-2", "trigger-1"));
    assertEquals(twoCallGraph().chain(), compiled.chain());
    assertTrue(
        MappingExecutionSiteValidator.validate(compiled, adapted.mappingIntents()).isEmpty());
  }

  @Test
  void briefWithOnlyDataMappingsCompilesLikeEquivalentV2Brief() throws Exception {
    RequirementDataMapping explicit =
        mapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.petId", null)));
    RequirementBrief legacy = briefWithMappings(List.of(explicit));
    assertTrue(legacy.mappingIntents().isEmpty());

    RequirementBrief adapted = LegacyStageMappingAdapter.ensureIntents(legacy);
    RequirementBrief v2 =
        briefWithMappings(List.of()).withMappingIntents(adapted.mappingIntents());

    ChainPlanGraph fromLegacy = compile(oneCallGraph(), legacy);
    ChainPlanGraph fromV2 = compile(oneCallGraph(), v2);

    assertEquals(transformSites(fromV2).size(), transformSites(fromLegacy).size());
    assertEquals(
        MappingExecutionSite.mappingIntentId(transformSites(fromV2).getFirst()),
        MappingExecutionSite.mappingIntentId(transformSites(fromLegacy).getFirst()));
    assertEquals(
        MappingFlowExecutor.apply(fromV2, PAYLOAD), MappingFlowExecutor.apply(fromLegacy, PAYLOAD));
    JsonNode body = JSON.readTree(MappingFlowExecutor.apply(fromLegacy, PAYLOAD));
    assertEquals("p-1", body.path("petId").asText());
  }

  private static void assertPorts(
      MappingIntent intent,
      String mappingIntentId,
      String sourceRef,
      MappingPort sourcePort,
      String targetRef,
      MappingPort targetPort) {
    assertEquals(mappingIntentId, intent.mappingIntentId());
    assertEquals(sourceRef, intent.sourceRef());
    assertEquals(sourcePort, intent.sourcePort());
    assertEquals(targetRef, intent.targetRef());
    assertEquals(targetPort, intent.targetPort());
  }

  private static ChainPlanGraph compile(ChainPlanGraph topology, RequirementBrief brief) {
    return ScriptConfigurationPhase.configure(
        Mapper2ConfigurationPhase.configure(
            MappingStructurePhase.placeShells(topology, brief), brief),
        brief);
  }

  private static RequirementBrief briefWithMappings(List<RequirementDataMapping> mappings) {
    return new RequirementBrief(
        "Orders",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Map OM output to Salesforce request",
        "ref",
        "draft",
        List.of(),
        mappings);
  }

  private static RequirementDataMapping mapping(
      String mappingId,
      RequirementDataMapping.Stage stage,
      String fromIntentRef,
      String toIntentRef,
      RequirementDataMapping.Mode mode,
      List<RequirementDataMapping.Rule> rules) {
    return new RequirementDataMapping(
        mappingId, stage, fromIntentRef, toIntentRef, mode, rules, List.of(fromIntentRef));
  }

  private static ChainPlanGraph oneCallGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            triggerNode(),
            new ChainPlanNode("call-1", "service-call", "Look up pet", null, null, List.of())),
        List.of(new ChainPlanEdge("e-trigger-call", "trigger-1", "call-1", null)));
  }

  private static ChainPlanGraph twoCallGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            triggerNode(),
            new ChainPlanNode("call-1", "service-call", "Look up pet", null, null, List.of()),
            new ChainPlanNode("call-2", "service-call", "Create task", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e-trigger-call-1", "trigger-1", "call-1", null),
            new ChainPlanEdge("e-call-1-call-2", "call-1", "call-2", null)));
  }

  private static ChainPlanNode triggerNode() {
    return new ChainPlanNode(
        "trigger-1",
        "http-trigger",
        "OM trigger",
        null,
        null,
        List.of(
            new PlanProperty("contextPath", "/orders"),
            new PlanProperty("httpMethodRestrict", "POST")));
  }

  private static ChainPlanNode requireSite(ChainPlanGraph graph, String mappingIntentId) {
    return graph.nodes().stream()
        .filter(node -> mappingIntentId.equals(MappingExecutionSite.mappingIntentId(node)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing site for " + mappingIntentId));
  }

  private static List<ChainPlanNode> transformSites(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(MappingExecutionSite::isTransformShell).toList();
  }

  private static List<ChainPlanNode> mapper2Nodes(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(node -> "mapper-2".equals(node.type())).toList();
  }

  private static boolean hasEdge(ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    return graph.edges().stream()
        .anyMatch(edge -> fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId()));
  }
}
