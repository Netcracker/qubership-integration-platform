package org.qubership.integration.platform.ai.chat.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptBalancingTest {

  @Test
  void truncateBalancedKeepsEnds() {
    String s = "AAAA" + "M".repeat(200) + "BBBB";
    String out = TranscriptBalancing.truncateBalanced(s, 80);
    assertTrue(out.startsWith("AAAA"));
    assertTrue(out.endsWith("BBBB"));
    assertTrue(out.contains("truncated middle"));
  }

  @Test
  void tailOnlyKeepsEnd() {
    String s = "HEAD" + "M".repeat(100) + "TAIL";
    assertEquals("TAIL", TranscriptBalancing.tailOnly(s, 4));
  }

  @Test
  void tailOnlyBlankReturnsEmpty() {
    assertEquals("", TranscriptBalancing.tailOnly(null, 10));
    assertEquals("", TranscriptBalancing.tailOnly("   ", 10));
  }

  @Test
  void trimStartIndexForBudgetDropsOldest() {
    var chunks =
        java.util.List.of(
            "User: a\n\n",
            "Assistant: b\n\n",
            "User: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n\n");
    int start = TranscriptBalancing.trimStartIndexForBudget(chunks, 60, 1);
    assertEquals(2, start);
  }
}
