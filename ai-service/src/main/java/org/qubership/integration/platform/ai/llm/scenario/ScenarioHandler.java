package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * Contract for all scenario handlers.
 *
 * <p>Each handler takes the incoming chat request and a (possibly new) conversation ID, and returns
 * a {@link Multi} of string tokens that will be streamed to the client as SSE.
 *
 * <p>Special token prefixes:
 *
 * <ul>
 *   <li>{@code [HITL]{...json...}} — a HITL checkpoint event; the API layer emits it with SSE event
 *       type {@code hitl_checkpoint}
 * </ul>
 */
public interface ScenarioHandler {

  Multi<String> handle(ChatRequest request, String conversationId, ScenarioType scenarioType);
}
