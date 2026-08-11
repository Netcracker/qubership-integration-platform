package org.qubership.integration.platform.ai.skill.executor;

import io.smallrye.mutiny.Uni;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

import java.util.Set;

/** Runs one compiler skill against a conversation workspace. */
public interface SkillExecutor {

  String skillId();

  SkillExecutorKind kind();

  Set<SkillArtifactType> requiredInputs();

  Set<SkillArtifactType> outputTypes();

  Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace);
}
