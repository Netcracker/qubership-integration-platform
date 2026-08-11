package org.qubership.integration.platform.ai.llm.qute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QuteUserMessageEscapingTest {

  @Test
  void escapesBracesAndBackslashesForQute() {
    assertEquals("plain", QuteUserMessageEscaping.escapeForAiServiceUserMessage("plain"));
    assertEquals("\\{id}", QuteUserMessageEscaping.escapeForAiServiceUserMessage("{id}"));
    assertEquals(
        "\\".repeat(3) + "{", QuteUserMessageEscaping.escapeForAiServiceUserMessage("\\" + "{"));
  }

  @Test
  void nullAndEmptyPassThrough() {
    assertNull(QuteUserMessageEscaping.escapeForAiServiceUserMessage(null));
    assertEquals("", QuteUserMessageEscaping.escapeForAiServiceUserMessage(""));
  }
}
