package org.qubership.integration.platform.ai.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class MarkdownTextTest {
  @Test
  void catalogNamesCannotIntroduceMarkdownLinksOrHtml() {
    assertEquals("**A\\_B \\[x\\]\\(url\\) \\<b\\>\\*\\\\**",
        MarkdownText.bold("A_B [x](url) <b>*\\"));
  }

  @Test
  void inlineCodePreservesBackticksAndEdgeSpaces() {
    assertEquals("`chain-1`", MarkdownText.code("chain-1"));
    assertEquals("`` `name` ``", MarkdownText.code("`name`"));
    assertEquals("```a``b```", MarkdownText.code("a``b"));
    assertEquals("`  name  `", MarkdownText.code(" name "));
  }
}
