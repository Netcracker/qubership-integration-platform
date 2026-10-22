package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.exception.HttpException;
import java.util.Map;

/** Retains response timing headers through the SDK's HTTP exception mapping. */
public final class RateLimitHttpException extends HttpException {
  private final Map<String, String> headers;

  public RateLimitHttpException(String message, Map<String, String> headers) {
    super(429, message);
    this.headers = Map.copyOf(headers);
  }

  public Map<String, String> headers() {
    return headers;
  }
}
