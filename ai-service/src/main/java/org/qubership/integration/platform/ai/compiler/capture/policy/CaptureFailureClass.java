package org.qubership.integration.platform.ai.compiler.capture.policy;

/**
 * Failure classification for capture tool outcomes (ADR 0003).
 *
 * <p>Adapters classify domain failures into {@link #CORRECTABLE} or {@link #PERMANENT}. The
 * gateway escalates a CORRECTABLE failure to {@link #IDENTICAL_SPAM} when the same rejection
 * comes back, whatever payload earned it the second time.
 */
public enum CaptureFailureClass {
  /** Fixable in-turn; first fail soft, then IDENTICAL_SPAM on the same rejection. Outer ≤ 1. */
  CORRECTABLE,
  /** The same rejection after a soft credit; CVE ends the inner turn. No outer. */
  IDENTICAL_SPAM,
  /** Not fixable by the same skill (ownership, wrong route, …). No soft; outer = 0. */
  PERMANENT,
  /** Successful accept terminator (ADR 0001). */
  ACCEPTED,
  /** Duplicate accept terminator (ADR 0001). */
  DUPLICATE,
  /** Framework tool-arguments failure channel; never blind outer. */
  TOOL_ARGUMENTS
}
