package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransientWaitPolicyTest {

  @Test
  void parsesCsvBackoffSequence() {
    TransientWaitPolicy policy = TransientWaitPolicy.fromCsv("2,5,10");
    assertEquals(2, policy.waitSeconds(0));
    assertEquals(5, policy.waitSeconds(1));
    assertEquals(10, policy.waitSeconds(2));
    assertEquals(10, policy.waitSeconds(5));
  }

  @Test
  void shouldRetryUntilMaxAttempts() {
    TransientWaitPolicy policy = TransientWaitPolicy.fromCsv("2,5,10");
    assertTrue(policy.shouldRetry(0, 3));
    assertTrue(policy.shouldRetry(1, 3));
    assertFalse(policy.shouldRetry(2, 3));
  }
}
