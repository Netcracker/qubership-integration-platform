package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CamelSimplePredicateValidatorTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "${header.minute} == 2",
      "${date:now:mm} regex '.*[02468]'",
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
}
