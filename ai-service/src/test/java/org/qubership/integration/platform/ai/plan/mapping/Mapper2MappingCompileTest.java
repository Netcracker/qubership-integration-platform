package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.GraphAssemblyService;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

@EnabledIf("mapper2Enabled")
class Mapper2MappingCompileTest {

  static boolean mapper2Enabled() {
    return MappingMechanismSelector.mapper2Enabled();
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void approvedCopyAndConstantMappingProducesOneMapper2SiteWithIntentId() {
    RequirementBrief brief = approvedBrief();
    ChainPlanGraph compiled =
        Mapper2ConfigurationPhase.configure(
            MappingStructurePhase.placeShells(passThroughGraph(), brief), brief);

    List<ChainPlanNode> mappers = mapper2Nodes(compiled);
    assertEquals(1, mappers.size());
    ChainPlanNode site = mappers.getFirst();
    assertEquals("mapper-2", site.type());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(site));
    assertTrue(MappingExecutionSite.isConfigured(site));
    assertTrue(hasEdge(compiled, "trigger-1", site.nodeId()));
    assertTrue(hasEdge(compiled, site.nodeId(), "call-1"));
    assertFalse(hasEdge(compiled, "trigger-1", "call-1"));
  }

  @Test
  void structurePhaseEmitsShellAndEdgesBeforeConfiguration() {
    RequirementBrief brief = approvedBrief();
    ChainPlanGraph topology = passThroughGraph();

    ChainPlanGraph shells = MappingStructurePhase.placeShells(topology, brief);

    List<ChainPlanNode> mappers = mapper2Nodes(shells);
    assertEquals(1, mappers.size());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(mappers.getFirst()));
    assertFalse(MappingExecutionSite.isConfigured(mappers.getFirst()));
    assertTrue(hasEdge(shells, "trigger-1", mappers.getFirst().nodeId()));
    assertTrue(hasEdge(shells, mappers.getFirst().nodeId(), "call-1"));
    assertEquals(topology.nodes().size() + 1, shells.nodes().size());

    ChainPlanGraph configured = Mapper2ConfigurationPhase.configure(shells, brief);

    assertEquals(shells.nodes().size(), configured.nodes().size());
    assertEquals(shells.edges().size(), configured.edges().size());
    assertTrue(MappingExecutionSite.isConfigured(mapper2Nodes(configured).getFirst()));
  }

  @Test
  void passThroughWithoutIntentHasNoMapperNode() {
    RequirementBrief brief = briefWith(List.of());
    ChainPlanGraph placed = MappingStructurePhase.placeShells(passThroughGraph(), brief);

    assertTrue(mapper2Nodes(placed).isEmpty());
    assertTrue(hasEdge(placed, "trigger-1", "call-1"));
    assertEquals(passThroughGraph().nodes().size(), placed.nodes().size());
  }

  @Test
  void configurationDoesNotInventAMissingTransformShell() {
    RequirementBrief brief = approvedBrief();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> Mapper2ConfigurationPhase.configure(passThroughGraph(), brief));
    assertTrue(thrown.getMessage().contains("map-init"));
    assertTrue(thrown.getMessage().contains("cip-transformation-generator"));
  }

  @Test
  void assemblerDoesNotInventAMissingTransformNode() {
    GraphAssemblyService assembler =
        new GraphAssemblyService(new CanonicalGraphDigest(JSON));
    ChainStructure structure = new ChainStructure(passThroughGraph(), List.of(), List.of());

    ChainPlanGraph assembled = assembler.assemble(structure, List.of()).graph();

    assertTrue(mapper2Nodes(assembled).isEmpty());
    assertTrue(hasEdge(assembled, "trigger-1", "call-1"));
  }

  @Test
  void transformationPatchConfiguresExistingShellWithoutTopologyChanges() {
    RequirementBrief brief = approvedBrief();
    ChainPlanGraph shells = MappingStructurePhase.placeShells(passThroughGraph(), brief);
    GraphPatch patch = Mapper2ConfigurationPhase.configurationPatch(shells, brief);
    assertTrue(patch.nodePatches().isEmpty());
    assertTrue(patch.edgePatches().isEmpty());
    assertFalse(patch.propertyPatches().isEmpty());

    GraphPatchOwnershipPolicy ownership =
        new GraphPatchOwnershipPolicy(
            false,
            false,
            java.util.Set.of("mapper-2"),
            java.util.Set.of(),
            Map.of("mapper-2", java.util.Set.of(MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY)));
    GraphPatchApplyResult result =
        new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier())
            .apply(context(shells, ownership, brief), patch);

    assertTrue(result.validationResult().valid(), result.validationResult().summary());
    assertEquals(shells.nodes().size(), result.graph().nodes().size());
    assertEquals(shells.edges().size(), result.graph().edges().size());
    assertTrue(MappingExecutionSite.isConfigured(mapper2Nodes(result.graph()).getFirst()));
  }

  @Test
  void selectsMapper2ForDeclarativeCopyAndConstantRules() {
    Optional<MappingMechanism> mechanism =
        MappingMechanismSelector.select(approvedBrief().mappingIntents().getFirst());

    assertEquals(Optional.of(MappingMechanism.MAPPER_2), mechanism);
  }

  @Test
  void completedSimpleMappingTransformsPayloadFields() throws Exception {
    RequirementBrief brief = approvedBrief();
    ChainPlanGraph compiled =
        Mapper2ConfigurationPhase.configure(
            MappingStructurePhase.placeShells(passThroughGraph(), brief), brief);

    String output =
        SimpleMapper2Executor.apply(
            compiled, "{\"userId\":\"u-1\",\"name\":\"Ada\",\"ignored\":\"x\"}");

    JsonNode body = JSON.readTree(output);
    assertEquals("u-1", body.path("personId").asText());
    assertEquals("OPEN", body.path("status").asText());
    assertTrue(body.path("ignored").isMissingNode());
  }

  private static RequirementBrief approvedBrief() {
    return briefWith(
        List.of(
            new MappingIntent(
                "map-init",
                "trigger-1",
                MappingPort.OUTPUT,
                "call-1",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.userId", "$.personId", null, MappingRuleStatus.USER_DEFINED),
                    new MappingIntentRule(
                        "\"OPEN\"", "$.status", null, MappingRuleStatus.USER_DEFINED)))));
  }

  private static RequirementBrief briefWith(List<MappingIntent> intents) {
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
            List.of())
        .withMappingIntents(intents);
  }

  private static ChainPlanGraph passThroughGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "trigger-1",
                "http-trigger",
                "OM trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/orders"),
                    new PlanProperty("httpMethodRestrict", "POST"))),
            new ChainPlanNode("call-1", "service-call", "Create task", null, null, List.of())),
        List.of(new ChainPlanEdge("e-trigger-call", "trigger-1", "call-1", null)));
  }

  private static List<ChainPlanNode> mapper2Nodes(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(node -> "mapper-2".equals(node.type())).toList();
  }

  private static boolean hasEdge(ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    return graph.edges().stream()
        .anyMatch(edge -> fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId()));
  }

  private static GraphPatchExecutionContext context(
      ChainPlanGraph graph, GraphPatchOwnershipPolicy ownership, RequirementBrief brief) {
    return new GraphPatchExecutionContext(
        "run-1",
        "cip-transformation-generator",
        "req",
        "input",
        "compiler",
        "24.4",
        brief,
        List.of(),
        graph,
        ownership,
        "");
  }
}
