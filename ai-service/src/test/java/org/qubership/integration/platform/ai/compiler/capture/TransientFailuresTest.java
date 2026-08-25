package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

class TransientFailuresTest {

  @Test
  void detectsTransportRateLimitAndTransientHttpFailuresInCauseChains() {
    assertTrue(TransientFailures.isTransient(new ConnectException("Connection refused")));
    assertTrue(TransientFailures.isTransient(new SocketTimeoutException("Read timed out")));
    assertTrue(
        TransientFailures.isTransient(
            new IllegalStateException(
                "catalog failed", new WebApplicationException(Response.status(503).build()))));
    assertTrue(TransientFailures.isTransient(new RateLimitTurnBudgetExhaustedException(2)));
  }

  @Test
  void ignoresValidationAndNonTransientHttpFailures() {
    assertFalse(TransientFailures.isTransient(new IllegalArgumentException("invalid plan")));
    assertFalse(
        TransientFailures.isTransient(new WebApplicationException(Response.status(400).build())));
  }
}
