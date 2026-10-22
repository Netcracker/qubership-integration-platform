package org.qubership.integration.platform.ai.chat.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CipJtokkitTokenCountEstimatorTest {

  @Test
  void countsNonEmptyText() {
    var estimator = new CipJtokkitTokenCountEstimator("gpt-4o");
    assertTrue(estimator.estimateTokenCountInText("integration platform") > 0);
  }
}
