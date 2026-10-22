package org.qubership.integration.platform.ai.llm.exchange;

/** Correlation fields copied from MDC at LLM call time. */
public record LlmExchangeMdcContext(
    String conversationId, String scenarioType, String capabilityId) {

  public static LlmExchangeMdcContext none() {
    return new LlmExchangeMdcContext("(none)", "(none)", "(none)");
  }
}
