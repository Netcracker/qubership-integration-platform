package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransientErrorClassifierTest {

  private TransientErrorClassifier classifier;

  @BeforeEach
  void setUp() {
    classifier = new TransientErrorClassifier();
  }

  @Test
  void detectsGatewayConnectionTermination() {
    Throwable error =
        new InternalServerException(
            "upstream connect error or disconnect/reset before headers. reset reason: connection termination",
            new HttpException(
                500,
                "upstream connect error or disconnect/reset before headers. reset reason: connection termination"));
    assertTrue(classifier.isTransient(error));
  }

  @Test
  void detectsRetryableHttpStatus() {
    assertTrue(classifier.isTransient(new HttpException(503, "service unavailable")));
    assertTrue(classifier.isTransient(new HttpException(502, "bad gateway")));
    assertFalse(classifier.isTransient(new HttpException(401, "unauthorized")));
  }

  @Test
  void ignoresOrdinaryErrors() {
    assertFalse(classifier.isTransient(new IllegalStateException("validation failed")));
  }

  @Test
  void permanentHttpStatusTakesPrecedenceOverQuotedConnectionText() {
    assertFalse(classifier.isTransient(new HttpException(400, "connection reset")));
    assertFalse(classifier.isTransient(new InternalServerException("upstream connect error",
        new HttpException(401, "upstream connect error"))));
    assertTrue(classifier.isTransient(new IllegalStateException("connection reset")),
        "The transport wrapper retains its existing network-message fallback");
  }
}
