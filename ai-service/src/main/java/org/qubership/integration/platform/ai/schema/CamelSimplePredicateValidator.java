package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Optional;
import org.apache.camel.language.simple.SimpleExpressionParser;
import org.apache.camel.language.simple.SimplePredicateParser;
import org.apache.camel.language.simple.types.SimpleParserException;
import org.apache.camel.language.simple.types.SimpleToken;

/** Checks Simple syntax and literal date patterns without evaluating runtime functions. */
public final class CamelSimplePredicateValidator {
  private CamelSimplePredicateValidator() {}

  public static Optional<String> validateProperty(String elementType, String key, JsonNode value) {
    return "if".equals(elementType) && "condition".equals(key) && value.isTextual()
        ? validate(value.asText()) : Optional.empty();
  }

  public static Optional<String> validate(String predicate) {
    try {
      // Report date-pattern defects even when predicate operators also need repair.
      new DatePatternParser(predicate).validatePatterns();
      new SimplePredicateParser(null, predicate, false, null).parseTokens();
      return Optional.empty();
    } catch (SimpleParserException e) {
      return Optional.of("Invalid Camel Simple predicate at position " + e.getIndex() + ": " + e.getMessage());
    }
  }

  private static final class DatePatternParser extends SimpleExpressionParser {
    private DatePatternParser(String expression) {
      super(null, expression, false, null);
    }

    private void validatePatterns() {
      parseTokens();
      var starts = new ArrayDeque<SimpleToken>();
      for (SimpleToken token : tokens) {
        if (token.getType().isFunctionStart()) {
          starts.push(token);
        } else if (token.getType().isFunctionEnd() && !starts.isEmpty()) {
          // Camel also tokenizes literal regex braces as function-end tokens.
          SimpleToken start = starts.pop();
          String function = expression.substring(start.getIndex() + start.getLength(), token.getIndex());
          validateDatePattern(function, start.getIndex());
        }
      }
    }

    private static void validateDatePattern(String function, int position) {
      if (function.contains("${") || function.contains("$simple{")) {
        return;
      }
      String pattern;
      if (function.startsWith("date:")) {
        String[] parts = function.substring("date:".length()).split(":", 2);
        if (parts.length < 2) {
          return;
        }
        pattern = parts[1];
      } else if (function.startsWith("date-with-timezone:")) {
        String[] parts = function.substring("date-with-timezone:".length()).split(":", 3);
        if (parts.length < 3) {
          throw new SimpleParserException("date-with-timezone requires command:timezone:pattern", position);
        }
        pattern = parts[2];
      } else {
        return;
      }
      try {
        new SimpleDateFormat(pattern);
      } catch (IllegalArgumentException error) {
        throw new SimpleParserException("Invalid date pattern '" + pattern + "': " + error.getMessage()
            + ". Use a SimpleDateFormat pattern and preserve the approved branch behavior.", position);
      }
    }
  }
}
