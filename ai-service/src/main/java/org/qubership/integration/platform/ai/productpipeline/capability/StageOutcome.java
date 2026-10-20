package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;

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

  public static Optional<StageOutcome> captureFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CaptureNoProgressException stopped) {
        var feedback = stopped.evidence() != null ? stopped.evidence().feedback()
            : new CaptureAttemptFeedback(
            CaptureFailureKind.VALIDATION,
            cause.getMessage(),
            CaptureFailureClass.IDENTICAL_SPAM,
            false, List.of(), "", true);
        return Optional.of(of(StageOutcomeClass.CONTRACT_FAILURE, cause.getMessage(),
            RecoveryCause.captureFailure(feedback, List.of(), stopped.evidence())));
      }
    }
    return Optional.empty();
  }

  public static StageOutcome invocationFailure(Throwable failure) {
    var capture = captureFailure(failure);
    return capture.orElseGet(() -> technicalFailure(failure));
  }

  public static StageOutcome technicalFailure(Throwable failure) {
    String message = failure.getMessage() == null ? "Stage invocation failed." : failure.getMessage();
    RecoveryCause cause = org.qubership.integration.platform.ai.llm.ratelimit.LlmRequestBudget
        .isOverflow(failure)
        ? new RecoveryCause(RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED,
            List.of(new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
                "LLM_CONTEXT_OVERFLOW", message, true)), "")
        : null;
    return of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, message, cause);
  }
}
