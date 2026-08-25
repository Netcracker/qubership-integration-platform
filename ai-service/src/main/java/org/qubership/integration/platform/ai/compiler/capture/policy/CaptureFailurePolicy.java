package org.qubership.integration.platform.ai.compiler.capture.policy;

/**
 * Pure failure matrix (ADR 0005): class × attempt state → decision.
 *
 * <p>Adapters classify only; this type owns soft / CVE / outerAllowed.
 */
public final class CaptureFailurePolicy {

  public CaptureFailureDecision decide(
      CaptureFailureClass classified, CaptureAttemptState state, String message) {
    if (classified == null) {
      throw new IllegalArgumentException("failureClass is required");
    }
    CaptureAttemptState attemptState =
        state == null ? CaptureAttemptState.forFingerprint(false) : state;
    String safeMessage = message == null ? "" : message;
    return switch (classified) {
      case PERMANENT -> CaptureFailureDecision.permanentCve(safeMessage);
      case TOOL_ARGUMENTS -> CaptureFailureDecision.toolArguments(safeMessage);
      case ACCEPTED -> CaptureFailureDecision.acceptedCve(safeMessage);
      case DUPLICATE -> CaptureFailureDecision.duplicateCve(safeMessage);
      case IDENTICAL_SPAM -> CaptureFailureDecision.identicalSpamCve(safeMessage);
      case CORRECTABLE -> {
        if (attemptState.softAlreadyConsumedForFingerprint()) {
          yield CaptureFailureDecision.identicalSpamCve(safeMessage);
        }
        yield CaptureFailureDecision.softCorrectable(safeMessage);
      }
    };
  }
}
