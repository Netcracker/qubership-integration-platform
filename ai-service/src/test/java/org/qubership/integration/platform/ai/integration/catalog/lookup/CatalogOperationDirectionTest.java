package org.qubership.integration.platform.ai.integration.catalog.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CatalogOperationDirectionTest {

  @ParameterizedTest
  @CsvSource({
    "kafka,publish,PRODUCED_BY_SYSTEM",
    "kafka,subscribe,CONSUMED_BY_SYSTEM",
    "amqp,send,PRODUCED_BY_SYSTEM",
    "amqp,receive,CONSUMED_BY_SYSTEM",
    "http,POST,CONSUMED_BY_SYSTEM"
  })
  void normalizesDirectionFromSpecificationOwner(
      String protocol, String method, CatalogOperationDirection expected) {
    assertEquals(Optional.of(expected), CatalogOperationDirection.from(protocol, method));
  }

  @Test
  void refusesUnknownAsyncDirection() {
    assertTrue(CatalogOperationDirection.from("kafka", "process").isEmpty());
  }
}
