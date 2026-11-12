package org.qubership.integration.platform.ai.llm.qute;

/**
 * Quarkus LangChain4j renders {@code @UserMessage} string parameters with Qute. User-supplied
 * text that contains sequences like {@code {id}} is otherwise parsed as expressions and fails at
 * render time when those names are not in the template data map.
 *
 * <p>Literal blocks preserve backslashes. Isolate braces in separate blocks because the Mustache
 * parser hook rewrites doubled delimiters before Qute parses literal text.
 */
public final class QuteUserMessageEscaping {

  private QuteUserMessageEscaping() {}

  /** Prepares arbitrary UTF-8 text for safe use as a Quarkus AI service user message parameter. */
  public static String escapeForAiServiceUserMessage(String text) {
    if (text == null || text.indexOf('{') < 0 && text.indexOf('}') < 0) {
      return text;
    }
    var literal = new StringBuilder("{|");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '{' || character == '}') {
        literal.append("|}{|").append(character).append("|}{|");
      } else {
        literal.append(character);
      }
    }
    return literal.append("|}").toString();
  }
}
