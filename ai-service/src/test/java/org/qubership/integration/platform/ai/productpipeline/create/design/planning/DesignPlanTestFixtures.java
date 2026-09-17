package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

final class DesignPlanTestFixtures {

  private DesignPlanTestFixtures() {}

  static ChainSemanticRevision revision() {
    return SemanticFixtures.linearOrders();
  }

  static RequirementBrief brief() {
    return new RequirementBrief("Orders", List.of(), List.of(), List.of(), List.of(), "Create order");
  }

  static CompilerRunPin pin(ChainSemanticRevision revision) {
    ResolvedCompilerDag dag =
        new ResolvedCompilerDag(
            List.of(
                node(
                    "cip-http-trigger-endpoint-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(),
                    0),
                node(
                    "cip-service-call-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-http-trigger-endpoint-generator"),
                    1)),
            List.of(),
            "dag-hash");
    return new CompilerRunPin(
        "compiler",
        "1",
        "package-hash",
        1,
        "1",
        "catalog-hash",
        dag,
        List.of(),
        Map.of(),
        Map.of(),
        List.of(),
        Kind.CHAIN_SEMANTIC_REVISION.name(),
        revision.schemaVersion(),
        revision.revisionId(),
        "revision-hash",
        revision.compilerContractVersion(),
        "contract-hash");
  }

  static CompilerRunPin pinWithSkills(ChainSemanticRevision revision, String... skillIds) {
    List<ResolvedCompilerNode> nodes =
        java.util.Arrays.stream(skillIds)
            .map(id -> node(id, List.of(), List.of(), List.of(), 0))
            .toList();
    ResolvedCompilerDag dag = new ResolvedCompilerDag(nodes, List.of(), "dag-hash");
    return new CompilerRunPin(
        "compiler",
        "1",
        "package-hash",
        1,
        "1",
        "catalog-hash",
        dag,
        List.of(),
        Map.of(),
        Map.of(),
        List.of(),
        Kind.CHAIN_SEMANTIC_REVISION.name(),
        revision.schemaVersion(),
        revision.revisionId(),
        "revision-hash",
        revision.compilerContractVersion(),
        "contract-hash");
  }

  static DesignPlanCapture validCapture(String triggerSummary, String callSummary) {
    return new DesignPlanCapture(
        List.of(
            new DesignPlanCapture.Step(
                "trigger",
                triggerSummary,
                new DesignPlanCapture.Owner(
                    OwnerKind.SKILL, "cip-http-trigger-endpoint-generator"),
                List.of(
                    new DesignPlanCapture.Claim(
                        TargetKind.ENTRY_POINT, "entry-1", ClaimRole.PRODUCER)),
                List.of()),
            new DesignPlanCapture.Step(
                "call",
                callSummary,
                new DesignPlanCapture.Owner(OwnerKind.SKILL, "cip-service-call-generator"),
                List.of(
                    new DesignPlanCapture.Claim(
                        TargetKind.SERVICE_CALL, "call-1", ClaimRole.PRODUCER)),
                List.of("trigger"))));
  }

  private static ResolvedCompilerNode node(
      String skillId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      int level) {
    return new ResolvedCompilerNode(
        skillId,
        "Planning",
        null,
        consumes,
        produces,
        dependsOn,
        null,
        List.of(),
        List.of(),
        true,
        List.of(),
        level,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }
}
