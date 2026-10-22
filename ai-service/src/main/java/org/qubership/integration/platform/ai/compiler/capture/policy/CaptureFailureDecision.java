package org.qubership.integration.platform.ai.compiler.capture.policy;

/**
 * Policy output: soft tool-result vs CVE, and whether an outer repair turn may run.
 *
 * <p>See ADR 0005 — {@code CaptureRepairRunner} must honor {@link #outerAllowed()}.
 */
public record CaptureFailureDecision(
    boolean softToolResult,
    boolean throwCve,
    boolean outerAllowed,
    CaptureFailureClass failureClass,
    String message) {

  public static CaptureFailureDecision softCorrectable(String message) {
    return new CaptureFailureDecision(
        true, false, true, CaptureFailureClass.CORRECTABLE, message);
  }

  /**
   * The same rejection twice. The in-turn credit is spent and the outer turn is refused too: it
   * would send "fix it and call the tool again" for a complaint the generator has already failed
   * to answer twice.
   */
  public static CaptureFailureDecision identicalSpamCve(String message) {
    return new CaptureFailureDecision(
        false, true, false, CaptureFailureClass.IDENTICAL_SPAM, message);
  }

  public static CaptureFailureDecision permanentCve(String message) {
    return new CaptureFailureDecision(
        false, true, false, CaptureFailureClass.PERMANENT, message);
  }

  public static CaptureFailureDecision acceptedCve(String message) {
    return new CaptureFailureDecision(
        false, true, false, CaptureFailureClass.ACCEPTED, message);
  }

  public static CaptureFailureDecision duplicateCve(String message) {
    return new CaptureFailureDecision(
        false, true, false, CaptureFailureClass.DUPLICATE, message);
  }

  public static CaptureFailureDecision toolArguments(String message) {
    return new CaptureFailureDecision(
        false, false, false, CaptureFailureClass.TOOL_ARGUMENTS, message);
  }
}
