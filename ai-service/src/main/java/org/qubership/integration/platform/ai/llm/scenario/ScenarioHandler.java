package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;

public interface ScenarioHandler {

  Multi<ChatEvent> handle(ChatRequest request, String conversationId, ScenarioType scenarioType);
}
