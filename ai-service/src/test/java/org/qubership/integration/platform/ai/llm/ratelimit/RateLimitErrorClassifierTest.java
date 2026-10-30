package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.RateLimitException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import dev.langchain4j.exception.HttpException;
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
  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

  @Test
  void readsStructuredResetInSecondsAndMillisecondsWithoutProviderNames() {
    classifier = new RateLimitErrorClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    for (String epoch : List.of("1789041660", "1789041660000")) {
      var error = new RateLimitException("{\"error\":{\"metadata\":{\"headers\":{\"x-ratelimit-reset\":\""
          + epoch + "\"}}}}");
      assertEquals(Optional.of(Duration.ofSeconds(60)), classifier.extractWait(error));
    }
  }

  @Test
  void recognizesPlainHttp429AndHonorsBothTimingHeaders() {
    classifier = new RateLimitErrorClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    var error = new RateLimitHttpException("unchanged", Map.of("retry-after", "12", "x-ratelimit-reset", "1789041660"));
    assertTrue(classifier.isRateLimit(new HttpException(429, "not an English rate limit message")));
    assertEquals(Optional.of(Duration.ofSeconds(60)), classifier.extractWait(new RuntimeException(error)));
  }

  @Test
  void honorsHttpDateAndKeepsTheLaterValidDeadline() {
    classifier = new RateLimitErrorClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    var error = new RateLimitHttpException("Please try again in 1s.",
        Map.of("Retry-After", "Thu, 10 Sep 2026 12:00:30 GMT", "X-RateLimit-Reset", "1789041660000"));
    assertEquals(Optional.of(Duration.ofSeconds(60)), classifier.extractWait(error));
  }

  @Test
  void malformedTimingKeepsTextFallbackAndPastResetHasNoDelay() {
    classifier = new RateLimitErrorClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    for (String invalid : List.of("NaN", "-1", "99999999999999999999999999", "1789041600000.1")) {
      assertEquals(Optional.of(Duration.ofSeconds(2)), classifier.extractWait(
          new RateLimitHttpException("Please try again in 2s.", Map.of("X-RateLimit-Reset", invalid))));
    }
    assertEquals(Optional.of(Duration.ZERO), classifier.extractWait(
        new RateLimitHttpException("", Map.of("X-RateLimit-Reset", "1789041599"))));
  }

  @Test
  void evidenceRetainsOnlyTimingDataWithAnExactDeadline() {
    classifier = new RateLimitErrorClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    var error = new RateLimitException("{\"error\":{\"metadata\":{\"headers\":{"
        + "\"X-RateLimit-Reset\":1789041660000,\"Authorization\":\"private\"}}}}");
    var evidence = classifier.errorEvidence(error);
    assertEquals(NOW.toString(), evidence.get("observedAt"));
    assertEquals(NOW.plusSeconds(60).toString(), evidence.get("retryNotBefore"));
    assertEquals(Map.of("body", Map.of("X-RateLimit-Reset", "1789041660000")), evidence.get("rateLimitTiming"));
  }

  @Test
  void errorEvidenceRetainsTheTypedHttpStatusWithoutInferringItFromTheProviderBody() {
    var error = new dev.langchain4j.exception.InternalServerException("Provider failed",
        new HttpException(503, "{\"error\":{\"code\":502}}"));

    assertEquals(Map.of("type", error.getClass().getName(), "message", "Provider failed", "httpStatus", 503),
        classifier.errorEvidence(error));
    assertFalse(classifier.errorEvidence(new IllegalStateException("{\"error\":{\"code\":502}}"))
        .containsKey("httpStatus"));
  }

  @Test
  void largePositiveRetryAfterDoesNotFallBackToASecond() {
    assertEquals(Optional.of(Duration.ofSeconds(2147483648L)), classifier.extractWait(
        new RateLimitHttpException("", Map.of("Retry-After", "2147483648"))));
  }

}
