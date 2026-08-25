package org.qubership.integration.platform.ai.compiler.capture;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;

/**
 * Last capture failure for a conversation, used to build repair user messages and gate outer
 * repair (ADR 0005).
 *
 * <p>{@link #kind()} remains the wording channel for {@link CaptureRepairMessageBuilder}. {@link
 * #failureClass()} and {@link #outerAllowed()} own outer budgets: PERMANENT, TOOL_ARGUMENTS, and
 * IDENTICAL_SPAM → no outer; CORRECTABLE → outer ≤ 1. A repeated rejection gets no outer turn
 * because the generator has already failed to answer that same complaint twice. {@link
 * #fieldHints()} carry actionable nested→top copy guidance when present.
 */
public record CaptureAttemptFeedback(
    CaptureFailureKind kind,
    String summary,
    CaptureFailureClass failureClass,
    boolean outerAllowed,
    List<CaptureFieldHint> fieldHints) {

  /**
   * Legacy two-arg construction for callers that have not yet classified via the gateway.
   * VALIDATION/CONVERSION default to CORRECTABLE with outer allowed; TOOL_ARGUMENTS forbids outer.
   */
  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary) {
    this(kind, summary, defaultClass(kind), defaultOuterAllowed(kind), List.of());
  }

  /** Classified construction without field hints (defaults to an empty hint list). */
  public CaptureAttemptFeedback(
      CaptureFailureKind kind,
      String summary,
      CaptureFailureClass failureClass,
      boolean outerAllowed) {
    this(kind, summary, failureClass, outerAllowed, List.of());
  }

  public CaptureAttemptFeedback {
    fieldHints = fieldHints == null ? List.of() : List.copyOf(fieldHints);
  }

  private static CaptureFailureClass defaultClass(CaptureFailureKind kind) {
    if (kind == CaptureFailureKind.TOOL_ARGUMENTS) {
      return CaptureFailureClass.TOOL_ARGUMENTS;
    }
    return CaptureFailureClass.CORRECTABLE;
  }

  private static boolean defaultOuterAllowed(CaptureFailureKind kind) {
    return kind != CaptureFailureKind.TOOL_ARGUMENTS;
  }
}
