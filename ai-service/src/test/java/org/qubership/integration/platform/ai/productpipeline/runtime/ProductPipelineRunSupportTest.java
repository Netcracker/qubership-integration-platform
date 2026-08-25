package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineRunSupportTest {

  private static final Instant FIXED = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void rebuildsOnlyConsecutiveFailedAttemptsForEachStage() {
    List<StageAttempt> attempts =
        List.of(
            attempt("analysis", 1, StageStatus.FAILED),
            attempt("planning", 2, StageStatus.FAILED),
            attempt("analysis", 3, StageStatus.SUCCEEDED),
            attempt("planning", 4, StageStatus.FAILED),
            attempt("analysis", 5, StageStatus.FAILED),
            nonTechnicalAttempt("analysis", 6));

    assertEquals(
        Map.of("run-1:planning", 2, "run-1:analysis", 1),
        ProductPipelineRunSupport.consecutiveTechnicalRetries("run-1", attempts));
  }

  private static StageAttempt attempt(String stageId, long revision, StageStatus outcome) {
    return new StageAttempt(
        "attempt-" + revision, stageId, revision, outcome, FIXED, FIXED, List.of(), null);
  }

  private static StageAttempt nonTechnicalAttempt(String stageId, long revision) {
    return new StageAttempt(
        "attempt-" + revision,
        stageId,
        revision,
        StageStatus.FAILED,
        FIXED,
        FIXED,
        List.of(),
        ProductPipelineRunSupport.nonTechnicalFailureEvidence("invalid contract"));
  }
}
