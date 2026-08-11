package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Tolerant repair for {@code repairScriptBodies} tool-argument JSON.
 *
 * <p>Same goal as {@link org.qubership.integration.platform.ai.plan.model.PlanPropertyListDeserializer}
 * and {@link PropertyPatchCapture}: keep LangChain4j from throwing {@code ToolArgumentsException}
 * when the LLM embeds Groovy that contains unescaped {@code "} (classic R-504
 * {@code exchange.in.body = '{"error": ...}'} mistake). Escapes raw quotes inside {@code "script"}
 * string values so Jackson can bind the capture object.
 */
public final class ScriptBodyToolArgumentsSanitizer {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCRIPT_KEY = "\"script\"";

  private ScriptBodyToolArgumentsSanitizer() {}

  /**
   * Returns repaired JSON when the original does not parse and quote-escaping inside {@code script}
   * values makes it parse. Empty when the input is already valid or cannot be repaired.
   */
  public static Optional<String> sanitizeIfNeeded(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return Optional.empty();
    }
    if (isValidJson(argumentsJson)) {
      return Optional.empty();
    }
    String repaired = escapeUnescapedQuotesInScriptValues(argumentsJson);
    if (repaired.equals(argumentsJson) || !isValidJson(repaired)) {
      return Optional.empty();
    }
    return Optional.of(repaired);
  }

  static boolean isValidJson(String json) {
    try {
      MAPPER.readTree(json);
      return true;
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  /**
   * Walks the raw JSON text and escapes unescaped {@code "} characters that appear inside values of
   * keys named {@code script}, until a quote that looks like the string terminator.
   */
  static String escapeUnescapedQuotesInScriptValues(String json) {
    StringBuilder out = new StringBuilder(json.length() + 32);
    int index = 0;
    while (index < json.length()) {
      int scriptKey = indexOfScriptKey(json, index);
      if (scriptKey < 0) {
        out.append(json, index, json.length());
        break;
      }
      out.append(json, index, scriptKey);
      int valueOpen = findScriptValueOpenQuote(json, scriptKey);
      if (valueOpen < 0) {
        out.append(json, scriptKey, json.length());
        break;
      }
      out.append(json, scriptKey, valueOpen + 1);
      int cursor = valueOpen + 1;
      while (cursor < json.length()) {
        char current = json.charAt(cursor);
        if (current == '\\' && cursor + 1 < json.length()) {
          out.append(current).append(json.charAt(cursor + 1));
          cursor += 2;
          continue;
        }
        if (current == '"') {
          if (looksLikeJsonStringEnd(json, cursor)) {
            out.append('"');
            cursor++;
            break;
          }
          out.append('\\').append('"');
          cursor++;
          continue;
        }
        out.append(current);
        cursor++;
      }
      index = cursor;
    }
    return out.toString();
  }

  private static int indexOfScriptKey(String json, int fromIndex) {
    int search = fromIndex;
    while (search < json.length()) {
      int at = json.indexOf(SCRIPT_KEY, search);
      if (at < 0) {
        return -1;
      }
      if (at > 0) {
        char before = json.charAt(at - 1);
        if (before == '\\' || Character.isLetterOrDigit(before) || before == '_') {
          search = at + SCRIPT_KEY.length();
          continue;
        }
      }
      return at;
    }
    return -1;
  }

  private static int findScriptValueOpenQuote(String json, int scriptKeyIndex) {
    int colon = json.indexOf(':', scriptKeyIndex + SCRIPT_KEY.length());
    if (colon < 0) {
      return -1;
    }
    int cursor = colon + 1;
    while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
      cursor++;
    }
    if (cursor >= json.length() || json.charAt(cursor) != '"') {
      return -1;
    }
    return cursor;
  }

  private static boolean looksLikeJsonStringEnd(String json, int quoteIndex) {
    int cursor = quoteIndex + 1;
    while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
      cursor++;
    }
    if (cursor >= json.length()) {
      return true;
    }
    char next = json.charAt(cursor);
    if (next == ',' || next == ']') {
      return true;
    }
    if (next != '}') {
      return false;
    }
    // Distinguish real JSON end (`"}, "rationale"` / `"}]`) from Groovy `'"}'` continuation.
    int after = cursor + 1;
    while (after < json.length() && Character.isWhitespace(json.charAt(after))) {
      after++;
    }
    if (after >= json.length()) {
      return true;
    }
    char afterBrace = json.charAt(after);
    return afterBrace == ',' || afterBrace == ']' || afterBrace == '}';
  }
}
