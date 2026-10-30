package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.apache.camel.language.simple.SimplePredicateParser;
import org.apache.camel.language.simple.types.SimpleParserException;

/** Checks Simple predicate syntax without resolving or evaluating runtime functions. */
public final class CamelSimplePredicateValidator {
  private CamelSimplePredicateValidator() {}

  public static Optional<String> validateProperty(String elementType, String key, JsonNode value) {
    return "if".equals(elementType) && "condition".equals(key) && value.isTextual()
        ? validate(value.asText()) : Optional.empty();
  }

  public static Optional<String> validate(String predicate) {
    try {
      new SimplePredicateParser(null, predicate, false, null).parseTokens();
      return Optional.empty();
    } catch (SimpleParserException e) {
      return Optional.of("Invalid Camel Simple predicate at position " + e.getIndex() + ": " + e.getMessage());
    }
  }
}
