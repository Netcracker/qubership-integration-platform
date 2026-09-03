package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.GraphAssemblyService;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
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
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

class ScriptMappingCompileTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void scriptPreferenceWithPlainLanguageProducesOneScriptShell() {
    RequirementBrief brief = approvedScriptBrief();
    assertFalse(briefContainsGroovy(brief));

    ChainPlanGraph shells = MappingStructurePhase.placeShells(passThroughGraph(), brief);

    List<ChainPlanNode> scripts = scriptNodes(shells);
    assertEquals(1, scripts.size());
    ChainPlanNode site = scripts.getFirst();
    assertEquals("script", site.type());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(site));
    assertFalse(MappingExecutionSite.isConfigured(site));
    assertTrue(hasEdge(shells, "trigger-1", site.nodeId()));
    assertTrue(hasEdge(shells, site.nodeId(), "call-1"));
    assertFalse(hasEdge(shells, "trigger-1", "call-1"));
    assertTrue(mapper2Nodes(shells).isEmpty());
  }

  @Test
  void structurePhaseEmitsScriptShellAndEdgesBeforeConfiguration() {
    RequirementBrief brief = approvedScriptBrief();
    ChainPlanGraph topology = passThroughGraph();

    ChainPlanGraph shells = MappingStructurePhase.placeShells(topology, brief);

    List<ChainPlanNode> scripts = scriptNodes(shells);
    assertEquals(1, scripts.size());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(scripts.getFirst()));
    assertFalse(MappingExecutionSite.isConfigured(scripts.getFirst()));
    assertTrue(hasEdge(shells, "trigger-1", scripts.getFirst().nodeId()));
    assertTrue(hasEdge(shells, scripts.getFirst().nodeId(), "call-1"));
    assertEquals(topology.nodes().size() + 1, shells.nodes().size());
  }

  @Test
  void mapper2PreferenceWithExpressionReturnsClarificationInsteadOfSwitching() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.name",
                    "$.fullName",
                    "uppercase the name",
                    MappingRuleStatus.USER_DEFINED)),
            "MAPPER_2");

    assertEquals(Optional.empty(), MappingMechanismSelector.select(intent));
    Optional<String> clarification = MappingMechanismSelector.clarification(intent);
    assertTrue(clarification.isPresent());
    assertTrue(clarification.get().contains("MAPPER_2"));
    assertTrue(clarification.get().contains("uppercase the name"));
    assertTrue(clarification.get().contains("SCRIPT"));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> MappingStructurePhase.placeShells(passThroughGraph(), briefWith(List.of(intent))));
    assertTrue(thrown.getMessage().contains("MAPPER_2"));
    assertFalse(thrown.getMessage().contains("mapper-2 execution site"));
  }

  @Test
  void scriptPreferenceWithNonWhitelistExpressionUsesScriptWhileMapper2IsDisabled() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.name",
                    "$.fullName",
                    "join records from two systems",
                    MappingRuleStatus.USER_DEFINED)),
            "SCRIPT");

    assertEquals(Optional.of(MappingMechanism.SCRIPT), MappingMechanismSelector.select(intent));
    assertTrue(MappingMechanismSelector.clarification(intent).isEmpty());

    ChainPlanGraph shells =
        MappingStructurePhase.placeShells(passThroughGraph(), briefWith(List.of(intent)));
    assertEquals(1, scriptNodes(shells).size());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(scriptNodes(shells).getFirst()));
  }

  @Test
  void assemblerDoesNotInventAMissingScriptNode() {
    GraphAssemblyService assembler = new GraphAssemblyService(new CanonicalGraphDigest(JSON));
    ChainStructure structure = new ChainStructure(passThroughGraph(), List.of(), List.of());

    ChainPlanGraph assembled = assembler.assemble(structure, List.of()).graph();

    assertTrue(scriptNodes(assembled).isEmpty());
    assertTrue(hasEdge(assembled, "trigger-1", "call-1"));
  }

  @Test
  void scriptPatchConfiguresExistingShellWithoutTopologyChanges() {
    RequirementBrief brief = approvedScriptBrief();
    ChainPlanGraph shells = MappingStructurePhase.placeShells(passThroughGraph(), brief);
    ChainPlanNode site = scriptNodes(shells).getFirst();
    GraphPatch patch =
        new GraphPatch(
            "configure-script",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    site.nodeId(),
                    new PlanProperty(
                        MappingExecutionSite.SCRIPT_PROPERTY,
                        "exchange.in.body = 'mapped'\nreturn exchange.in.body"))),
            List.of(),
            List.of(),
            "Configure existing script shell");
    assertTrue(patch.nodePatches().isEmpty());
    assertTrue(patch.edgePatches().isEmpty());
    assertFalse(patch.propertyPatches().isEmpty());
    assertEquals("cip-script-generator", patch.ownerCapabilityId());

    GraphPatchOwnershipPolicy ownership =
        new GraphPatchOwnershipPolicy(
            false,
            false,
            java.util.Set.of("script"),
            java.util.Set.of(),
            Map.of("script", java.util.Set.of(MappingExecutionSite.SCRIPT_PROPERTY)));
    GraphPatchApplyResult result =
        new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier())
            .apply(context(shells, ownership, brief), patch);

    assertTrue(result.validationResult().valid(), result.validationResult().summary());
    assertEquals(shells.nodes().size(), result.graph().nodes().size());
    assertEquals(shells.edges().size(), result.graph().edges().size());
    assertTrue(MappingExecutionSite.isConfigured(scriptNodes(result.graph()).getFirst()));
  }

  @Test
  void mappingScriptUpdateReplacesTheCompleteBodyInsteadOfMergingFragments() {
    RequirementBrief brief = approvedScriptBrief();
    ChainPlanGraph shells = MappingStructurePhase.placeShells(passThroughGraph(), brief);
    ChainPlanNode site = scriptNodes(shells).getFirst();
    java.util.ArrayList<PlanProperty> properties = new java.util.ArrayList<>();
    if (site.properties() != null) {
      properties.addAll(site.properties());
    }
    properties.add(
        new PlanProperty(
            MappingExecutionSite.SCRIPT_PROPERTY, "target['obsolete'] = source['old']\n"));
    ChainPlanNode configuredSite =
        new ChainPlanNode(
            site.nodeId(),
            site.type(),
            site.label(),
            site.parentNodeId(),
            site.order(),
            List.copyOf(properties));
    ChainPlanGraph configured =
        new ChainPlanGraph(
            shells.schemaVersion(),
            shells.chain(),
            shells.nodes().stream()
                .map(node -> site.nodeId().equals(node.nodeId()) ? configuredSite : node)
                .toList(),
            shells.edges());
    GraphPatch patch =
        new GraphPatch(
            "replace-script",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.UPDATE,
                    configuredSite.nodeId(),
                    new PlanProperty(
                        MappingExecutionSite.SCRIPT_PROPERTY,
                        "target['personId'] = source['userId']\n"
                            + "target['fullName'] = source['name']\n"))),
            List.of(),
            List.of(),
            "Replace the complete mapping script");
    GraphPatchOwnershipPolicy ownership =
        new GraphPatchOwnershipPolicy(
            false,
            false,
            java.util.Set.of("script"),
            java.util.Set.of(),
            Map.of("script", java.util.Set.of(MappingExecutionSite.SCRIPT_PROPERTY)));

    GraphPatchApplyResult result =
        new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier())
            .apply(context(configured, ownership, brief), patch);

    assertTrue(result.validationResult().valid(), result.validationResult().summary());
    String body = MappingExecutionSite.scriptBody(scriptNodes(result.graph()).getFirst());
    assertTrue(body.contains("personId"));
    assertFalse(body.contains("obsolete"));
  }

  @Test
  void selectsScriptWhenUserRequestsScriptWithoutGroovy() {
    Optional<MappingMechanism> mechanism =
        MappingMechanismSelector.select(approvedScriptBrief().mappingIntents().getFirst());

    assertEquals(Optional.of(MappingMechanism.SCRIPT), mechanism);
  }

  @Test
  void scriptPreferenceKeepsPlainLanguageExpressionResolvedAtBriefValidation() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.name", "$.fullName", "uppercase the name", MappingRuleStatus.USER_DEFINED)),
            MappingContract.unknown(),
            MappingContract.unknown(),
            "SCRIPT");

    assertTrue(intent.isPresent());
    assertEquals("SCRIPT", intent.get().implementationPreference());
    assertEquals(MappingRuleStatus.USER_DEFINED, intent.get().rules().getFirst().status());
    assertEquals("uppercase the name", intent.get().rules().getFirst().expression());
    assertFalse(BriefMappingValidator.blocksApproval(briefWith(List.of(intent.get()))));
  }

  private static RequirementBrief approvedScriptBrief() {
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
                        "$.name",
                        "$.fullName",
                        "uppercase the name",
                        MappingRuleStatus.USER_DEFINED)),
                "SCRIPT")));
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

  private static boolean briefContainsGroovy(RequirementBrief brief) {
    String text = brief.summary() + brief.approvedDraftText();
    for (MappingIntent intent : brief.mappingIntents()) {
      text = text + intent.implementationPreference();
      for (MappingIntentRule rule : intent.rules()) {
        text = text + rule.sourcePath() + rule.targetPath() + rule.expression();
      }
    }
    String lower = text.toLowerCase();
    return lower.contains("exchange.in.body")
        || lower.contains("groovy")
        || lower.contains("def ")
        || lower.contains("jsonslurper");
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

  private static List<ChainPlanNode> scriptNodes(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(node -> "script".equals(node.type())).toList();
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
        "cip-script-generator",
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
