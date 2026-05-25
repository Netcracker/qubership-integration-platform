package org.qubership.integration.platform.ai.llm.qute;

/**
 * Quarkus LangChain4j renders {@code @UserMessage} string parameters with Qute. User-supplied
 * text that contains sequences like {@code {id}} is otherwise parsed as expressions and fails at
 * render time when those names are not in the template data map.
 *
 * <p>Per the Qute reference, a backslash before an opening brace escapes the delimiter so the
 * following text is not evaluated as an expression. Backslashes are doubled first so user-supplied
 * backslashes remain literal.
 */
public final class QuteUserMessageEscaping {

  private QuteUserMessageEscaping() {}

  /**
   * Prepares arbitrary UTF-8 text for safe use as a Quarkus AI service user prompt / template
   * parameter value.
   */
  public static String escapeForAiServiceUserMessage(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return text.replace("\\", "\\\\").replace("{", "\\{");
  }
}
