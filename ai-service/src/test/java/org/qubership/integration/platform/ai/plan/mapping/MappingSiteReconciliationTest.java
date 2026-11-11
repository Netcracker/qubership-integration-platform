package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;

class MappingSiteReconciliationTest {

  @Test
  void insertsAMissingSiteForEachApprovedIntent() {
    ChainPlanGraph graph =
        MappingStructurePhase.placeShells(passThroughGraph(), briefWith(List.of(copyIntent())));

    ChainPlanNode site = requireSite(graph, "map-init");
    assertEquals("script", site.type());
    assertTrue(hasEdge(graph, "trigger-1", site.nodeId()));
    assertTrue(hasEdge(graph, site.nodeId(), "call-1"));
    assertFalse(hasEdge(graph, "trigger-1", "call-1"));
  }

  @Test
  void reusesAMatchingSiteWithoutAddingASecondShell() {
    RequirementBrief brief = briefWith(List.of(copyIntent()));
    ChainPlanGraph first = MappingStructurePhase.placeShells(passThroughGraph(), brief);
    ChainPlanNode original = requireSite(first, "map-init");

    ChainPlanGraph reused = MappingStructurePhase.placeShells(first, brief);

    assertSame(original, requireSite(reused, "map-init"));
    assertEquals(1, transformSites(reused).size());
  }

  @Test
  void removesAnObsoleteNonterminalSiteAndReconnectsTheSemanticEdge() {
    ChainPlanGraph withSite =
        MappingStructurePhase.placeShells(scopedPassThroughGraph(), briefWith(List.of(copyIntent())));
    ChainPlanNode site = requireSite(withSite, "map-init");

    ChainPlanGraph removed =
        MappingStructurePhase.placeShells(withSite, briefWith(List.of()));

    assertTrue(transformSites(removed).isEmpty());
    assertFalse(removed.nodes().stream().anyMatch(node -> site.nodeId().equals(node.nodeId())));
    assertTrue(hasEdge(removed, "trigger-1", "call-1"));
    assertEquals(
        "try-1",
        removed.edges().stream()
            .filter(edge -> "trigger-1".equals(edge.fromNodeId()) && "call-1".equals(edge.toNodeId()))
            .findFirst()
            .orElseThrow()
            .scopeNodeId());
  }

  @Test
  void removingATerminalSiteDropsTheIncomingEdgeWithoutInventingATarget() {
    ChainPlanGraph withTerminal = terminalSiteGraph();
    assertEquals(1, transformSites(withTerminal).size());

    ChainPlanGraph removed =
        MappingStructurePhase.placeShells(withTerminal, briefWith(List.of()));

    assertTrue(transformSites(removed).isEmpty());
    assertTrue(hasNode(removed, "trigger-1"));
    assertFalse(hasNode(removed, "transform-map-init"));
    assertTrue(removed.edges().isEmpty());
  }

  @Test
  void emptyApprovedCollectionRemovesAStaleTaggedSite() {
    ChainPlanGraph stale = terminalSiteGraph();

    ChainPlanGraph cleaned = MappingStructurePhase.placeShells(stale, briefWith(List.of()));

    assertTrue(transformSites(cleaned).isEmpty());
    assertTrue(MappingExecutionSiteValidator.validate(cleaned, List.of()).isEmpty());
  }

  @Test
  void nullBriefIsEmptyDesiredStateAndRemovesStaleTaggedSites() {
    ChainPlanGraph cleaned = MappingStructurePhase.placeShells(terminalSiteGraph(), null);

    assertTrue(transformSites(cleaned).isEmpty());
  }

  @Test
  void rejectsDuplicateSitesForTheSameIntent() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    duplicateSitesGraph(), briefWith(List.of(copyIntent()))));

    assertTrue(thrown.getMessage().contains("more than one execution site"));
  }

  @Test
  void rejectsAMisplacedSiteOnTheWrongBoundary() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    misplacedSiteGraph(), briefWith(List.of(copyIntent()))));

    assertTrue(thrown.getMessage().contains("does not match the approved source"));
  }

  @Test
  void rejectsAmbiguousUntaggedShellsOnTheSameBoundary() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    ambiguousUntaggedGraph(), briefWith(List.of(copyIntent()))));

    assertTrue(thrown.getMessage().contains("more than one untagged transform shell"));
  }

  @Test
  void rejectsAnUnreachableMatchingSite() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    unreachableMatchingSiteGraph(),
                    briefWith(List.of(copyIntent("map-init", "call-1", "call-2")))));

    assertTrue(thrown.getMessage().contains("not reachable"));
  }

  @Test
  void rejectsAStoredPortMismatch() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    portMismatchSiteGraph(), briefWith(List.of(copyIntent()))));

    assertTrue(thrown.getMessage().contains("does not match the approved source"));
  }

  @Test
  void rejectsAMechanismMismatch() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MappingStructurePhase.placeShells(
                    mapper2SiteOnScriptBoundaryGraph(),
                    briefWith(List.of(scriptPreferenceIntent()))));

    assertTrue(thrown.getMessage().contains("execution mechanism"));
  }

  @Test
  void changedMappingClearsGeneratedScriptInsteadOfMergingFragments() {
    ChainPlanGraph configured = configuredSiteGraph("target['obsolete'] = source['old']\n");

    ChainPlanGraph reconciled =
        MappingStructurePhase.placeShells(configured, briefWith(List.of(copyIntent())));

    ChainPlanNode site = requireSite(reconciled, "map-init");
    assertEquals("transform-map-init", site.nodeId());
    assertFalse(MappingExecutionSite.isConfigured(site));
    assertTrue(
        MappingExecutionSite.scriptBody(site) == null
            || MappingExecutionSite.scriptBody(site).isBlank());
    assertNull(MappingExecutionSite.mappingCoverage(site));
  }

  @Test
  void validatorFlagsAStaleTaggedSiteWhenTheApprovedCollectionIsEmpty() {
    List<ValidationIssue> issues =
        MappingExecutionSiteValidator.validate(terminalSiteGraph(), List.of());

    assertTrue(
        issues.stream()
            .anyMatch(
                issue ->
                    issue.message().contains("transform-map-init")
                        && issue.message().contains("not in the approved brief")));
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

  private static MappingIntent copyIntent() {
    return copyIntent("map-init", "trigger-1", "call-1");
  }

  private static MappingIntent copyIntent(
      String mappingIntentId, String sourceRef, String targetRef) {
    return new MappingIntent(
        mappingIntentId,
        sourceRef,
        MappingPort.OUTPUT,
        targetRef,
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.userId", "$.personId", null, MappingRuleStatus.USER_DEFINED)));
  }

  private static MappingIntent scriptPreferenceIntent() {
    return new MappingIntent(
        "map-init",
        "trigger-1",
        MappingPort.OUTPUT,
        "call-1",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.userId", "$.personId", null, MappingRuleStatus.USER_DEFINED)),
        "SCRIPT");
  }

  private static ChainPlanGraph passThroughGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger-1", "call-1", null)));
  }

  private static ChainPlanGraph scopedPassThroughGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger-1", "call-1", "try-1")));
  }

  private static ChainPlanGraph terminalSiteGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "transform-map-init",
                "script",
                "Script map-init",
                null,
                null,
                List.of(
                    new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init"),
                    new PlanProperty(MappingExecutionSite.MAPPING_SOURCE_PORT_PROPERTY, "OUTPUT"),
                    new PlanProperty(
                        MappingExecutionSite.MAPPING_TARGET_PORT_PROPERTY, "OUTPUT")))),
        List.of(new ChainPlanEdge("e-term", "trigger-1", "transform-map-init", null)));
  }

  private static ChainPlanGraph duplicateSitesGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            taggedScript("transform-map-init", "map-init"),
            taggedScript("transform-map-init-dup", "map-init"),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanGraph misplacedSiteGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of()),
            new ChainPlanNode("call-2", "service-call", "Other", null, null, List.of()),
            taggedScript("transform-map-init", "map-init")),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "call-1", null),
            new ChainPlanEdge("e2", "call-1", "transform-map-init", null),
            new ChainPlanEdge("e3", "transform-map-init", "call-2", null)));
  }

  private static ChainPlanGraph ambiguousUntaggedGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script-a", "script", "A", null, null, List.of()),
            new ChainPlanNode("script-b", "script", "B", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "script-a", null),
            new ChainPlanEdge("e2", "script-a", "call-1", null),
            new ChainPlanEdge("e3", "trigger-1", "script-b", null),
            new ChainPlanEdge("e4", "script-b", "call-1", null)));
  }

  private static ChainPlanGraph unreachableMatchingSiteGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            taggedScript("transform-map-init", "map-init"),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of()),
            new ChainPlanNode("call-2", "service-call", "Next", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e2", "call-1", "transform-map-init", null),
            new ChainPlanEdge("e3", "transform-map-init", "call-2", null)));
  }

  private static ChainPlanGraph portMismatchSiteGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "transform-map-init",
                "script",
                "Script map-init",
                null,
                null,
                List.of(
                    new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init"),
                    new PlanProperty(MappingExecutionSite.MAPPING_SOURCE_PORT_PROPERTY, "OUTPUT"),
                    new PlanProperty(
                        MappingExecutionSite.MAPPING_TARGET_PORT_PROPERTY, "OUTPUT"))),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanGraph mapper2SiteOnScriptBoundaryGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "transform-map-init",
                "mapper-2",
                "Map map-init",
                null,
                null,
                List.of(
                    new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init"),
                    new PlanProperty(MappingExecutionSite.MAPPING_SOURCE_PORT_PROPERTY, "OUTPUT"),
                    new PlanProperty(
                        MappingExecutionSite.MAPPING_TARGET_PORT_PROPERTY, "REQUEST"))),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanGraph configuredSiteGraph(String script) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "transform-map-init",
                "script",
                "Script map-init",
                null,
                null,
                List.of(
                    new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init"),
                    new PlanProperty(MappingExecutionSite.MAPPING_SOURCE_PORT_PROPERTY, "OUTPUT"),
                    new PlanProperty(MappingExecutionSite.MAPPING_TARGET_PORT_PROPERTY, "REQUEST"),
                    new PlanProperty(MappingExecutionSite.SCRIPT_PROPERTY, script),
                    new PlanProperty(
                        MappingExecutionSite.MAPPING_COVERAGE_PROPERTY, "[\"$.obsolete\"]"))),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanNode taggedScript(String nodeId, String mappingIntentId) {
    return new ChainPlanNode(
        nodeId,
        "script",
        "Script",
        null,
        null,
        List.of(new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, mappingIntentId)));
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

  private static boolean hasEdge(ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    return graph.edges().stream()
        .anyMatch(edge -> fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId()));
  }

  private static boolean hasNode(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream().anyMatch(node -> nodeId.equals(node.nodeId()));
  }
}
