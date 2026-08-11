package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.compiler.ConfiguredTriggerSetCaptureTool;
import org.qubership.integration.platform.ai.compiler.CompilerGraphPatchTool;
import org.qubership.integration.platform.ai.compiler.NamingManifestCaptureTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/**
 * Generic agent for compiler skill execution.
 *
 * <p>System prompt: assembled at build time from qip-base-system.md + roles/compiler-skill.md
 * → prompts/compiler-skill-system.md.
 */
@RegisterAiService(
    tools = {
      CompilerGraphPatchTool.class,
      NamingManifestCaptureTool.class,
      ConfiguredTriggerSetCaptureTool.class,
      ElementSchemaTools.class
    },
    maxSequentialToolInvocations = 3)
@ApplicationScoped
public interface CompilerSkillAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/compiler-skill-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
