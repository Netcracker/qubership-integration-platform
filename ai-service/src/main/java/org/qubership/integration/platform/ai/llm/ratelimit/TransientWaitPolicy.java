package org.qubership.integration.platform.ai.llm.ratelimit;

import java.util.Arrays;

public final class TransientWaitPolicy {

  private final int[] backoffSeconds;

  public TransientWaitPolicy(int[] backoffSeconds) {
    if (backoffSeconds == null || backoffSeconds.length == 0) {
      throw new IllegalArgumentException("backoffSeconds must not be empty");
    }
    this.backoffSeconds = backoffSeconds;
  }

  public static TransientWaitPolicy fromCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return new TransientWaitPolicy(new int[] {2, 5, 10});
    }
    int[] seconds =
        Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .mapToInt(Integer::parseInt)
            .toArray();
    if (seconds.length == 0) {
      throw new IllegalArgumentException("backoff-seconds must contain at least one value");
    }
    return new TransientWaitPolicy(seconds);
  }

  public int waitSeconds(int attemptIndex) {
    if (attemptIndex < 0) {
      return backoffSeconds[0];
    }
    int index = Math.min(attemptIndex, backoffSeconds.length - 1);
    return Math.max(1, backoffSeconds[index]);
  }

  public boolean shouldRetry(int attemptIndex, int maxAttempts) {
    return attemptIndex + 1 < maxAttempts;
  }
}
