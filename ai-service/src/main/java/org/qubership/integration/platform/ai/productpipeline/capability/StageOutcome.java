package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.List;

/** Typed terminal result of one stage capability invocation. */
public record StageOutcome(
    StageOutcomeClass outcomeClass,
    List<ArtifactCandidate> candidates,
    String message,
    Long retryDelayMs,
    RecoveryCause recoveryCause) {

  public StageOutcome {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public StageOutcome(
      StageOutcomeClass outcomeClass,
      List<ArtifactCandidate> candidates,
      String message,
      Long retryDelayMs) {
    this(outcomeClass, candidates, message, retryDelayMs, null);
  }

  public static StageOutcome of(StageOutcomeClass outcomeClass, String message) {
    return new StageOutcome(outcomeClass, List.of(), message, null, null);
  }

  public static StageOutcome of(
      StageOutcomeClass outcomeClass, String message, RecoveryCause recoveryCause) {
    return new StageOutcome(outcomeClass, List.of(), message, null, recoveryCause);
  }
}
