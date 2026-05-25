package org.qubership.integration.platform.ai.chat.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QipJtokkitTokenCountEstimatorTest {

  @Test
  void countsNonEmptyText() {
    var estimator = new QipJtokkitTokenCountEstimator("gpt-4o");
    assertTrue(estimator.estimateTokenCountInText("integration platform") > 0);
  }
}
