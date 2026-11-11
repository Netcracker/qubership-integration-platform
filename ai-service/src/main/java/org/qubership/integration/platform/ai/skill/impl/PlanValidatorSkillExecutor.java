package org.qubership.integration.platform.ai.skill.impl;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.executor.StreamingSkillExecutor;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillId;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * Pre-build plan validator for the BUILD_CHAIN spine. Not a CIP compiler pack skill — runtime
 * contract and prompt live in ai-service.
 */
@ApplicationScoped
@SkillId(InternalPipelineSkills.PLAN_VALIDATOR)
public class PlanValidatorSkillExecutor implements StreamingSkillExecutor {

  private final CompilerSkillRuntime compilerSkillRuntime;

  @Inject
  public PlanValidatorSkillExecutor(CompilerSkillRuntime compilerSkillRuntime) {
    this.compilerSkillRuntime = Objects.requireNonNull(compilerSkillRuntime, "compilerSkillRuntime");
  }

  @Override
  public String skillId() {
    return InternalPipelineSkills.PLAN_VALIDATOR;
  }

  @Override
  public SkillExecutorKind kind() {
    return SkillExecutorKind.AGENT;
  }

  @Override
  public Set<SkillArtifactType> requiredInputs() {
    return EnumSet.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
  }

  @Override
  public Set<SkillArtifactType> outputTypes() {
    return EnumSet.of(
        SkillArtifactType.PRE_BUILD_VALIDATION, SkillArtifactType.PLAN_CAPTURE_OUTCOME);
  }

  @Override
  public Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace) {
    return compilerSkillRuntime.runStreaming(context, workspace, skillId());
  }

  @Override
  public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
    return compilerSkillRuntime.run(context, workspace, skillId());
  }
}
