package org.qubership.integration.platform.ai.compiler.capture.policy;

/**
 * Failure classification for capture tool outcomes (ADR 0003).
 *
 * <p>Adapters classify domain failures into {@link #CORRECTABLE} or {@link #PERMANENT}. The
 * gateway may escalate a repeated identical CORRECTABLE fingerprint to {@link #IDENTICAL_SPAM}.
 */
public enum CaptureFailureClass {
  /** Fixable in-turn; first fail soft, then IDENTICAL_SPAM on same fingerprint. Outer ≤ 1. */
  CORRECTABLE,
  /** Same fingerprint after a soft credit; CVE ends the inner turn. Outer still ≤ 1. */
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
