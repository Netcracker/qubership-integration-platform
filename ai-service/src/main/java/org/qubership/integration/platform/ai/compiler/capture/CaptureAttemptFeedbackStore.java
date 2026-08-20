package org.qubership.integration.platform.ai.compiler.capture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;

/** In-memory store of the last capture failure per conversation (and capability for patches). */
@ApplicationScoped
public class CaptureAttemptFeedbackStore {

  private final ConcurrentHashMap<String, CaptureAttemptFeedback> planFailures =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> planFailureFingerprints =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, CaptureAttemptFeedback>>
      patchFailures = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, CaptureAttemptFeedback>>
      validationFailures = new ConcurrentHashMap<>();
  private final ToolCallFingerprintStore fingerprintStore;

  @Inject
  public CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore) {
    this.fingerprintStore = fingerprintStore;
  }

  /** Test helper without CDI. */
  public CaptureAttemptFeedbackStore() {
    this(new ToolCallFingerprintStore());
  }

  public boolean recordPlanValidationFailure(String conversationId, String summary) {
    return recordPlanFailure(conversationId, CaptureFailureKind.VALIDATION, summary, null);
  }

  public boolean recordPlanValidationFailure(
      String conversationId, String summary, Object rejectedPayload) {
    String fingerprint =
        fingerprintStore.fingerprint(
            "plan-validation", "requirement-analysis", rejectedPayload);
    return recordPlanFailure(
        conversationId, CaptureFailureKind.VALIDATION, summary, fingerprint);
  }

  public void recordPlanConversionFailure(String conversationId, String summary) {
    recordPlanFailure(conversationId, CaptureFailureKind.CONVERSION, summary);
  }

  public void recordPlanToolArgumentsFailure(String conversationId, String summary) {
    recordPlanFailure(conversationId, CaptureFailureKind.TOOL_ARGUMENTS, summary);
  }

  public boolean recordPatchValidationFailure(
      String conversationId, String capabilityId, String summary) {
    return recordPatchFailure(conversationId, capabilityId, CaptureFailureKind.VALIDATION, summary);
  }

  public boolean recordPatchConversionFailure(
      String conversationId, String capabilityId, String summary) {
    return recordPatchFailure(
        conversationId, capabilityId, CaptureFailureKind.CONVERSION, summary);
  }

  public void recordPatchToolArgumentsFailure(
      String conversationId, String capabilityId, String summary) {
    recordPatchFailure(conversationId, capabilityId, CaptureFailureKind.TOOL_ARGUMENTS, summary);
  }

  public void recordValidationFailure(
      String conversationId, String capabilityId, String summary) {
    recordValidationFailure(conversationId, capabilityId, CaptureFailureKind.VALIDATION, summary);
  }

  public void recordValidationToolArgumentsFailure(
      String conversationId, String capabilityId, String summary) {
    recordValidationFailure(conversationId, capabilityId, CaptureFailureKind.TOOL_ARGUMENTS, summary);
  }

  /**
   * Records a classified failure from {@code CaptureToolOutcomeGateway} (single write; no
   * legacy repeated-boolean ratchet).
   */
  public void recordClassifiedPlanFailure(
      String conversationId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary) {
    recordClassifiedPlanFailure(
        conversationId, kind, failureClass, outerAllowed, summary, List.of());
  }

  public void recordClassifiedPlanFailure(
      String conversationId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary,
      List<CaptureFieldHint> fieldHints) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    planFailures.put(
        conversationId,
        new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints));
  }

  public void recordClassifiedPatchFailure(
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary) {
    recordClassifiedPatchFailure(
        conversationId, capabilityId, kind, failureClass, outerAllowed, summary, List.of());
  }

  public void recordClassifiedPatchFailure(
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary,
      List<CaptureFieldHint> fieldHints) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      return;
    }
    patchFailures
        .computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>())
        .put(
            capabilityId,
            new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints));
  }

  public void recordClassifiedValidationFailure(
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary) {
    recordClassifiedValidationFailure(
        conversationId, capabilityId, kind, failureClass, outerAllowed, summary, List.of());
  }

  public void recordClassifiedValidationFailure(
      String conversationId,
      String capabilityId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary,
      List<CaptureFieldHint> fieldHints) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      return;
    }
    validationFailures
        .computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>())
        .put(
            capabilityId,
            new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints));
  }

  boolean recordPlanFailure(
      String conversationId, CaptureFailureKind kind, String summary) {
    return recordPlanFailure(conversationId, kind, summary, null);
  }

  private boolean recordPlanFailure(
      String conversationId, CaptureFailureKind kind, String summary, String fingerprint) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    boolean repeated =
        Optional.ofNullable(planFailures.get(conversationId))
            .map(
                previous ->
                    previous.kind() == kind
                        && Objects.equals(previous.summary(), summary)
                        && (kind != CaptureFailureKind.VALIDATION
                            || Objects.equals(
                                planFailureFingerprints.get(conversationId), fingerprint)))
            .orElse(false);
    planFailures.put(conversationId, new CaptureAttemptFeedback(kind, summary));
    if (fingerprint == null) {
      planFailureFingerprints.remove(conversationId);
    } else {
      planFailureFingerprints.put(conversationId, fingerprint);
    }
    return repeated;
  }

  boolean recordPatchFailure(
      String conversationId, String capabilityId, CaptureFailureKind kind, String summary) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      return false;
    }
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        patchFailures.computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>());
    boolean repeated =
        Optional.ofNullable(byCapability.get(capabilityId))
            .map(
                previous ->
                    previous.kind() == kind
                        && (kind == CaptureFailureKind.VALIDATION
                            || Objects.equals(previous.summary(), summary)))
            .orElse(false);
    patchFailures
        .computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>())
        .put(capabilityId, new CaptureAttemptFeedback(kind, summary));
    return repeated;
  }

  void recordValidationFailure(
      String conversationId, String capabilityId, CaptureFailureKind kind, String summary) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      return;
    }
    validationFailures
        .computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>())
        .put(capabilityId, new CaptureAttemptFeedback(kind, summary));
  }

  public Optional<CaptureAttemptFeedback> lastPlanFailure(String conversationId) {
    return Optional.ofNullable(planFailures.get(conversationId));
  }

  public Optional<CaptureAttemptFeedback> lastPatchFailure(
      String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        patchFailures.get(conversationId);
    if (byCapability == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byCapability.get(capabilityId));
  }

  public Optional<CaptureAttemptFeedback> lastValidationFailure(
      String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        validationFailures.get(conversationId);
    if (byCapability == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byCapability.get(capabilityId));
  }

  public void clearPlan(String conversationId) {
    if (conversationId != null) {
      planFailures.remove(conversationId);
      planFailureFingerprints.remove(conversationId);
      fingerprintStore.clear(conversationId);
    }
  }

  public void clearPatch(String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        patchFailures.get(conversationId);
    if (byCapability != null && capabilityId != null) {
      byCapability.remove(capabilityId);
    }
    if (conversationId != null) {
      fingerprintStore.clear(conversationId);
    }
  }

  public void clearValidation(String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        validationFailures.get(conversationId);
    if (byCapability != null && capabilityId != null) {
      byCapability.remove(capabilityId);
    }
    if (conversationId != null) {
      fingerprintStore.clear(conversationId);
    }
  }

  public void clearAll(String conversationId) {
    if (conversationId == null) {
      return;
    }
    planFailures.remove(conversationId);
    planFailureFingerprints.remove(conversationId);
    patchFailures.remove(conversationId);
    validationFailures.remove(conversationId);
    fingerprintStore.clear(conversationId);
  }

  public ToolCallFingerprintStore fingerprintStore() {
    return fingerprintStore;
  }
}
