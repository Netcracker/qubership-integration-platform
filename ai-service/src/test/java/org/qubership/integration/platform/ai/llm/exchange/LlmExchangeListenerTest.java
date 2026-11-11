package org.qubership.integration.platform.ai.llm.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;

class LlmExchangeListenerTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(ChatMdc.SCENARIO_TYPE);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  @Test
  void responseContextKeepsTheRequestOwnerAfterThreadMdcChanges() {
    var original = new LlmExchangeMdcContext("original-conversation", "CREATE", "cip-script-generator");
    java.util.Map<Object, Object> attributes = new java.util.HashMap<>();
    attributes.put(LlmExchangeListener.CONTEXT_KEY, original);
    MDC.put(ChatMdc.CONVERSATION_ID, "different-conversation");
    assertEquals(original, LlmExchangeListener.exchangeContext(attributes));
  }

  @Test
  void readMdcContextUsesNoneWhenMissing() {
    LlmExchangeMdcContext context = LlmExchangeListener.readMdcContext();

    assertEquals("(none)", context.conversationId());
    assertEquals("(none)", context.scenarioType());
    assertEquals("(none)", context.capabilityId());
  }

  @Test
  void readMdcContextReadsValues() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-42");
    MDC.put(ChatMdc.SCENARIO_TYPE, "CREATE_CHAIN_PLAN");
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, "cip-structure-generator");

    LlmExchangeMdcContext context = LlmExchangeListener.readMdcContext();

    assertEquals("conv-42", context.conversationId());
    assertEquals("CREATE_CHAIN_PLAN", context.scenarioType());
    assertEquals("cip-structure-generator", context.capabilityId());
  }

  @Test
  void readMdcContextTreatsBlankAsNone() {
    MDC.put(ChatMdc.CONVERSATION_ID, "   ");

    assertEquals("(none)", LlmExchangeListener.readMdcContext().conversationId());
  }
}
