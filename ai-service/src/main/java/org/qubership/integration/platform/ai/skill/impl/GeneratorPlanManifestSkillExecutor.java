package org.qubership.integration.platform.ai.skill.impl;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSupport;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifestBuilder;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillId;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

@ApplicationScoped
@SkillId("generator-plan-manifest")
public class GeneratorPlanManifestSkillExecutor implements SkillExecutor {

  private static final String SKILL_ID = "generator-plan-manifest";

  private final QipKnowledgePackRepository packRepository;
  private final GeneratorPlanManifestBuilder manifestBuilder;
  private final CompilerSkillRuntimeEligibility runtimeEligibility;

  @Inject
  public GeneratorPlanManifestSkillExecutor(
      QipKnowledgePackRepository packRepository,
      GeneratorPlanManifestBuilder manifestBuilder,
      CompilerSkillRuntimeEligibility runtimeEligibility) {
    this.packRepository = packRepository;
    this.manifestBuilder = manifestBuilder;
    this.runtimeEligibility = runtimeEligibility;
  }

  @Override
  public String skillId() {
    return SKILL_ID;
  }

  @Override
  public SkillExecutorKind kind() {
    return SkillExecutorKind.DETERMINISTIC;
  }

  @Override
  public Set<SkillArtifactType> requiredInputs() {
    return EnumSet.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
  }

  @Override
  public Set<SkillArtifactType> outputTypes() {
    return EnumSet.of(SkillArtifactType.GENERATOR_PLAN_MANIFEST, SkillArtifactType.COMPILER_STATUS);
  }

  @Override
  public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
    List<String> generationSkillIds =
        CompilerPipelineIndexSupport.generationSkillIds(packRepository.loadCompilerPipelineIndex())
            .stream()
            .filter(runtimeEligibility::allowsRuntimeAccess)
            .toList();
    var buildResult =
        manifestBuilder.build(
            packRepository.loadCompilerGeneratorPolicy(), generationSkillIds, workspace);
    return Uni.createFrom()
        .item(
            SkillExecutionResult.completed(
                List.of(
                    SkillArtifact.of(
                        SkillArtifactType.GENERATOR_PLAN_MANIFEST,
                        SKILL_ID,
                        new SkillArtifactPayload.GeneratorPlanManifestPayload(
                            buildResult.manifest())),
                    SkillArtifact.of(
                        SkillArtifactType.COMPILER_STATUS,
                        SKILL_ID,
                        new SkillArtifactPayload.CompilerStatusPayload(buildResult.status()))),
                "Generator plan manifest built"));
  }
}
