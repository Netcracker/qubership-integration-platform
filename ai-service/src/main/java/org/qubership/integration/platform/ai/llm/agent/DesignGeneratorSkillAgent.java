package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;

/**
 * Agent for immutable {@code cip-design-generator}. Exposes only APIHub tools; never invents
 * operation bindings.
 *
 * <p>The system message is the skill's own template, copied onto the classpath at build time from
 * {@code integration-platform-skills/.apm/skills/cip-design-generator/templates/ids_template.md}
 * (see the {@code copy-ids-template-from-skill} execution in {@code pom.xml}). Edit neither this
 * resource nor the upstream skill: behavior changes for a {@code cip-*} skill belong in
 * {@code integration-platform-skills/addons/skills/cip-design-generator.addon.md}, which the
 * runtime loads through {@code qip.ai.qipknowledge.addon-pack-root}.
 */
@RegisterAiService(tools = {ApiHubMcpTools.class}, maxSequentialToolInvocations = 8)
@ApplicationScoped
public interface DesignGeneratorSkillAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "product-pipelines/templates/ids_template.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
