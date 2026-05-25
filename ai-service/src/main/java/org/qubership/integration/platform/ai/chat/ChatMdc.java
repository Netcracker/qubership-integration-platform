package org.qubership.integration.platform.ai.chat;

/**
 * MDC keys used across the chat pipeline so tool execution can recover the HTTP conversation id
 * when framework-injected ids diverge from the SSE session key.
 */
public final class ChatMdc {

  public static final String CONVERSATION_ID = "conversationId";

  /**
   * {@link org.qubership.integration.platform.ai.model.ScenarioType#name()} for the active routed
   * scenario.
   */
  public static final String SCENARIO_TYPE = "scenarioType";

  private ChatMdc() {}
}
