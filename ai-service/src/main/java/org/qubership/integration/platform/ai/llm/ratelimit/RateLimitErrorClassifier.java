package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RateLimitErrorClassifier {
  private static final Pattern WAIT =
      Pattern.compile("Please try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|s)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE = Pattern.compile("rate_limit_exceeded", Pattern.CASE_INSENSITIVE);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Clock clock;

  public RateLimitErrorClassifier() {
    this(Clock.systemUTC());
  }

  RateLimitErrorClassifier(Clock clock) {
    this.clock = clock;
  }

  public boolean isRateLimit(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RateLimitException || c instanceof HttpException http && http.statusCode() == 429) {
        return true;
      }
      if (c.getMessage() != null && CODE.matcher(c.getMessage()).find()) {
        return true;
      }
    }
    return false;
  }

  public Optional<Duration> extractWait(Throwable t) {
    return extractWait(t, clock.instant());
  }

  private Optional<Duration> extractWait(Throwable t, Instant now) {
    Optional<Duration> timed = Optional.empty();
    Optional<Duration> text = Optional.empty();
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RateLimitHttpException http) {
        timed = later(timed, parseHeaders(http.headers(), now));
      }
      timed = later(timed, parseHeaders(bodyHeaders(c.getMessage()), now));
      text = later(text, parseWait(c.getMessage()));
    }
    return timed.isPresent() ? timed : text;
  }

  Map<String, Object> errorEvidence(Throwable error) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("type", error.getClass().getName());
    evidence.put("message", String.valueOf(error.getMessage()));
    if (!isRateLimit(error)) return evidence;
    Instant now = clock.instant();
    Map<String, Object> timing = new LinkedHashMap<>();
    for (Throwable c = error; c != null; c = c.getCause()) {
      if (c instanceof RateLimitHttpException http && !http.headers().isEmpty()) {
        timing.put("wire", http.headers());
      }
      var body = bodyHeaders(c.getMessage());
      if (!body.isEmpty()) timing.put("body", body);
    }
    evidence.put("rateLimitTiming", timing);
    evidence.put("observedAt", now.toString());
    extractWait(error, now).ifPresent(wait -> evidence.put("retryNotBefore", now.plus(wait).toString()));
    return evidence;
  }

  private static Optional<Duration> parseHeaders(Map<String, String> headers, Instant now) {
    Optional<Duration> wait = Optional.empty();
    for (var header : headers.entrySet()) {
      try {
        String value = header.getValue().trim();
        Duration candidate;
        if ("Retry-After".equalsIgnoreCase(header.getKey())) {
          candidate = value.matches("\\d+") ? Duration.ofSeconds(Long.parseLong(value))
              : Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } else if ("X-RateLimit-Reset".equalsIgnoreCase(header.getKey())) {
          long epoch = Long.parseLong(value);
          if (epoch < 0) continue;
          // Epoch milliseconds have at least 12 digits; ordinary epoch seconds have 10.
          Instant reset = epoch >= 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
          candidate = Duration.between(now, reset);
        } else {
          continue;
        }
        if (candidate.isNegative()) candidate = Duration.ZERO;
        now.plus(candidate);
        wait = later(wait, Optional.of(candidate));
      } catch (java.time.DateTimeException | NumberFormatException | ArithmeticException malformed) {
        // Invalid timing data keeps the existing text or fallback delay.
      }
    }
    return wait;
  }

  private static Map<String, String> bodyHeaders(String message) {
    if (message == null || !message.stripLeading().startsWith("{")) return Map.of();
    try {
      JsonNode headers = JSON.readTree(message).path("error").path("metadata").path("headers");
      Map<String, String> timing = new LinkedHashMap<>();
      headers.properties().forEach(entry -> {
        if (("Retry-After".equalsIgnoreCase(entry.getKey()) || "X-RateLimit-Reset".equalsIgnoreCase(entry.getKey()))
            && entry.getValue().isValueNode() && !entry.getValue().isNull()) {
          timing.put(entry.getKey(), entry.getValue().asText());
        }
      });
      return timing;
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      return Map.of();
    }
  }

  private static Optional<Duration> later(Optional<Duration> left, Optional<Duration> right) {
    return left.isEmpty() ? right : right.isEmpty() || left.get().compareTo(right.get()) >= 0 ? left : right;
  }

  static Optional<Duration> parseWait(String message) {
    if (message == null || message.isBlank()) return Optional.empty();
    Matcher m = WAIT.matcher(message);
    if (!m.find()) return Optional.empty();
    double value = Double.parseDouble(m.group(1));
    String unit = m.group(2).toLowerCase(Locale.ROOT);
    return Optional.of(Duration.ofMillis(Math.round("ms".equals(unit) ? value : value * 1000.0)));
  }
}
