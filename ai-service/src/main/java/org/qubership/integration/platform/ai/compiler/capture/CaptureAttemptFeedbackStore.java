package org.qubership.integration.platform.ai.compiler.capture;

import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue;

/** Stores capture feedback; requirement failures restore from immutable compiler artifacts. */
@ApplicationScoped
public class CaptureAttemptFeedbackStore {

  private static final Duration DEFAULT_CACHE_IDLE_TIMEOUT = Duration.ofHours(1);

  private final ConcurrentMap<String, CaptureAttemptFeedback> planFailures;
  private final ConcurrentMap<String, RequirementRejection> requirementFailures;
  private final ConcurrentMap<String, String> planFailureFingerprints;
  private final ConcurrentMap<String, ConcurrentHashMap<String, CaptureAttemptFeedback>> patchFailures;
  private final ConcurrentMap<String, ConcurrentHashMap<String, CaptureAttemptFeedback>> validationFailures;
  private final ToolCallFingerprintStore fingerprintStore;
  private final ConcurrentMap<String, String> scopes = idleCache(DEFAULT_CACHE_IDLE_TIMEOUT);
  private final ConcurrentMap<String, PendingProposal> pending = idleCache(DEFAULT_CACHE_IDLE_TIMEOUT);
  private final ConcurrentMap<String, java.util.List<Observation>> observations = idleCache(DEFAULT_CACHE_IDLE_TIMEOUT);
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
  private final ConcurrentMap<String, String> historyOwners = idleCache(DEFAULT_CACHE_IDLE_TIMEOUT);
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;

  @Inject
  public CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore, AppConfig appConfig,
      CompilationArtifacts artifacts, CompilationSessions sessions) {
    this(fingerprintStore, appConfig.capture().feedbackCacheIdleTimeout(), artifacts, sessions);
  }

  public CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore, AppConfig appConfig) {
    this(fingerprintStore, appConfig.capture().feedbackCacheIdleTimeout());
  }

  public CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore,
      CompilationArtifacts artifacts, CompilationSessions sessions) {
    this(fingerprintStore, DEFAULT_CACHE_IDLE_TIMEOUT, artifacts, sessions);
  }

  public CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore) {
    this(fingerprintStore, DEFAULT_CACHE_IDLE_TIMEOUT);
  }

  CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore, Duration cacheIdleTimeout) {
    this(fingerprintStore, cacheIdleTimeout, null, null);
  }

  private CaptureAttemptFeedbackStore(ToolCallFingerprintStore fingerprintStore, Duration cacheIdleTimeout,
      CompilationArtifacts artifacts, CompilationSessions sessions) {
    this.fingerprintStore = fingerprintStore;
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.planFailures = idleCache(cacheIdleTimeout);
    this.requirementFailures = idleCache(cacheIdleTimeout);
    this.planFailureFingerprints = idleCache(cacheIdleTimeout);
    this.patchFailures = idleCache(cacheIdleTimeout);
    this.validationFailures = idleCache(cacheIdleTimeout);
  }

  /** Test helper without CDI. */
  public CaptureAttemptFeedbackStore() {
    this(new ToolCallFingerprintStore());
  }

  public record PendingProposal(String capabilityId, String inputScope, String scope, String toolName,
      com.fasterxml.jackson.databind.JsonNode arguments, String toolCallId) {
    public PendingProposal(String capabilityId, String inputScope, String scope, String toolName,
        com.fasterxml.jackson.databind.JsonNode arguments) {
      this(capabilityId, inputScope, scope, toolName, arguments, null);
    }
  }
  public record Observation(String channel, String capabilityId, PendingProposal proposal, CaptureAttemptFeedback feedback) {}

  /** The runtime pins effective inputs and contract before a skill turn; history grants no authority. */
  public void beginScope(String conversationId, String capabilityId, Object effectiveInputs) {
    beginScope(conversationId, capabilityId, effectiveInputs, conversationId);
  }

  public void beginScope(String conversationId, String capabilityId, Object effectiveInputs, String historyOwner) {
    if (conversationId == null || capabilityId == null) { return; }
    historyOwners.put(conversationId, historyOwner);
    scopes.put(scopeKey(conversationId, capabilityId), CaptureProposal.digest(effectiveInputs));
    pending.remove(conversationId);
  }

  public int identicalFailureCount(String conversationId, String capabilityId) {
    return (int) history(conversationId).stream()
        .filter(observation -> capabilityId.equals(observation.capabilityId()))
        .filter(observation -> observation.feedback() != null
            && observation.feedback().failureClass() == CaptureFailureClass.IDENTICAL_SPAM)
        .count();
  }

  public boolean hasScope(String conversationId, String capabilityId) {
    return scopes.containsKey(scopeKey(conversationId, capabilityId));
  }

  public void endScope(String conversationId, String capabilityId) {
    scopes.remove(scopeKey(conversationId, capabilityId));
    pending.remove(conversationId);
  }

  private String historyOwner(String conversationId) {
    return historyOwners.getOrDefault(conversationId, conversationId);
  }

  /** Runs after cheap runtime checks and before conversion, preview, or mutation. */
  public void beginProposal(String conversationId, String capabilityId, String toolName, Object arguments) {
    beginProposal(conversationId, capabilityId, toolName, arguments, null);
  }

  public void beginProposal(String conversationId, String capabilityId, String toolName, Object arguments, Object base) {
    if (conversationId == null || conversationId.isBlank()) { return; }
    String capability = capabilityId == null ? "" : capabilityId;
    String registeredScope = scopes.get(scopeKey(conversationId, capability));
    String scope = registeredScope == null ? null : base == null ? registeredScope
        : CaptureProposal.digest(java.util.Map.of("effectiveInputs", registeredScope, "base", base));
    pending.remove(conversationId);
    if (scope == null || arguments == null) { return; }
    var proposal = new PendingProposal(capability, registeredScope, scope, toolName,
        JSON.valueToTree(arguments));
    pending.put(conversationId, proposal);
    var prior = history(conversationId).stream().filter(observation -> observation.proposal() != null)
        .filter(observation -> observation.feedback() != null)
        .filter(observation -> observation.proposal().capabilityId().equals(capability)
            && observation.proposal().scope().equals(scope)
            && observation.proposal().toolName().equals(toolName))
        .filter(observation -> CaptureProposal.mutationDigest(toolName, observation.proposal().arguments())
            .equals(CaptureProposal.mutationDigest(toolName, proposal.arguments())))
        .filter(observation -> observation.feedback().kind() != CaptureFailureKind.TOOL_ARGUMENTS)
        .findFirst();
    if (prior.isEmpty()) { return; }
    var previous = prior.orElseThrow();
    var feedback = previous.feedback();
    var duplicate = observe(conversationId, previous.channel(), new CaptureAttemptFeedback(
        feedback.kind(), feedback.summary(), CaptureFailureClass.IDENTICAL_SPAM, false, feedback.fieldHints(), "", true, null, feedback.issues(), feedback.presentationDetails()));
    cache(conversationId, capability, previous.channel(), duplicate);
    throw new CaptureNoProgressException("REPAIR_NO_PROGRESS: The same proposal was rejected under the current inputs. " + duplicate.summary(),
        new CaptureFailureEvidence(duplicate, null, duplicate.evidenceReference(), List.of()));
  }

  private static String scopeKey(String conversationId, String capabilityId) {
    return conversationId + "\u0000" + capabilityId;
  }

  private List<Observation> history(String conversationId) {
    if (artifacts == null) { return observations.getOrDefault(historyOwner(conversationId), List.of()); }
    return sessions.current(historyOwner(conversationId)).map(link -> artifacts.history(link.compilationId(), Kind.CAPTURE_ATTEMPT_FEEDBACK)
        .stream().map(revision -> {
          var observation = artifacts.payload(revision, Observation.class);
          return new Observation(observation.channel(), observation.capabilityId(), observation.proposal(),
              observation.feedback() == null ? null : observation.feedback().withEvidenceReference(revision.reference()));
        }).toList()).orElse(List.of());
  }

  private CaptureAttemptFeedback observe(String conversationId, String channel, CaptureAttemptFeedback feedback) {
    return observe(conversationId, channel, "", feedback);
  }

  private CaptureAttemptFeedback observe(String conversationId, String channel, String channelCapability,
      CaptureAttemptFeedback feedback) {
    var captured = pending.remove(conversationId);
    var proposal = captured;
    String capability = proposal == null ? channelCapability : proposal.capabilityId();
    var observation = new Observation(channel, capability, proposal, feedback);
    if (artifacts != null) {
      var saved = artifacts.append(new AppendCommand(sessions.active(historyOwner(conversationId)), Kind.CAPTURE_ATTEMPT_FEEDBACK,
          "1", "capture-feedback", "1", observation, List.of(), null));
      feedback = feedback.withEvidenceReference(saved.reference());
    } else {
      var updated = new java.util.ArrayList<>(history(conversationId));
      updated.add(observation);
      observations.put(historyOwner(conversationId), List.copyOf(updated));
    }
    if (observation.proposal() == null) { return feedback; }
    var json = JSON;
    var relevant = history(conversationId).stream().filter(item -> item.proposal() != null)
        .filter(item -> item.proposal().capabilityId().equals(observation.proposal().capabilityId())
            && item.proposal().scope().equals(observation.proposal().scope()))
        .map(item -> (com.fasterxml.jackson.databind.JsonNode) json.valueToTree(item)).toList();
    String summary = org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory
        .projectValues(relevant, json, org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.CHAR_BUDGET)
        .toString();
    return new CaptureAttemptFeedback(feedback.kind(), feedback.summary(), feedback.failureClass(),
        feedback.outerAllowed(), feedback.fieldHints(), summary, feedback.noProgress(), feedback.evidenceReference(), feedback.issues(), feedback.presentationDetails());
  }

  /** Retains unmapped input verbatim; validation identity never depends on its payload. */
  public void beginRejectedProposal(String conversationId, String capabilityId, String toolName,
      String rawArguments, String toolCallId) {
    if (conversationId == null) { return; }
    String capability = capabilityId == null ? "" : capabilityId;
    String scope = inputScope(conversationId, capability);
    pending.put(conversationId, new PendingProposal(capability, scope, scope, toolName,
        JSON.getNodeFactory().textNode(rawArguments == null ? "" : rawArguments), toolCallId));
  }

  public String inputScope(String conversationId, String capabilityId) {
    return scopes.getOrDefault(scopeKey(conversationId, capabilityId == null ? "" : capabilityId), "");
  }

  public void recordTypedPlanFailure(String conversationId, String capabilityId,
      CaptureFailureKind kind, CaptureFailureClass failureClass, boolean outerAllowed,
      String summary, List<TypedIssue> issues) {
    cache(conversationId, capabilityId, "PLAN", observe(conversationId, "PLAN", capabilityId,
        new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, List.of(), "", false, null, issues)));
  }

  /** A smaller remaining issue set is progress; changing rejected values alone is not. */
  public boolean recordTypedPatchFailure(String conversationId, String capabilityId, List<TypedIssue> issues) {
    var identities = issues.stream().map(issue -> issue.code() + ":" + issue.entityId() + ":" + issue.path())
        .sorted().toList();
    String fingerprint = fingerprintStore.fingerprint("captureGraphPatch", capabilityId,
        List.of(inputScope(conversationId, capabilityId), identities));
    boolean repeated = fingerprintStore.softCreditUsed(conversationId, fingerprint);
    fingerprintStore.consumeSoftCredit(conversationId, fingerprint);
    String summary = issues.stream().map(issue -> issue.entityId() + " " + issue.path() + ": " + issue.message())
        .collect(java.util.stream.Collectors.joining("\n"));
    var failure = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, summary,
        repeated ? CaptureFailureClass.IDENTICAL_SPAM : CaptureFailureClass.CORRECTABLE,
        !repeated, List.of(), "", false, null, issues);
    cache(conversationId, capabilityId, "PATCH", observe(conversationId, "PATCH", capabilityId, failure));
    return repeated;
  }

  public String repairHistory(String conversationId, String capabilityId) {
    String scope = scopes.get(scopeKey(conversationId, capabilityId));
    if (scope == null) { return ""; }
    var json = JSON;
    var relevant = history(conversationId).stream().filter(item -> item.proposal() != null && item.feedback() != null)
        .filter(item -> item.proposal().capabilityId().equals(capabilityId)
            && item.proposal().inputScope().equals(scope))
        .map(item -> (com.fasterxml.jackson.databind.JsonNode) json.valueToTree(item)).toList();
    return relevant.isEmpty() ? "" : org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory
        .projectValues(relevant, json, org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.CHAR_BUDGET).toString();
  }

  private Optional<CaptureAttemptFeedback> restored(String conversationId, String channel, String capability) {
    return history(conversationId).stream().filter(item -> channel.equals(item.channel()))
        .filter(item -> "PLAN".equals(channel) || Objects.equals(capability, item.capabilityId()))
        .reduce((first, second) -> second).map(Observation::feedback);
  }

  private void clearSaved(String conversationId, String channel, String capability) {
    if (conversationId == null) { return; }
    pending.remove(conversationId);
    if (artifacts == null) {
      var updated = new java.util.ArrayList<>(history(conversationId));
      updated.add(new Observation(channel, capability, null, null));
      observations.put(historyOwner(conversationId), List.copyOf(updated));
      return;
    }
    if (sessions.current(historyOwner(conversationId)).isEmpty()) { return; }
    artifacts.append(new AppendCommand(sessions.active(historyOwner(conversationId)), Kind.CAPTURE_ATTEMPT_FEEDBACK,
        "1", "capture-feedback", "1", new Observation(channel, capability, null, null), List.of(), null));
  }

  private void cache(String conversationId, String capability, String channel, CaptureAttemptFeedback feedback) {
    switch (channel) {
      case "PLAN" -> planFailures.put(conversationId, feedback);
      case "PATCH" -> patchFailures.computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>()).put(capability, feedback);
      case "VALIDATION" -> validationFailures.computeIfAbsent(conversationId, ignored -> new ConcurrentHashMap<>()).put(capability, feedback);
      default -> throw new IllegalArgumentException("Unknown capture channel: " + channel);
    }
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

  public void recordClassifiedRequirementFailure(
      String conversationId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary,
      List<TypedIssue> issues) {
    recordClassifiedRequirementFailure(conversationId, kind, failureClass, outerAllowed, summary, issues, false);
  }

  public void recordClassifiedRequirementFailure(String conversationId, CaptureFailureKind kind,
      CaptureFailureClass failureClass, boolean outerAllowed, String summary, List<TypedIssue> issues,
      boolean noProgress) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    var rejection = new RequirementRejection(new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed,
        List.of(), "", noProgress, null, issues),
        List.copyOf(issues));
    saveRequirementFeedback(conversationId, rejection);
    requirementFailures.put(conversationId, rejection);
  }

  public Optional<CaptureAttemptFeedback> lastRequirementFailure(String conversationId) {
    return lastRequirementRejection(conversationId).map(RequirementRejection::feedback);
  }

  public record RequirementRejection(CaptureAttemptFeedback feedback, List<TypedIssue> issues) {}

  public Optional<Reference> lastRequirementFeedbackReference(String conversationId) {
    return artifacts == null ? Optional.empty() : sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_CAPTURE_FEEDBACK)).map(Revision::reference);
  }

  public Optional<RequirementRejection> lastRequirementRejection(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    if (artifacts != null) {
      return sessions.current(conversationId)
          .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_CAPTURE_FEEDBACK))
          .map(revision -> {
            var rejection = artifacts.payload(revision, RequirementFeedback.class).rejection();
            if (rejection == null) return null;
            var saved = rejection.feedback();
            return new RequirementRejection(new CaptureAttemptFeedback(saved.kind(), saved.summary(), saved.failureClass(),
                saved.outerAllowed(), saved.fieldHints(), saved.repairHistoryJson(), saved.noProgress(),
                revision.reference(), rejection.issues(), saved.presentationDetails()), rejection.issues());
          });
    }
    return Optional.ofNullable(requirementFailures.get(conversationId));
  }

  public void clearRequirement(String conversationId) {
    if (conversationId != null) {
      if (artifacts != null && lastRequirementRejection(conversationId).isPresent()) {
        saveRequirementFeedback(conversationId, null);
      }
      requirementFailures.remove(conversationId);
    }
  }

  private void saveRequirementFeedback(String conversationId, RequirementRejection rejection) {
    if (artifacts == null) {
      return;
    }
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_FEEDBACK);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_CAPTURE_FEEDBACK, "1",
        "requirement-capture-feedback", "1", new RequirementFeedback(rejection), List.of(),
        previous.map(Revision::artifactId).orElse(null)));
  }

  public record RequirementFeedback(RequirementRejection rejection) {}

  public void recordClassifiedPlanFailure(
      String conversationId,
      CaptureFailureKind kind,
      CaptureFailureClass failureClass,
      boolean outerAllowed,
      String summary,
      List<CaptureFieldHint> fieldHints) {
    recordClassifiedPlanFailure(conversationId, kind, failureClass, outerAllowed, summary, fieldHints, null);
  }

  public void recordClassifiedPlanFailure(
      String conversationId, CaptureFailureKind kind, CaptureFailureClass failureClass,
      boolean outerAllowed, String summary, List<CaptureFieldHint> fieldHints, String presentationDetails) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    planFailures.put(
        conversationId,
        observe(conversationId, "PLAN", new CaptureAttemptFeedback(kind, summary, failureClass,
            outerAllowed, fieldHints, "", false, null, List.of(), presentationDetails)));
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
            observe(conversationId, "PATCH", capabilityId, new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints)));
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
            observe(conversationId, "VALIDATION", capabilityId, new CaptureAttemptFeedback(kind, summary, failureClass, outerAllowed, fieldHints)));
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
    planFailures.put(conversationId, observe(conversationId, "PLAN", new CaptureAttemptFeedback(kind, summary)));
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
        .put(capabilityId, observe(conversationId, "PATCH", capabilityId, new CaptureAttemptFeedback(kind, summary)));
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
        .put(capabilityId, observe(conversationId, "VALIDATION", capabilityId, new CaptureAttemptFeedback(kind, summary)));
  }

  public Optional<CaptureAttemptFeedback> lastPlanFailure(String conversationId) {
    return Optional.ofNullable(planFailures.get(conversationId))
        .or(() -> restored(conversationId, "PLAN", ""));
  }

  public Optional<CaptureAttemptFeedback> lastPatchFailure(
      String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        patchFailures.get(conversationId);
    if (byCapability == null) {
      return restored(conversationId, "PATCH", capabilityId);
    }
    return Optional.ofNullable(byCapability.get(capabilityId)).or(() -> restored(conversationId, "PATCH", capabilityId));
  }

  public Optional<CaptureAttemptFeedback> lastValidationFailure(
      String conversationId, String capabilityId) {
    ConcurrentHashMap<String, CaptureAttemptFeedback> byCapability =
        validationFailures.get(conversationId);
    if (byCapability == null) {
      return restored(conversationId, "VALIDATION", capabilityId);
    }
    return Optional.ofNullable(byCapability.get(capabilityId)).or(() -> restored(conversationId, "VALIDATION", capabilityId));
  }

  /** Reads the exact scoped native observation; a reference or scope mismatch supplies no proposal. */
  public CaptureFailureEvidence failureEvidence(
      String conversationId, String capabilityId, CaptureAttemptFeedback feedback) {
    Observation observation = null;
    Reference reference = feedback.evidenceReference();
    String scope = inputScope(conversationId, capabilityId);
    if (artifacts != null && sessions != null && reference != null
        && reference.kind() == Kind.CAPTURE_ATTEMPT_FEEDBACK && !scope.isBlank()) {
      var session = sessions.current(historyOwner(conversationId));
      var stored = session.flatMap(link -> artifacts.get(link.compilationId(), reference));
      if (stored.isPresent()) {
        var nativeValue = artifacts.payload(stored.orElseThrow(), Observation.class);
        var proposal = nativeValue.proposal();
        var saved = nativeValue.feedback();
        // History is a derived display projection. Every native feedback field must still match.
        if (saved != null && proposal != null && Objects.equals(capabilityId, nativeValue.capabilityId())
            && Objects.equals(capabilityId, proposal.capabilityId()) && scope.equals(proposal.inputScope())
            && new CaptureAttemptFeedback(saved.kind(), saved.summary(), saved.failureClass(), saved.outerAllowed(),
                saved.fieldHints(), "", saved.noProgress(), reference, saved.issues(), saved.presentationDetails())
                .equals(new CaptureAttemptFeedback(feedback.kind(), feedback.summary(), feedback.failureClass(),
                    feedback.outerAllowed(), feedback.fieldHints(), "", feedback.noProgress(), reference, feedback.issues(), feedback.presentationDetails()))) {
          observation = nativeValue;
        }
      }
    }
    return new CaptureFailureEvidence(feedback, null, reference, List.of(), observation);
  }

  public void clearPlan(String conversationId) {
    clearSaved(conversationId, "PLAN", "");
    if (conversationId != null) {
      planFailures.remove(conversationId);
      planFailureFingerprints.remove(conversationId);
      fingerprintStore.clear(conversationId);
    }
  }

  public void clearPatch(String conversationId, String capabilityId) {
    clearSaved(conversationId, "PATCH", capabilityId);
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
    clearSaved(conversationId, "VALIDATION", capabilityId);
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
    var channels = history(conversationId).stream().map(item -> item.channel() + "\u0000" + item.capabilityId()).distinct().toList();
    for (String channel : channels) {
      int separator = channel.indexOf('\u0000');
      clearSaved(conversationId, channel.substring(0, separator), channel.substring(separator + 1));
    }
    planFailures.remove(conversationId);
    clearRequirement(conversationId);
    planFailureFingerprints.remove(conversationId);
    patchFailures.remove(conversationId);
    validationFailures.remove(conversationId);
    fingerprintStore.clear(conversationId);
  }

  public ToolCallFingerprintStore fingerprintStore() {
    return fingerprintStore;
  }

  private static <K, V> ConcurrentMap<K, V> idleCache(Duration idleTimeout) {
    return Caffeine.newBuilder()
        .expireAfterAccess(Objects.requireNonNull(idleTimeout, "cacheIdleTimeout"))
        .<K, V>build()
        .asMap();
  }
}
