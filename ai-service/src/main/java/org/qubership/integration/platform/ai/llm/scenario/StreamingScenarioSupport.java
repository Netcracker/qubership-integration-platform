package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.ConversationPromptAssembler;
import org.qubership.integration.platform.ai.chat.prompt.PromptAssemblyRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.function.BiFunction;

/** Shared prompt assembly, logging, and agent streaming for {@link ScenarioHandler} beans. */
@ApplicationScoped
public class StreamingScenarioSupport {

  private final ConversationPromptAssembler promptAssembler;

  public StreamingScenarioSupport(ConversationPromptAssembler promptAssembler) {
    this.promptAssembler = promptAssembler;
  }

  public String assemblePrompt(String conversationId, ChatRequest request, PromptProfile profile) {
    return promptAssembler.assemble(PromptAssemblyRequest.of(conversationId, request, profile));
  }

  public Multi<String> streamAgent(
      Logger log,
      ScenarioType scenarioType,
      String conversationId,
      ChatRequest request,
      PromptProfile profile,
      BiFunction<String, String, Multi<String>> chatFn,
      boolean wrapHitl,
      HitlStreamRegistry hitlStreamRegistry) {
    ScenarioLogging.logScenarioStart(log, scenarioType, conversationId, request);
    String userMessage = assemblePrompt(conversationId, request, profile);
    Multi<String> stream =
        chatFn
            .apply(conversationId, userMessage)
            .onFailure()
            .invoke(err -> log.errorf(err, "%s failed", scenarioType));
    if (wrapHitl && hitlStreamRegistry != null) {
      return hitlStreamRegistry.wrapWithHitl(conversationId, stream);
    }
    return stream;
  }
}
