package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.RateLimitException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitErrorClassifierTest {

  private RateLimitErrorClassifier classifier;

  @BeforeEach
  void setUp() {
    classifier = new RateLimitErrorClassifier();
  }

  @Test
  void detectsRateLimitException() {
    assertTrue(classifier.isRateLimit(new RateLimitException("rate_limit_exceeded")));
  }

  @Test
  void detectsMessageCodeEvenIfWrapped() {
    Throwable t = new RuntimeException(new Exception(
        "Rate limit reached ... \"code\": \"rate_limit_exceeded\""));
    assertTrue(classifier.isRateLimit(t));
  }

  @Test
  void ignoresOrdinaryErrors() {
    assertFalse(classifier.isRateLimit(new IllegalStateException("boom")));
  }

  @Test
  void extractsMsAndSecondsFromOpenAiMessage() {
    String msg =
        "Rate limit reached for gpt-4o-mini ... Please try again in 812ms. ... \"code\": \"rate_limit_exceeded\"";
    assertEquals(Optional.of(Duration.ofMillis(812)), classifier.extractWait(new RateLimitException(msg)));

    String msg2 = "Please try again in 3.483s. ... rate_limit_exceeded";
    assertEquals(Optional.of(Duration.ofMillis(3483)), classifier.extractWait(new RateLimitException(msg2)));
  }
}
