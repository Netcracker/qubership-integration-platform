package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CamelSimplePredicateValidatorTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "${date:now:minute} == 0",
      "${date:now:yyyy-QQ} != ''",
      "${date-with-timezone:now:UTC:minute} == 0",
      "${header.enabled} == true && '${date:now:minute}' != ''",
      "${date:now:minute} % 2 == 0"
  })
  void rejectsInvalidLiteralDatePatterns(String predicate) {
    var result = CamelSimplePredicateValidator.validate(predicate);
    assertTrue(result.isPresent(), predicate + " => " + result);
    assertTrue(result.orElseThrow().contains("date pattern"), result.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "${header.minute} == 2",
      "${date:now:mm} regex '.*[02468]'",
      "${date-with-timezone:now:UTC:HH:mm:ss} != ''",
      "${date:now:yyyy-MM-dd'T'HH:mm:ss} != ''",
      "${date:now} != null",
      "${date:now:${header.datePattern}} != ''",
      "${body} regex 'a{2}'",
      "${body} regex 'a\\{2\\}'",
      "${bean:runtimePolicy.approved} == true",
      "${body} != null && ${exchangeProperty.enabled} == true"
  })
  void acceptsPredicateSyntaxWithoutResolvingRuntimeFunctions(String predicate) {
    assertEquals(Optional.empty(), CamelSimplePredicateValidator.validate(predicate), predicate);
  }

  @Test
  void leavesScriptBodiesOutsidePredicateValidation() {
    assertEquals(Optional.empty(), CamelSimplePredicateValidator.validateProperty(
        "script", "script", new ObjectMapper().valueToTree("return minute % 2 == 0")));
  }

  @Test
  void reportsMalformedClosingBraceAsValidationFeedback() {
    var error = assertDoesNotThrow(() -> CamelSimplePredicateValidator.validate("}"));
    assertTrue(error.isPresent(), error.toString());
    assertTrue(error.orElseThrow().contains("Camel Simple"), error.toString());
  }
}
