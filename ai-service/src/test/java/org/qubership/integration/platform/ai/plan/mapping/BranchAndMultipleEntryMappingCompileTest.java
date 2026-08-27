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

class BranchAndMultipleEntryMappingCompileTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PAYLOAD = "{\"userId\":\"u-1\",\"name\":\"Ada\"}";

  @Test
  void twoEntryPointsWithOwnIntentsDoNotCollapseSites() throws Exception {
    RequirementBrief brief =
        briefWith(
            List.of(
                copyIntent("map-http", "http-trigger", "call-1", "$.userId", "$.personId"),
                copyIntent("map-kafka", "kafka-trigger", "call-1", "$.userId", "$.kafkaUser")));
    ChainPlanGraph compiled = compile(twoEntryGraph(), brief);

    ChainPlanNode httpSite = requireSite(compiled, "map-http");
    ChainPlanNode kafkaSite = requireSite(compiled, "map-kafka");
    assertNotEquals(httpSite.nodeId(), kafkaSite.nodeId());
    assertTrue(hasEdge(compiled, "http-trigger", httpSite.nodeId()));
    assertTrue(hasEdge(compiled, httpSite.nodeId(), "call-1"));
    assertTrue(hasEdge(compiled, "kafka-trigger", kafkaSite.nodeId()));
    assertTrue(hasEdge(compiled, kafkaSite.nodeId(), "call-1"));
    assertFalse(hasEdge(compiled, "http-trigger", "call-1"));
    assertFalse(hasEdge(compiled, "kafka-trigger", "call-1"));
    assertTrue(MappingExecutionSiteValidator.validate(compiled, brief.mappingIntents()).isEmpty());

    JsonNode httpBody =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(
                compiled, List.of("http-trigger", httpSite.nodeId(), "call-1"), PAYLOAD));
    assertEquals("u-1", httpBody.path("personId").asText());
    assertTrue(httpBody.path("kafkaUser").isMissingNode());

    JsonNode kafkaBody =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(
                compiled, List.of("kafka-trigger", kafkaSite.nodeId(), "call-1"), PAYLOAD));
    assertEquals("u-1", kafkaBody.path("kafkaUser").asText());
    assertTrue(kafkaBody.path("personId").isMissingNode());
  }

  @Test
  void entryWithoutIntentKeepsDirectPassThrough() throws Exception {
    RequirementBrief brief =
        briefWith(List.of(copyIntent("map-http", "http-trigger", "call-1", "$.userId", "$.personId")));
    ChainPlanGraph compiled = compile(twoEntryGraph(), brief);

    ChainPlanNode httpSite = requireSite(compiled, "map-http");
    assertEquals(1, transformSites(compiled).size());
    assertTrue(hasEdge(compiled, "kafka-trigger", "call-1"));
    assertFalse(hasEdge(compiled, "http-trigger", "call-1"));

    JsonNode kafkaBody =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(compiled, List.of("kafka-trigger", "call-1"), PAYLOAD));
    assertEquals("u-1", kafkaBody.path("userId").asText());
    assertTrue(kafkaBody.path("personId").isMissingNode());

    JsonNode httpBody =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(
                compiled, List.of("http-trigger", httpSite.nodeId(), "call-1"), PAYLOAD));
    assertEquals("u-1", httpBody.path("personId").asText());
  }

  @Test
  void branchSpecificMappingLeavesSiblingPassThrough() throws Exception {
    RequirementBrief brief =
        briefWith(List.of(copyIntent("map-b", "router", "call-b", "$.userId", "$.personId")));
    ChainPlanGraph compiled = compile(branchedMergeGraph(), brief);

    ChainPlanNode siteB = requireSite(compiled, "map-b");
    assertEquals(1, transformSites(compiled).size());
    assertTrue(hasEdge(compiled, "router", "call-a", "branch-a"));
    assertTrue(hasEdge(compiled, "router", siteB.nodeId(), "branch-b"));
    assertTrue(hasEdge(compiled, siteB.nodeId(), "call-b", "branch-b"));
    assertFalse(hasEdge(compiled, "router", "call-b"));
    assertTrue(hasEdge(compiled, "call-a", "next"));
    assertTrue(hasEdge(compiled, "call-b", "next"));
    assertTrue(MappingExecutionSiteValidator.validate(compiled, brief.mappingIntents()).isEmpty());

    JsonNode branchA =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(
                compiled, List.of("http-trigger", "router", "call-a", "next"), PAYLOAD));
    assertEquals("u-1", branchA.path("userId").asText());
    assertTrue(branchA.path("personId").isMissingNode());

    JsonNode branchB =
        JSON.readTree(
            MappingFlowExecutor.applyAlong(
                compiled,
                List.of("http-trigger", "router", siteB.nodeId(), "call-b", "next"),
                PAYLOAD));
    assertEquals("u-1", branchB.path("personId").asText());
    assertTrue(branchB.path("userId").isMissingNode());
  }

  @Test
  void mergeDoesNotInventCombinedMappingNode() {
    RequirementBrief brief =
        briefWith(List.of(copyIntent("map-b", "router", "call-b", "$.userId", "$.personId")));
    ChainPlanGraph compiled = compile(branchedMergeGraph(), brief);

    ChainPlanNode next = node(compiled, "next");
    assertFalse(MappingExecutionSite.isTransformShell(next));
    assertEquals(2, incomingCount(compiled, "next"));
    assertEquals(1, incomingCount(compiled, requireSite(compiled, "map-b").nodeId()));
    assertEquals(1, transformSites(compiled).size());
    assertTrue(hasEdge(compiled, "call-a", "next"));
    assertTrue(hasEdge(compiled, "call-b", "next"));
    assertTrue(MappingExecutionSiteValidator.validate(compiled, brief.mappingIntents()).isEmpty());
  }

  @Test
  void changingOneBranchIntentDoesNotRewriteTheOtherSite() {
    RequirementBrief brief =
        briefWith(
            List.of(
                copyIntent("map-a", "router", "call-a", "$.userId", "$.personId"),
                copyIntent("map-b", "router", "call-b", "$.name", "$.fullName")));
    ChainPlanGraph compiled = compile(branchedMergeGraph(), brief);
    ChainPlanNode siteBBefore = requireSite(compiled, "map-b");
    String configB = MappingExecutionSite.mappingDescription(siteBBefore);
    String configA = MappingExecutionSite.mappingDescription(requireSite(compiled, "map-a"));

    RequirementBrief updated =
        BriefMappingReview.editRule(brief, "map-a", "$.personId", "$.accountId", null);
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(brief, updated, twoBranchPlan());

    assertEquals(Set.of("map-a"), impact.changedMappingIntentIds());
    assertTrue(impact.invalidatedPlanStepIds().contains("step-transform-map-a"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-transform-map-b"));

    ChainPlanGraph rebuilt = reconfigureChanged(compiled, updated, impact.changedMappingIntentIds());
    ChainPlanNode siteBAfter = requireSite(rebuilt, "map-b");
    assertSame(siteBBefore, siteBAfter);
    assertEquals(configB, MappingExecutionSite.mappingDescription(siteBAfter));
    assertNotEquals(
        configA, MappingExecutionSite.mappingDescription(requireSite(rebuilt, "map-a")));
    assertTrue(
        MappingExecutionSite.mappingDescription(requireSite(rebuilt, "map-a"))
            .contains("accountId"));
    assertTrue(hasEdge(rebuilt, "router", requireSite(rebuilt, "map-a").nodeId(), "branch-a"));
    assertTrue(hasEdge(rebuilt, "router", siteBAfter.nodeId(), "branch-b"));
    List<ValidationIssue> issues =
        MappingExecutionSiteValidator.validate(rebuilt, updated.mappingIntents());
    assertTrue(issues.isEmpty(), issues.toString());
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

  private static MappingIntent copyIntent(
      String mappingIntentId, String sourceRef, String targetRef, String sourcePath, String targetPath) {
    return new MappingIntent(
        mappingIntentId,
        sourceRef,
        MappingPort.OUTPUT,
        targetRef,
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                sourcePath, targetPath, null, MappingRuleStatus.USER_DEFINED)));
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

  private static ChainPlanGraph twoEntryGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "http-trigger",
                "http-trigger",
                "HTTP trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/orders"),
                    new PlanProperty("httpMethodRestrict", "POST"))),
            new ChainPlanNode(
                "kafka-trigger",
                "kafka-trigger-2",
                "Kafka trigger",
                null,
                null,
                List.of(new PlanProperty("topics", "orders"))),
            new ChainPlanNode("call-1", "service-call", "Create task", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e-http-call", "http-trigger", "call-1", null),
            new ChainPlanEdge("e-kafka-call", "kafka-trigger", "call-1", null)));
  }

  private static ChainPlanGraph branchedMergeGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "http-trigger",
                "http-trigger",
                "HTTP trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/orders"),
                    new PlanProperty("httpMethodRestrict", "POST"))),
            new ChainPlanNode("router", "condition", "Route by type", null, null, List.of()),
            new ChainPlanNode("branch-a", "if", "Branch A", "router", null, List.of()),
            new ChainPlanNode("branch-b", "else", "Branch B", "router", null, List.of()),
            new ChainPlanNode("call-a", "service-call", "Call A", "branch-a", null, List.of()),
            new ChainPlanNode("call-b", "service-call", "Call B", "branch-b", null, List.of()),
            new ChainPlanNode("next", "service-call", "Shared next", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e-trigger-router", "http-trigger", "router", null),
            new ChainPlanEdge("e-router-a", "router", "call-a", "branch-a"),
            new ChainPlanEdge("e-router-b", "router", "call-b", "branch-b"),
            new ChainPlanEdge("e-a-next", "call-a", "next", null),
            new ChainPlanEdge("e-b-next", "call-b", "next", null)));
  }

  private static DesignExecutionPlan twoBranchPlan() {
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
                "step-transform-map-a",
                1,
                "Configure mapper for map-a",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-transform-map-b",
                2,
                "Configure mapper for map-b",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-hash",
        Map.of("cip-transformation-generator", "h2"),
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

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing node " + nodeId));
  }

  private static List<ChainPlanNode> transformSites(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(MappingExecutionSite::isTransformShell).toList();
  }

  private static boolean hasEdge(ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    return graph.edges().stream()
        .anyMatch(edge -> fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId()));
  }

  private static boolean hasEdge(
      ChainPlanGraph graph, String fromNodeId, String toNodeId, String scopeNodeId) {
    return graph.edges().stream()
        .anyMatch(
            edge ->
                fromNodeId.equals(edge.fromNodeId())
                    && toNodeId.equals(edge.toNodeId())
                    && scopeNodeId.equals(edge.scopeNodeId()));
  }

  private static int incomingCount(ChainPlanGraph graph, String nodeId) {
    int count = 0;
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.toNodeId())) {
        count++;
      }
    }
    return count;
  }
}
