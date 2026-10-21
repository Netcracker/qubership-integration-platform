package org.qubership.integration.platform.ai.compiler.capture.policy;

/** Attempt counters the pure {@link CaptureFailurePolicy} needs to decide soft vs CVE. */
public record CaptureAttemptState(boolean softAlreadyConsumedForFingerprint, int outerAttemptsUsed) {

  public static CaptureAttemptState forFingerprint(boolean softAlreadyConsumed) {
    return new CaptureAttemptState(softAlreadyConsumed, 0);
  }
}
