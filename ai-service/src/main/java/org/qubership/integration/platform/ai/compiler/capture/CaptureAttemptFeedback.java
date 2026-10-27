package org.qubership.integration.platform.ai.compiler.capture;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder;

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
    List<CaptureFieldHint> fieldHints,
    String repairHistoryJson,
    boolean noProgress, Reference evidenceReference,
    List<org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue> issues,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String presentationDetails,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    RequirementRecoveryLadder.Stage ladderStage) {

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints,
      String repairHistoryJson, boolean noProgress, Reference evidenceReference,
      List<org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue> issues,
      String presentationDetails) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, repairHistoryJson, noProgress,
        evidenceReference, issues, presentationDetails, null);
  }

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints,
      String repairHistoryJson, boolean noProgress, Reference evidenceReference,
      List<org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue> issues) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, repairHistoryJson, noProgress,
        evidenceReference, issues, null);
  }

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints,
      String repairHistoryJson, boolean noProgress, Reference evidenceReference) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, repairHistoryJson, noProgress, evidenceReference, List.of());
  }

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints,
      String repairHistoryJson, boolean noProgress) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, repairHistoryJson, noProgress, null);
  }

  public CaptureAttemptFeedback withEvidenceReference(Reference reference) {
    return new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints,
        repairHistoryJson, noProgress, reference, issues, presentationDetails, ladderStage);
  }

  /** Additional model wording does not change the summary used by native and outer defect keys. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public String modelSummary() {
    return presentationDetails == null ? summary : summary + "\n" + presentationDetails;
  }

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints,
      String repairHistoryJson) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, repairHistoryJson, false);
  }

  public CaptureAttemptFeedback(CaptureFailureKind kind, String summary,
      CaptureFailureClass failureClass, boolean outerAllowed, List<CaptureFieldHint> fieldHints) {
    this(kind, summary, failureClass, outerAllowed, fieldHints, "");
  }

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
    issues = issues == null ? List.of() : List.copyOf(issues);
    repairHistoryJson = repairHistoryJson == null ? "" : repairHistoryJson;
    fieldHints = fieldHints == null ? List.of() : List.copyOf(fieldHints);
    presentationDetails = presentationDetails == null || presentationDetails.isBlank() ? null : presentationDetails;
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
