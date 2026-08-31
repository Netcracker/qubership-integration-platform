package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;

@EnabledIf("mapper2Enabled")
class IndependentMappingBoundaryCompileTest {

  static boolean mapper2Enabled() {
    return MappingMechanismSelector.mapper2Enabled();
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void twoApprovedIntentsCompileToDistinctExecutionSites() {
    RequirementBrief brief = twoMapperBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    ChainPlanNode init = requireSite(compiled, "map-init");
    ChainPlanNode conv = requireSite(compiled, "map-conv");
    assertEquals("mapper-2", init.type());
    assertEquals("mapper-2", conv.type());
    assertNotEquals(init.nodeId(), conv.nodeId());
    assertTrue(MappingExecutionSite.isConfigured(init));
    assertTrue(MappingExecutionSite.isConfigured(conv));
    assertTrue(hasEdge(compiled, "trigger-1", init.nodeId()));
    assertTrue(hasEdge(compiled, init.nodeId(), "call-1"));
    assertTrue(hasEdge(compiled, "call-1", conv.nodeId()));
    assertTrue(hasEdge(compiled, conv.nodeId(), "call-2"));
    assertFalse(hasEdge(compiled, "trigger-1", "call-1"));
    assertFalse(hasEdge(compiled, "call-1", "call-2"));
    assertTrue(MappingExecutionSiteValidator.validate(compiled, brief.mappingIntents()).isEmpty());
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
  void changingOneIntentReconfiguresOnlyThatSite() {
    RequirementBrief brief = twoMapperBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);
    ChainPlanNode convBefore = requireSite(compiled, "map-conv");
    String convConfig = MappingExecutionSite.mappingDescription(convBefore);
    String initConfig = MappingExecutionSite.mappingDescription(requireSite(compiled, "map-init"));

    RequirementBrief updated =
        BriefMappingReview.editRule(brief, "map-init", "$.personId", "$.accountId", null);
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(brief, updated, twoBoundaryPlan());

    assertEquals(Set.of("map-init"), impact.changedMappingIntentIds());
    assertTrue(impact.invalidatedPlanStepIds().contains("step-transform-map-init"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-transform-map-conv"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-script"));

    ChainPlanGraph rebuilt = reconfigureChanged(compiled, updated, impact.changedMappingIntentIds());
    ChainPlanNode convAfter = requireSite(rebuilt, "map-conv");
    assertSame(convBefore, convAfter);
    assertEquals(convConfig, MappingExecutionSite.mappingDescription(convAfter));
    assertNotEquals(
        initConfig, MappingExecutionSite.mappingDescription(requireSite(rebuilt, "map-init")));
    assertTrue(
        MappingExecutionSite.mappingDescription(requireSite(rebuilt, "map-init"))
            .contains("accountId"));
    List<ValidationIssue> issues =
        MappingExecutionSiteValidator.validate(rebuilt, updated.mappingIntents());
    assertTrue(issues.isEmpty(), issues.toString());
  }

  @Test
  void linearFlowAppliesEachMappingOnceInExecutionOrder() throws Exception {
    RequirementBrief brief = twoMapperBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    String output = MappingFlowExecutor.apply(compiled, "{\"userId\":\"u-1\",\"name\":\"Ada\"}");

    JsonNode body = JSON.readTree(output);
    assertEquals("u-1", body.path("accountId").asText());
    assertTrue(body.path("personId").isMissingNode());
    assertTrue(body.path("userId").isMissingNode());
  }

  @Test
  void mapper2AndScriptOnDifferentBoundariesCompileIndependently() throws Exception {
    RequirementBrief brief = mixedBrief();
    ChainPlanGraph compiled = compile(linearPassThroughGraph(), brief);

    ChainPlanNode init = requireSite(compiled, "map-init");
    ChainPlanNode conv = requireSite(compiled, "map-conv");
    assertEquals("mapper-2", init.type());
    assertEquals("script", conv.type());
    assertTrue(MappingExecutionSite.isConfigured(init));
    assertTrue(MappingExecutionSite.isConfigured(conv));

    String output = MappingFlowExecutor.apply(compiled, "{\"userId\":\"u-1\",\"name\":\"Ada\"}");
    JsonNode body = JSON.readTree(output);
    assertEquals("u-1", body.path("accountId").asText());
    assertEquals("ADA", body.path("fullName").asText());

    ChainPlanNode convBefore = conv;
    String scriptBefore = MappingExecutionSite.scriptBody(convBefore);
    RequirementBrief updated =
        BriefMappingReview.editRule(brief, "map-init", "$.personId", "$.userId", null);
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(brief, updated, twoBoundaryPlan());
    ChainPlanGraph rebuilt = reconfigureChanged(compiled, updated, impact.changedMappingIntentIds());

    assertSame(convBefore, requireSite(rebuilt, "map-conv"));
    assertEquals(scriptBefore, MappingExecutionSite.scriptBody(requireSite(rebuilt, "map-conv")));
    assertNotEquals("mapper-2", requireSite(rebuilt, "map-conv").type());
  }

  private static ChainPlanGraph compile(ChainPlanGraph topology, RequirementBrief brief) {
    return ScriptConfigurationPhase.configure(
        Mapper2ConfigurationPhase.configure(
            MappingStructurePhase.placeShells(topology, brief), brief),
        brief);
  }

  private static ChainPlanGraph reconfigureChanged(
      ChainPlanGraph graph, RequirementBrief brief, Set<String> changedIntentIds) {
    return ScriptConfigurationPhase.configure(
        Mapper2ConfigurationPhase.configure(graph, brief, changedIntentIds),
        brief,
        changedIntentIds);
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
