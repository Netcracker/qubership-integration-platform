package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.exception.RateLimitException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RateLimitErrorClassifier {
  private static final Pattern WAIT =
      Pattern.compile("Please try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|s)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE = Pattern.compile("rate_limit_exceeded", Pattern.CASE_INSENSITIVE);

  public boolean isRateLimit(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RateLimitException) {
        return true;
      }
      if (c.getMessage() != null && CODE.matcher(c.getMessage()).find()) {
        return true;
      }
    }
    return false;
  }

  public Optional<Duration> extractWait(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      Optional<Duration> fromMessage = parseWait(c.getMessage());
      if (fromMessage.isPresent()) {
        return fromMessage;
      }
    }
    return Optional.empty();
  }

  static Optional<Duration> parseWait(String message) {
    if (message == null || message.isBlank()) {
      return Optional.empty();
    }
    Matcher m = WAIT.matcher(message);
    if (!m.find()) {
      return Optional.empty();
    }
    double value = Double.parseDouble(m.group(1));
    String unit = m.group(2).toLowerCase(Locale.ROOT);
    if ("ms".equals(unit)) {
      return Optional.of(Duration.ofMillis(Math.round(value)));
    }
    return Optional.of(Duration.ofMillis(Math.round(value * 1000.0)));
  }
}
