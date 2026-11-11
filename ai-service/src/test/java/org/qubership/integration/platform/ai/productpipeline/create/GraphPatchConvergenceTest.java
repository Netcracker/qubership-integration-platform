package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class GraphPatchConvergenceTest {

  @Test
  void structuralPatchReactivatesOnlyDownstreamSkills() {
    PlanningSchedulerState state =
        new PlanningSchedulerState(
            dag(),
            Set.of(
                SkillArtifactType.REQUIREMENT_BRIEF.name(),
                SkillArtifactType.CHAIN_PLAN_GRAPH.name(),
                SkillArtifactType.GRAPH_PATCH_ARTIFACT.name()),
            Set.of(
                "cip-requirement-analyzer",
                "cip-naming-generator",
                "cip-structure-generator",
                "cip-routing-generator",
                "cip-security-validator"),
            Set.of(),
            Map.of(),
            0,
            2);
    GraphPatchArtifact structureChangingPatch =
        new GraphPatchArtifact(
            1,
            "patch-1",
            "cip-structure-generator",
            "base",
            "result",
            new GraphPatch("patch-1", "cip-structure-generator", List.of(), List.of(), List.of(), List.of(), List.of(), "Structure changed"),
            List.of(),
            List.of(),
            List.of(),
            "Structure changed",
            PatchApplicability.APPLICABLE,
            "invocation");

    PlanningSchedulerState next =
        CompilerDerivedPlanningSpine.convergeAfterPatchArtifact(
            state, "cip-structure-generator", structureChangingPatch);

    assertTrue(next.completedSkillIds().contains("cip-naming-generator"));
    assertFalse(next.completedSkillIds().contains("cip-routing-generator"));
    assertFalse(next.completedSkillIds().contains("cip-security-validator"));
  }

  private static ResolvedCompilerDag dag() {
    return new ResolvedCompilerDag(
        List.of(
            node("cip-requirement-analyzer", List.of(), List.of(), List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()), 0),
            node(
                "cip-naming-generator",
                List.of("cip-requirement-analyzer"),
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                1),
            node(
                "cip-structure-generator",
                List.of("cip-naming-generator"),
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                2),
            node(
                "cip-routing-generator",
                List.of("cip-structure-generator"),
                List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of(SkillArtifactType.GRAPH_PATCH_ARTIFACT.name()),
                3),
            node(
                "cip-security-validator",
                List.of("cip-routing-generator"),
                List.of(SkillArtifactType.GRAPH_PATCH_ARTIFACT.name()),
                List.of(SkillArtifactType.PRE_BUILD_VALIDATION.name()),
                4)),
        List.of(),
        "dag-1");
  }

  private static ResolvedCompilerNode node(
      String skillId,
      List<String> dependsOn,
      List<String> consumes,
      List<String> produces,
      int level) {
    return new ResolvedCompilerNode(
        skillId,
        "Generation",
        null,
        consumes,
        produces,
        dependsOn,
        "captureGraphPatch",
        List.of(),
        List.of(),
        true,
        List.of(),
        level,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null,
        GraphPatchOwnershipPolicy.denyAll());
  }
}
