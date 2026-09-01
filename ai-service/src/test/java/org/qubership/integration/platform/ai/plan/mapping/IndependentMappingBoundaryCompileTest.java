package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.BriefMappingReview;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class IndependentMappingBoundaryCompileTest {

  @Test
  void twoApprovedIntentsCompileToDistinctExecutionSites() {
    RequirementBrief brief = twoMapperBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    ChainPlanNode init = requireSite(compiled, "map-init");
    ChainPlanNode conv = requireSite(compiled, "map-conv");
    assertEquals("script", init.type());
    assertEquals("script", conv.type());
    assertNotEquals(init.nodeId(), conv.nodeId());
    assertFalse(MappingExecutionSite.isConfigured(init));
    assertFalse(MappingExecutionSite.isConfigured(conv));
    assertTrue(hasEdge(compiled, "trigger-1", init.nodeId()));
    assertTrue(hasEdge(compiled, init.nodeId(), "call-1"));
    assertTrue(hasEdge(compiled, "call-1", conv.nodeId()));
    assertTrue(hasEdge(compiled, conv.nodeId(), "call-2"));
    assertFalse(hasEdge(compiled, "trigger-1", "call-1"));
    assertFalse(hasEdge(compiled, "call-1", "call-2"));
  }

  @Test
  void multipleRulesAtTheSameBoundaryShareOneSite() {
    RequirementBrief brief = briefWith(List.of(fiveRuleIntent("map-init", "trigger-1", "call-1")));
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    List<ChainPlanNode> sites =
        compiled.nodes().stream().filter(MappingExecutionSite::isTransformShell).toList();
    assertEquals(1, sites.size());
    assertEquals("map-init", MappingExecutionSite.mappingIntentId(sites.getFirst()));
    assertTrue(hasEdge(compiled, "call-1", "call-2"));
  }

  @Test
  void passThroughBoundaryKeepsDirectEdgeWithoutTransform() {
    RequirementBrief brief = briefWith(List.of(copyIntent("map-init", "trigger-1", "call-1")));
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    assertEquals(1, transformSites(compiled).size());
    assertTrue(hasEdge(compiled, "call-1", "call-2"));
    assertFalse(
        compiled.nodes().stream()
            .anyMatch(node -> "map-conv".equals(MappingExecutionSite.mappingIntentId(node))));
  }

  @Test
  void changingOneIntentDoesNotAddASecondSiteForTheOtherBoundary() {
    RequirementBrief brief = twoMapperBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);
    ChainPlanNode convBefore = requireSite(compiled, "map-conv");
    ChainPlanNode initBefore = requireSite(compiled, "map-init");

    RequirementBrief updated =
        BriefMappingReview.editRule(brief, "map-init", "$.personId", "$.accountId", null);
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(brief, updated, twoBoundaryPlan());

    assertEquals(Set.of("map-init"), impact.changedMappingIntentIds());
    assertTrue(impact.invalidatedPlanStepIds().contains("step-transform-map-init"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-transform-map-conv"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-script"));

    ChainPlanGraph rebuilt = compile(compiled, updated);
    assertSame(convBefore, requireSite(rebuilt, "map-conv"));
    assertEquals(initBefore.nodeId(), requireSite(rebuilt, "map-init").nodeId());
    assertEquals(2, transformSites(rebuilt).size());
  }

  @Test
  void twoScriptBoundariesStayIndependentShells() {
    RequirementBrief brief = mixedBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    ChainPlanNode init = requireSite(compiled, "map-init");
    ChainPlanNode conv = requireSite(compiled, "map-conv");
    assertEquals("script", init.type());
    assertEquals("script", conv.type());
    assertNotEquals(init.nodeId(), conv.nodeId());
    assertFalse(MappingExecutionSite.isConfigured(init));
    assertFalse(MappingExecutionSite.isConfigured(conv));
  }

  private static ChainPlanGraph compile(ChainPlanGraph topology, RequirementBrief brief) {
    return MappingStructurePhase.placeShells(topology, brief);
  }

  private static RequirementBrief twoMapperBrief() {
    return briefWith(
        List.of(
            copyIntent("map-init", "trigger-1", "call-1"),
            new MappingIntent(
                "map-conv",
                "call-1",
                MappingPort.RESPONSE,
                "call-2",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.personId", "$.accountId", null, MappingRuleStatus.USER_DEFINED)))));
  }

  private static RequirementBrief mixedBrief() {
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
                        "$.name", "$.name", null, MappingRuleStatus.USER_DEFINED))),
            new MappingIntent(
                "map-conv",
                "call-1",
                MappingPort.RESPONSE,
                "call-2",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.personId", "$.accountId", null, MappingRuleStatus.USER_DEFINED),
                    new MappingIntentRule(
                        "$.name",
                        "$.fullName",
                        "uppercase the name",
                        MappingRuleStatus.USER_DEFINED)),
                "SCRIPT")));
  }

  private static MappingIntent copyIntent(String mappingIntentId, String sourceRef, String targetRef) {
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

  private static MappingIntent fiveRuleIntent(
      String mappingIntentId, String sourceRef, String targetRef) {
    return new MappingIntent(
        mappingIntentId,
        sourceRef,
        MappingPort.OUTPUT,
        targetRef,
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule("$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule("$.userId", "$.personId", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule("$.name", "$.fullName", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "$.createdAt", "$.registrationDate", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule("$.status", "$.state", null, MappingRuleStatus.USER_DEFINED)));
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

  private static ChainPlanGraph linearPassThroughGraph() {
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
            new ChainPlanNode("call-1", "service-call", "Lookup person", null, null, List.of()),
            new ChainPlanNode("call-2", "service-call", "Create task", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e-trigger-call-1", "trigger-1", "call-1", null),
            new ChainPlanEdge("e-call-1-call-2", "call-1", "call-2", null)));
  }

  private static DesignExecutionPlan twoBoundaryPlan() {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(
            new DesignExecutionPlan.Step(
                "step-trigger",
                1,
                "Generate HTTP trigger",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-transform-map-init",
                2,
                "Configure mapper for map-init",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-transform-map-conv",
                3,
                "Configure mapper for map-conv",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-script",
                4,
                "Generate mapping script",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-script-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-hash",
        Map.of(
            "cip-trigger-generator",
            "h1",
            "cip-transformation-generator",
            "h2",
            "cip-script-generator",
            "h3"),
        Map.of(),
        "catalog-hash",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY_HASH);
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
}
