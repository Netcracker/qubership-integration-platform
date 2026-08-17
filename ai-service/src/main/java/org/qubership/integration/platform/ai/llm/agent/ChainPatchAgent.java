package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchTool;
import org.qubership.integration.platform.ai.chain.patch.ChainSnapshotTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/**
 * Constrained agent that proposes changes to a chain the user already has.
 *
 * <p>It reads before it writes, through the same lookups the CREATE agents use.
 * {@link ElementSchemaTools} answers what an element type accepts, so configuration is taken from
 * the schema rather than from memory -- a key the type does not define is refused, and the whole
 * change with it. {@link CatalogSystemTools} answers which services, specifications and operations
 * exist, which is the only way a request like "change the operation" can be settled: nobody
 * remembers an operation id, so the candidates have to be found before the reader is asked to pick
 * one.
 *
 * <p>The invocation budget covers a turn that looks several things up before proposing: a branch
 * holding two elements asks about both types, and binding a service call walks service to
 * specification to operation, with room left to submit the patch and correct it once.
 */
@RegisterAiService(
    tools = {
      ChainPatchTool.class,
      ChainSnapshotTool.class,
      ElementSchemaTools.class,
      CatalogSystemTools.class
    },
    maxSequentialToolInvocations = 10)
@ApplicationScoped
public interface ChainPatchAgent {

  @SystemMessage(fromResource = "prompts/chain-patch-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
