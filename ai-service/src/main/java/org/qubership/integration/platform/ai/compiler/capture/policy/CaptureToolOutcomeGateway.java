package org.qubership.integration.platform.ai.compiler.capture.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.ArrayList;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder;

/**
 * Sole adapter exit for capture validation failures (ADR 0005).
 *
 * <p>Adapters classify into {@link CaptureFailureClass}; this gateway owns soft budgets,
 * IDENTICAL_SPAM escalation, feedback recording, and CVE. Callers must not also write to {@link
 * CaptureAttemptFeedbackStore} for the same failure (no double-record).
 *
 * <p>The soft budget is keyed by the normalized rejection text, not by the payload that caused it.
 * A generator answering a rejection re-emits its whole capture, so payload identity handed out a
 * fresh credit on every attempt and never escalated to IDENTICAL_SPAM.
 * Structured requirement issues use their exact code and logical path instead of rejection prose.
 */
@ApplicationScoped
public class CaptureToolOutcomeGateway {

  public record TypedIssue(String code, String path, String message,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String entityId,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      com.fasterxml.jackson.databind.JsonNode details) {
    public TypedIssue(String code, String path, String message, String entityId) {
      this(code, path, message, entityId, null);
    }
    public TypedIssue(String code, String path, String message) {
      this(code, path, message, null);
    }

    public TypedIssue(String code, String path) {
      this(code, path, "");
    }
  }

  /** Applies the shared soft-credit policy to each structured requirement issue. */
  public boolean repeatedRequirementIssue(
      String conversationId, String mutationScope, List<TypedIssue> issues) {
    return repeatedRequirementIssue(conversationId, mutationScope, issues, null);
  }

  /**
   * Records the same issue fingerprints as {@link #repeatedRequirementIssue(String, String, List)}.
   * A non-null ladder stage is stored with that evidence and does not spend another soft credit.
   */
  public boolean repeatedRequirementIssue(
      String conversationId, String mutationScope, List<TypedIssue> issues,
      RequirementRecoveryLadder.Stage stage) {
    boolean repeated = false;
    List<String> fingerprints = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    var consumed = fingerprintStore.softFingerprints(conversationId);
    for (TypedIssue issue : issues) {
      String logicalPath = logicalRequirementPath(issue.path());
      String identity = issue.code() + ":" + logicalPath
          + (issue.entityId() == null ? "" : ":" + issue.entityId());
      identities.add(identity);
      String fingerprint = fingerprintStore.fingerprint(
          "requirement-capture", mutationScope, new TypedIssue(issue.code(), logicalPath, "", issue.entityId()));
      CaptureFailureDecision decision = policy.decide(
          CaptureFailureClass.CORRECTABLE,
          CaptureAttemptState.forFingerprint(consumed.contains(fingerprint)), identity);
      repeated |= decision.failureClass() == CaptureFailureClass.IDENTICAL_SPAM;
      fingerprints.add(fingerprint);
    }
    if (stage != null) {
      boolean continueCapture = stage == RequirementRecoveryLadder.Stage.PROJECTION_REBUILD
          || stage == RequirementRecoveryLadder.Stage.ASK_AUTHOR;
      feedbackStore.recordClassifiedRequirementFailure(
          conversationId,
          CaptureFailureKind.VALIDATION,
          continueCapture ? CaptureFailureClass.CORRECTABLE : CaptureFailureClass.PERMANENT,
          continueCapture,
          String.join(", ", identities),
          issues,
          stage == RequirementRecoveryLadder.Stage.INTERNAL_FAILURE,
          stage);
      return true;
    }
    if (!repeated) {
      fingerprintStore.consumeSoftCredits(conversationId, fingerprints);
    }
    boolean argumentError = issues.stream().anyMatch(issue ->
        List.of("INVALID_JSON", "DUPLICATE_JSON_KEY", "MISSING_FIELD",
            "UNEXPECTED_FIELD", "INVALID_TYPE", "INVALID_ENUM")
            .contains(issue.code()));
    feedbackStore.recordClassifiedRequirementFailure(
        conversationId,
        argumentError ? CaptureFailureKind.TOOL_ARGUMENTS : CaptureFailureKind.VALIDATION,
        repeated ? CaptureFailureClass.IDENTICAL_SPAM : CaptureFailureClass.CORRECTABLE,
        !repeated && !argumentError,
        String.join(", ", identities), issues);
    return repeated;
  }

  /** Removes tool wrappers and array positions while preserving named records and fields. */
  public static String logicalRequirementPath(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    String[] parts = path.split("/");
    StringBuilder logical = new StringBuilder();
    boolean propertyPath = false;
    boolean arrayPosition = false;
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty()) {
        continue;
      }
      if (propertyPath) {
        logical.append('/').append(part);
        continue;
      }
      if ((i == 1 && ("draft".equals(part) || "changes".equals(part) || "capture".equals(part)))
          || ("flow".equals(part) && (i == 1 || i == 2 && "draft".equals(parts[1])))) {
        continue;
      }
      if (arrayPosition && part.chars().allMatch(Character::isDigit)) {
        arrayPosition = false;
        continue;
      }
      String normalized = switch (part) {
        case "addInteractions", "updateInteractions", "removeInteractionIds", "interactions" -> "interactions[]";
        case "addFacts", "updateFacts", "removeFactIds", "facts" -> "facts[]";
        case "setCapabilities", "removeCapabilityInteractionIds", "capabilities" -> "capabilities[]";
        case "addQuestions", "updateQuestions", "removeQuestionIds", "openQuestions" -> "questions[]";
        case "addTransitions", "removeTransitions", "transitions" -> "transitions[]";
        case "interactionIds" -> "interactionIds[]";
        case "endpoints" -> "endpoints[]";
        default -> part;
      };
      logical.append('/').append(normalized);
      propertyPath = "requestedProperties".equals(part);
      arrayPosition = normalized.endsWith("[]");
    }
    return logical.toString();
  }

  private final CaptureFailurePolicy policy;
  private final ToolCallFingerprintStore fingerprintStore;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureFailureMetrics metrics;

  @Inject
  public CaptureToolOutcomeGateway(
      ToolCallFingerprintStore fingerprintStore,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureFailureMetrics metrics) {
    this(new CaptureFailurePolicy(), fingerprintStore, feedbackStore, metrics);
  }

  /** Test helper without metrics bean. */
  public CaptureToolOutcomeGateway(
      ToolCallFingerprintStore fingerprintStore, CaptureAttemptFeedbackStore feedbackStore) {
    this(new CaptureFailurePolicy(), fingerprintStore, feedbackStore, null);
  }

  /** Test helper without metrics bean. */
  public CaptureToolOutcomeGateway(
      CaptureFailurePolicy policy,
      ToolCallFingerprintStore fingerprintStore,
      CaptureAttemptFeedbackStore feedbackStore) {
    this(policy, fingerprintStore, feedbackStore, null);
  }

  public CaptureToolOutcomeGateway(
      CaptureFailurePolicy policy,
      ToolCallFingerprintStore fingerprintStore,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureFailureMetrics metrics) {
    this.policy = policy;
    this.fingerprintStore = fingerprintStore;
    this.feedbackStore = feedbackStore;
    this.metrics = metrics;
  }

  /**
   * Applies the failure matrix and either returns a soft tool-result string or throws {@link
   * CaptureValidationException}.
   */
  public String onFailure(
      CaptureFeedbackChannel channel,
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      String toolName,
      Object fingerprintArgs,
      String message) {
    return onFailure(
        channel,
        conversationId,
        capabilityId,
        kind,
        failureClass,
        toolName,
        fingerprintArgs,
        message,
        List.of());
  }

  /**
   * Same as {@link #onFailure(CaptureFeedbackChannel, String, String, CaptureFailureKind,
   * CaptureFailureClass, String, Object, String)} but persists actionable {@code fieldHints} for
   * outer repair wording.
   */
  public String onFailure(
      CaptureFeedbackChannel channel,
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      String toolName,
      Object fingerprintArgs,
      String message,
      List<CaptureFieldHint> fieldHints) {
    return onFailure(channel, conversationId, capabilityId, kind, failureClass, toolName,
        fingerprintArgs, message, fieldHints, null);
  }

  /** PLAN presentation detail is retained separately from the unchanged defect identity. */
  public String onFailure(
      CaptureFeedbackChannel channel, String conversationId, String capabilityId,
      CaptureFailureKind kind, CaptureFailureClass failureClass, String toolName,
      Object fingerprintArgs, String message, List<CaptureFieldHint> fieldHints,
      String presentationDetails) {
    if (presentationDetails != null && channel != CaptureFeedbackChannel.PLAN) {
      throw new IllegalArgumentException("Presentation details require the PLAN feedback channel");
    }
    CaptureFailureKind safeKind = kind == null ? CaptureFailureKind.VALIDATION : kind;
    CaptureFailureClass classified =
        failureClass == null ? CaptureFailureClass.CORRECTABLE : failureClass;
    // Proposal identity is independent of the logical defect credit.
    // Preflight callers have already installed the pending proposal before validation.
    String fingerprint = fingerprintStore.failureFingerprint(toolName, capabilityId, message);
    boolean softUsed = fingerprintStore.softCreditUsed(conversationId, fingerprint);
    CaptureFailureDecision decision =
        policy.decide(classified, CaptureAttemptState.forFingerprint(softUsed), message);
    recordFeedback(channel, conversationId, capabilityId, safeKind, decision, fieldHints, presentationDetails);
    recordMetrics(decision, capabilityId, toolName);
    if (decision.softToolResult()) {
      fingerprintStore.consumeSoftCredit(conversationId, fingerprint);
      return decision.message();
    }
    if (decision.throwCve()) {
      throw new CaptureValidationException(cveMessage(decision));
    }
    return decision.message();
  }

  /** Uses the same failure policy with typed, source-scoped defect identity. */
  public void onTypedPlanFailure(String conversationId, String capabilityId, String toolName, TypedIssue issue) {
    String logicalPath = logicalRequirementPath(issue.path());
    String fingerprint = fingerprintStore.fingerprint(toolName, capabilityId,
        List.of(feedbackStore.inputScope(conversationId, capabilityId), issue.code(), logicalPath,
            issue.entityId() == null ? "" : issue.entityId()));
    String message = issue.code() + " " + issue.path() + ": " + issue.message();
    var decision = policy.decide(CaptureFailureClass.CORRECTABLE,
        CaptureAttemptState.forFingerprint(fingerprintStore.softCreditUsed(conversationId, fingerprint)), message);
    feedbackStore.recordTypedPlanFailure(conversationId, capabilityId, CaptureFailureKind.VALIDATION,
        decision.failureClass(), decision.outerAllowed(), message, List.of(issue));
    recordMetrics(decision, capabilityId, toolName);
    if (decision.softToolResult()) { fingerprintStore.consumeSoftCredit(conversationId, fingerprint); }
    if (decision.throwCve()) { throw new CaptureValidationException(cveMessage(decision)); }
  }

  /** Retains the original rejected call in the same immutable native feedback journal. */
  public void beginRejectedProposal(String conversationId, String capabilityId, String toolName,
      String rawArguments, String toolCallId) {
    feedbackStore.beginRejectedProposal(conversationId, capabilityId, toolName, rawArguments, toolCallId);
  }

  public void beginProposal(String conversationId, String capabilityId, String toolName, Object arguments) {
    feedbackStore.beginProposal(conversationId, capabilityId, toolName, arguments);
  }

  /** ACCEPTED / DUPLICATE terminators still go through CVE (ADR 0001 harvest). */
  public void onTerminalAccept(String message) {
    CaptureFailureDecision decision = CaptureFailureDecision.acceptedCve(message);
    throw new CaptureValidationException(decision.message());
  }

  public void onTerminalDuplicate(String message) {
    CaptureFailureDecision decision = CaptureFailureDecision.duplicateCve(message);
    throw new CaptureValidationException(decision.message());
  }

  /** Ends a stateless invocation after its feedback has already been recorded. */
  public void endInvocation(String message) {
    throw new CaptureValidationException(message);
  }

  private void recordFeedback(
      CaptureFeedbackChannel channel,
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureDecision decision,
      List<CaptureFieldHint> fieldHints,
      String presentationDetails) {
    CaptureFeedbackChannel safeChannel =
        channel == null ? CaptureFeedbackChannel.PATCH : channel;
    List<CaptureFieldHint> hints = fieldHints == null ? List.of() : fieldHints;
    switch (safeChannel) {
      case PLAN ->
          feedbackStore.recordClassifiedPlanFailure(
              conversationId,
              kind,
              decision.failureClass(),
              decision.outerAllowed(),
              decision.message(),
              hints, presentationDetails);
      case PATCH ->
          feedbackStore.recordClassifiedPatchFailure(
              conversationId,
              capabilityId,
              kind,
              decision.failureClass(),
              decision.outerAllowed(),
              decision.message(),
              hints);
      case VALIDATION ->
          feedbackStore.recordClassifiedValidationFailure(
              conversationId,
              capabilityId,
              kind,
              decision.failureClass(),
              decision.outerAllowed(),
              decision.message(),
              hints);
    }
  }

  private void recordMetrics(
      CaptureFailureDecision decision, String capabilityId, String toolName) {
    if (metrics == null) {
      return;
    }
    String capabilityTag =
        capabilityId != null && !capabilityId.isBlank() ? capabilityId : toolName;
    metrics.recordDecision(decision, capabilityTag);
  }

  private static String cveMessage(CaptureFailureDecision decision) {
    if (decision.failureClass() == CaptureFailureClass.IDENTICAL_SPAM) {
      return "Repeated capture validation failure: " + decision.message();
    }
    return decision.message();
  }
}
