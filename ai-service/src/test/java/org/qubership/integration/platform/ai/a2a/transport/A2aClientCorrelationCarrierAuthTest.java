package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class A2aClientCorrelationCarrierAuthTest {

  @AfterEach
  void clearCarrier() {
    A2aClientCorrelationCarrier.clearAll();
  }

  @Test
  void storesCatalogAuthorizationUntilRequestCleared() {
    A2aClientCorrelationCarrier.Binding binding =
        A2aClientCorrelationCarrier.bind("task-1", "ctx-1", "Bearer token-a");

    assertEquals(
        "Bearer token-a",
        A2aClientCorrelationCarrier.catalogAuthorization(binding.requestId()).orElseThrow());

    assertTrue(A2aClientCorrelationCarrier.clear(binding.requestId()));
    assertTrue(A2aClientCorrelationCarrier.catalogAuthorization(binding.requestId()).isEmpty());
  }
}
