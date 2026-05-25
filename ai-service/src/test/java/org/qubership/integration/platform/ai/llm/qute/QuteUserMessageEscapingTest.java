package org.qubership.integration.platform.ai.llm.qute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuteUserMessageEscapingTest {

  @Test
  void escapesBracesAndBackslashesForQute() {
    assertEquals("plain", QuteUserMessageEscaping.escapeForAiServiceUserMessage("plain"));
    assertEquals("\\{id}", QuteUserMessageEscaping.escapeForAiServiceUserMessage("{id}"));
    // Input: backslash + '{'. Backslashes double first, then '{' is escaped.
    assertEquals(
        "\\".repeat(3) + "{", QuteUserMessageEscaping.escapeForAiServiceUserMessage("\\" + "{"));
  }

  @Test
  void nullAndEmptyPassThrough() {
    assertNull(QuteUserMessageEscaping.escapeForAiServiceUserMessage(null));
    assertEquals("", QuteUserMessageEscaping.escapeForAiServiceUserMessage(""));
  }
}
