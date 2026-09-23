package org.qubership.integration.platform.ai.compiler.capture.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.ArrayList;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;

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
 */
@ApplicationScoped
public class CaptureToolOutcomeGateway {

  public record TypedIssue(String code, String path) {}

  /** Applies the shared soft-credit policy to each structured requirement issue. */
  public boolean repeatedRequirementIssue(
      String conversationId, String mutationScope, List<TypedIssue> issues) {
    boolean repeated = false;
    List<String> fingerprints = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    for (TypedIssue issue : issues) {
      String identity = issue.code() + ":" + logicalRequirementPath(issue.path());
      identities.add(identity);
      String fingerprint = fingerprintStore.failureFingerprint(
          "requirement-capture", mutationScope, identity);
      CaptureFailureDecision decision = policy.decide(
          CaptureFailureClass.CORRECTABLE,
          CaptureAttemptState.forFingerprint(
              fingerprintStore.softCreditUsed(conversationId, fingerprint)), identity);
      repeated |= decision.failureClass() == CaptureFailureClass.IDENTICAL_SPAM;
      fingerprints.add(fingerprint);
    }
    if (!repeated) {
      fingerprints.forEach(fingerprint ->
          fingerprintStore.consumeSoftCredit(conversationId, fingerprint));
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
        String.join(", ", identities));
    return repeated;
  }

  static String logicalRequirementPath(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    String[] parts = path.split("/");
    StringBuilder logical = new StringBuilder();
    for (String part : parts) {
      if (part.isEmpty() || "draft".equals(part) || "changes".equals(part)
          || "flow".equals(part)
          || part.chars().allMatch(Character::isDigit)) {
        continue;
      }
      String normalized = switch (part) {
        case "addInteractions", "updateInteractions", "removeInteractionIds", "interactions" -> "interactions[]";
        case "addFacts", "updateFacts", "removeFactIds", "facts" -> "facts[]";
        case "setCapabilities", "removeCapabilityInteractionIds", "capabilities" -> "capabilities[]";
        case "addQuestions", "updateQuestions", "removeQuestionIds", "openQuestions" -> "questions[]";
        case "addTransitions", "removeTransitions", "transitions" -> "transitions[]";
        case "interactionIds" -> "interactionIds[]";
        default -> part;
      };
      logical.append('/').append(normalized);
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
    CaptureFailureKind safeKind = kind == null ? CaptureFailureKind.VALIDATION : kind;
    CaptureFailureClass classified =
        failureClass == null ? CaptureFailureClass.CORRECTABLE : failureClass;
    // ponytail: fingerprintArgs no longer keys the budget. Dropping the parameter touches 18 call
    // sites, so it goes in its own mechanical change rather than this behavior fix.
    String fingerprint = fingerprintStore.failureFingerprint(toolName, capabilityId, message);
    boolean softUsed = fingerprintStore.softCreditUsed(conversationId, fingerprint);
    CaptureFailureDecision decision =
        policy.decide(classified, CaptureAttemptState.forFingerprint(softUsed), message);
    recordFeedback(channel, conversationId, capabilityId, safeKind, decision, fieldHints);
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

  /** ACCEPTED / DUPLICATE terminators still go through CVE (ADR 0001 harvest). */
  public void onTerminalAccept(String message) {
    CaptureFailureDecision decision = CaptureFailureDecision.acceptedCve(message);
    throw new CaptureValidationException(decision.message());
  }

  public void onTerminalDuplicate(String message) {
    CaptureFailureDecision decision = CaptureFailureDecision.duplicateCve(message);
    throw new CaptureValidationException(decision.message());
  }

  private void recordFeedback(
      CaptureFeedbackChannel channel,
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureDecision decision,
      List<CaptureFieldHint> fieldHints) {
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
              hints);
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
