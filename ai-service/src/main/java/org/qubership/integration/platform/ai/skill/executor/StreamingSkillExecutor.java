package org.qubership.integration.platform.ai.skill.executor;

import io.smallrye.mutiny.Multi;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Agent-backed skill that streams chat events while running. */
public interface StreamingSkillExecutor extends SkillExecutor {

  Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace);
}
