package org.qubership.integration.platform.ai.compiler.capture.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;

/**
 * Sole adapter exit for capture validation failures (ADR 0003).
 *
 * <p>Adapters classify into {@link CaptureFailureClass} and supply fingerprint args; this gateway
 * owns soft budgets, IDENTICAL_SPAM escalation, feedback recording, and CVE. Callers must not also
 * write to {@link CaptureAttemptFeedbackStore} for the same failure (no double-record).
 */
@ApplicationScoped
public class CaptureToolOutcomeGateway {

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
    String fingerprint = fingerprintStore.fingerprint(toolName, capabilityId, fingerprintArgs);
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
