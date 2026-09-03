package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;

class CompilerCreateSeedProjectorTest {

  @Test
  void httpTriggerSelectsGp01AndCopiesTriggerProperties() {
    ChainPlanGraph graph =
        graph(
            new ChainPlanNode(
                "http-in",
                "http-trigger",
                "Receive order",
                null,
                null,
                List.of(new PlanProperty("contextPath", "/orders"))),
            new ChainPlanNode("call-1", "service-call", "Create order", null, null, List.of()));

    SelectedPattern pattern = CompilerCreateSeedProjector.pattern(graph);
    ElementSkeleton skeleton = CompilerCreateSeedProjector.skeleton(graph, pattern.patternId());
    ConfiguredTriggerSet triggers = CompilerCreateSeedProjector.triggerSet(graph);

    assertEquals("GP-01", pattern.patternId());
    assertEquals(List.of("http-in"), skeleton.entryPointRoleIds());
    assertEquals("http-trigger", role(skeleton, "http-in").elementType());
    assertEquals("service-call", role(skeleton, "call-1").elementType());
    assertEquals(1, triggers.triggers().size());
    ConfiguredTrigger trigger = triggers.triggers().get(0);
    assertEquals("http-in", trigger.roleId());
    assertEquals("http-trigger", trigger.elementType());
    assertEquals("/orders", trigger.properties().get(0).value());
  }

  @Test
  void asyncApiTriggerSelectsGp02() {
    ChainPlanGraph graph =
        graph(
            new ChainPlanNode(
                "trigger-onTaskStart",
                "async-api-trigger",
                "Receive OM Task Start",
                null,
                null,
                List.of(new PlanProperty("integrationOperationId", "op-om"))));

    SelectedPattern pattern = CompilerCreateSeedProjector.pattern(graph);
    ConfiguredTriggerSet triggers = CompilerCreateSeedProjector.triggerSet(graph);

    assertEquals("GP-02", pattern.patternId());
    assertEquals(1, triggers.triggers().size());
    assertEquals("async-api-trigger", triggers.triggers().get(0).elementType());
    assertEquals("op-om", triggers.triggers().get(0).properties().get(0).value());
  }

  @Test
  void completeTaskSkeletonKeepsBehaviorOwnedScriptRole() {
    ChainPlanGraph graph =
        graph(
            new ChainPlanNode("http-in", "http-trigger", "In", null, null, List.of()),
            new ChainPlanNode(
                SemanticFixtures.COMPLETE_TASK_NODE_ID,
                "script",
                "Complete task",
                null,
                null,
                List.of()),
            new ChainPlanNode("call-1", "service-call", "Create order", null, null, List.of()));

    ElementSkeleton skeleton = CompilerCreateSeedProjector.skeleton(graph, "GP-01");

    assertEquals("script", role(skeleton, SemanticFixtures.COMPLETE_TASK_NODE_ID).elementType());
    assertTrue(
        skeleton.elementRoles().stream().noneMatch(r -> "mapper-2".equals(r.elementType())));
  }

  @Test
  void createSeedPreSatisfiesPatternAndTriggerSkills() {
    ChainPlanGraph graph =
        graph(new ChainPlanNode("http-in", "http-trigger", "In", null, null, List.of()));
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forCreate(
            "conv-1", emptyBrief(), SemanticFixtures.linearOrders(), graph, List.of());

    assertTrue(seed.preSatisfiedSkillIds().contains(CompilerExecutionSeed.PATTERN_SELECTOR_SKILL));
    assertTrue(seed.preSatisfiedSkillIds().contains(CompilerExecutionSeed.TRIGGER_GENERATOR_SKILL));
    assertTrue(seed.presentArtifactTypes().contains("SELECTED_PATTERN"));
    assertTrue(seed.presentArtifactTypes().contains("ELEMENT_SKELETON"));
    assertTrue(seed.presentArtifactTypes().contains("CONFIGURED_TRIGGER_SET"));
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph(
        "1.0", new ChainSection("chain", "Chain"), List.of(nodes), List.of());
  }

  private static ElementRole role(ElementSkeleton skeleton, String roleId) {
    return skeleton.elementRoles().stream()
        .filter(role -> roleId.equals(role.roleId()))
        .findFirst()
        .orElseThrow();
  }

  private static RequirementBrief emptyBrief() {
    return new RequirementBrief(
        "goal",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "summary",
        null,
        "",
        List.of(),
        List.of());
  }
}
