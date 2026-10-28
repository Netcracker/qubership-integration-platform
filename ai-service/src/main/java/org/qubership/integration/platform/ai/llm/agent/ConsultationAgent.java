package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.quarkus.arc.Arc;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DocumentationTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/** Read-only platform assistance independent of the private creation package. */
@RegisterAiService(toolProviderSupplier = ConsultationAgent.ToolsSupplier.class,
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface ConsultationAgent {
  @SystemMessage(fromResource = "prompts/consultation-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String message);

  @ApplicationScoped
  class ToolsSupplier implements Supplier<ToolProvider> {
    @Override
    public ToolProvider get() {
      return request -> {
        var result = ToolProviderResult.builder();
        var factory = Arc.container().instance(io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory.class).get();
        for (Class<?> type : List.of(ElementSchemaTools.class, DocumentationTools.class)) {
          for (var method : io.quarkiverse.langchain4j.runtime.ToolsRecorder.getMetadata().get(type.getName())) {
            var executor = factory.create(new io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor.Context(
                Arc.container().instance(type).get(), method.invokerClassName(), method.methodName(),
                method.argumentMapperClassName(), method.executionModel(), method.returnBehavior(), false, method));
            result.add(method.toolSpecification(), (call, memoryId) ->
                executor.executeWithContext(call, request.invocationContext()).resultText());
          }
        }
        return result.build();
      };
    }
  }
}
