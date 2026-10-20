package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitWaitPolicyTest {

  private RateLimitWaitPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new RateLimitWaitPolicy();
  }

  @Test
  void ceilRoundsUpFractions() {
    assertEquals(1, policy.ceilWaitSeconds(Duration.ofMillis(812)));
    assertEquals(4, policy.ceilWaitSeconds(Duration.ofMillis(3483)));
    assertEquals(2, policy.ceilWaitSeconds(Duration.ofSeconds(2)));
  }

  @Test
  void fallbackUsesOneThenTwoSeconds() {
    assertEquals(1, policy.fallbackWaitSeconds(0));
    assertEquals(2, policy.fallbackWaitSeconds(1));
  }

  @Test
  void resolvePrefersExtractedOverFallback() {
    assertEquals(1, policy.resolveWaitSeconds(Optional.of(Duration.ofMillis(812)), 0));
    assertEquals(1, policy.resolveWaitSeconds(Optional.empty(), 0));
    assertEquals(2, policy.resolveWaitSeconds(Optional.empty(), 1));
  }

  @Test
  void attemptBudgetAllowsTwoRetriesWhenMaxIsThree() {
    assertTrue(policy.shouldRetry(0, 3));
    assertTrue(policy.shouldRetry(1, 3));
    assertFalse(policy.shouldRetry(2, 3));
  }
}
