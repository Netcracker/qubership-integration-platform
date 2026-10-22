package org.qubership.integration.platform.ai.presentation;

/** Formats catalog values as literal Markdown so names cannot introduce their own markup. */
public final class MarkdownText {
  private MarkdownText() {}

  public static String bold(String value) {
    StringBuilder literal = new StringBuilder();
    for (char ch : value.toCharArray()) {
      if ((ch >= '!' && ch <= '/') || (ch >= ':' && ch <= '@')
          || (ch >= '[' && ch <= '`') || (ch >= '{' && ch <= '~')) {
        literal.append('\\');
      }
      literal.append(ch);
    }
    return "**" + literal + "**";
  }

  public static String code(String value) {
    int longest = 0;
    int run = 0;
    for (char ch : value.toCharArray()) {
      run = ch == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    String fence = "`".repeat(longest + 1);
    boolean padding = value.startsWith("`") || value.endsWith("`")
        || (value.startsWith(" ") && value.endsWith(" ") && !value.isBlank());
    return fence + (padding ? " " : "") + value + (padding ? " " : "") + fence;
  }
}
