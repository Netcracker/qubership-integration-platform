package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.TranscriptWindow;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ConsultationAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

@ApplicationScoped
@ForScenario(ScenarioType.CONSULTATION)
public class ConsultationScenario implements ScenarioHandler {
  @Inject ConsultationAgent agent;
  @Inject org.qubership.integration.platform.ai.productpipeline.knowledge.DocumentationTools documentation;
  @Inject ConversationService conversations;
  @Inject org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer memory;

  @Override
  public Multi<ChatEvent> handle(ChatRequest request, String conversationId, ScenarioType type) {
    String memoryId = "consultation:" + conversationId;
    memory.clear(memoryId);
    documentation.beginConsultationTurn(memoryId);
    String prompt =
        TranscriptWindow.format(conversations.getMessages(conversationId))
            + "\nLatest user message: " + request.getEffectiveUserText();
    return agent.chat(memoryId,
        org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping
            .escapeForAiServiceUserMessage(prompt))
        .onItem().transform(ChatEvent::token);
  }
}
