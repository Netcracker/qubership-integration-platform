package org.qubership.integration.platform.ai.a2a;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class A2aBearerRequiredRouteFilterTest {

  @Test
  void agentCardDiscoveryDoesNotRequireBearer() {
    assertFalse(A2aBearerRequiredRouteFilter.requiresCallerBearer("/.well-known/agent-card.json"));
  }

  @Test
  void messageSendRequiresBearer() {
    assertTrue(A2aBearerRequiredRouteFilter.requiresCallerBearer("/message:send"));
  }
}
