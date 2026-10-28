package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.ArtifactDecision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Decision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.DecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/** Stores requirement drafts as immutable revisions in the active compilation. */
@ApplicationScoped
public class RequirementDraftStore {

  private final java.util.Set<String> discussionTurns = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, Boolean> capturedThisTurn = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RequirementDiscoveryDirective> turnDirectives =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> lastCaptureRejection = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> captureMutationScopes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> captureMutationRejected = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> currentAuthorText = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<RequirementCaptureInput.FactInput>> rejectedCaptureFacts =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, com.fasterxml.jackson.databind.JsonNode> rejectedCaptureValues = new ConcurrentHashMap<>();
  @Inject org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService elementSchemas;
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;
  private final org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection policyChoices;
  private final RequirementTargetResolver targetResolver;
  private final ConcurrentHashMap<String, org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document>
      sourceInputs = new ConcurrentHashMap<>();


  @Inject
  public RequirementDraftStore(
      CompilationArtifacts artifacts, CompilationSessions sessions,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations,
      RequirementTargetResolver targetResolver) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.conversations = conversations;
    this.targetResolver = targetResolver;
    this.policyChoices = new org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection(artifacts, sessions, conversations);
  }

  public RequirementDraftStore(CompilationArtifacts artifacts, CompilationSessions sessions) {
    this(artifacts, sessions, null, null);
  }

  public RequirementDraftStore(CompilationArtifacts artifacts, CompilationSessions sessions,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this(artifacts, sessions, conversations, null);
  }

  public RequirementDraftStore() {
    this(memoryRuntime());
  }

  private RequirementDraftStore(MemoryRuntime runtime) {
    this(runtime.artifacts(), runtime.sessions());
  }

  public void put(String conversationId, RequirementDraft draft) {
    withWriteAuthority(conversationId, () -> {
      var previous = get(conversationId).orElse(null);
      if (previous != null && previous.authoredDraft() != null
          && !Objects.equals(previous.authoredDraft(), draft.authoredDraft())) {
        throw new IllegalArgumentException("Derived writes must preserve admitted authored requirements.");
      }
      requireCurrentSource(conversationId);
      appendDraft(conversationId, draft, false);
      return null;
    });
  }

  <T> T withWriteAuthority(String conversationId, java.util.function.Supplier<T> action) {
    java.util.function.Supplier<T> serialized = () -> {
      synchronized (this) {
        return action.get();
      }
    };
    if (conversations == null) {
      return serialized.get();
    }
    var current = conversations.archive().read(conversationId);
    return conversations.archive().withAuthority(conversationId, current.generation(),
        org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(current), serialized);
  }

  /** Admission result, including whether the candidate still needs source correction approval. */
  record CaptureAdmission(List<RequirementCaptureEditor.Issue> issues, RequirementRepairPolicy.Strategy strategy,
      CaptureAttemptFeedbackStore.ClarificationFailureKey clarificationFailure, boolean awaitingSourceApproval) {
    CaptureAdmission(List<RequirementCaptureEditor.Issue> issues, RequirementRepairPolicy.Strategy strategy) {
      this(issues, strategy, null, false);
    }
    CaptureAdmission(List<RequirementCaptureEditor.Issue> issues, RequirementRepairPolicy.Strategy strategy,
        CaptureAttemptFeedbackStore.ClarificationFailureKey clarificationFailure) {
      this(issues, strategy, clarificationFailure, false);
    }
  }

  List<RequirementCaptureEditor.Issue> admitCapture(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths) {
    return admitCaptureDecision(conversationId, expectedBase, draft, changes, mappingPaths).issues();
  }

  CaptureAdmission admitCaptureDecision(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths) {
    return admitCaptureDecision(conversationId, expectedBase, draft, changes, mappingPaths, null, null);
  }

  CaptureAdmission admitCaptureDecision(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths,
      ClarificationAdmissionContext clarification, java.util.function.Predicate<String> validCandidate) {
    return withWriteAuthority(conversationId,
        () -> admitCaptureLocked(conversationId, expectedBase, draft, changes, mappingPaths,
            clarification, validCandidate));
  }

  private CaptureAdmission admitCaptureLocked(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths,
      ClarificationAdmissionContext clarification, java.util.function.Predicate<String> validCandidate) {
    var issues = new java.util.ArrayList<RequirementCaptureEditor.Issue>();
    var current = latestRevision(conversationId);
    if (!Objects.equals(expectedBase, current.map(Revision::reference).orElse(null))) {
      issues.add(new RequirementCaptureEditor.Issue("STALE_REQUIREMENT_BASE", "/draft", null,
          "Read the current admitted revision before preparing another candidate."));
    }
    if (!currentSource(conversationId)) {
      issues.add(new RequirementCaptureEditor.Issue("STALE_REQUIREMENT_SOURCE", "/draft", null,
          "This candidate belongs to an obsolete author source. Resume with the current source."));
    }
    var base = current.map(revision -> artifacts.payload(revision, RequirementDraft.class))
        .map(RequirementDraft::authoredDraft).orElse(null);
    var authorPaths = new java.util.LinkedHashSet<String>();
    if (changes != null && changes.amendments() != null) {
      for (int i = 0; i < changes.amendments().size(); i++) {
        var amendment = changes.amendments().get(i);
        var normalized = amendment == null ? null : new RequirementCaptureInput.AmendmentInput(
            RequirementRevisionAdmission.canonicalPath(amendment.path()), amendment.sourceTurnId(), amendment.quote());
        String issuePath = "/changes/amendments/" + i;
        if (normalized == null || !RequirementRevisionAdmission.validPath(base, normalized.path())) {
          String path = normalized == null ? null : normalized.path();
          String parent = path;
          while (parent != null && parent.lastIndexOf('/') > 0
              && !RequirementRevisionAdmission.validPath(base, parent)) {
            parent = parent.substring(0, parent.lastIndexOf('/'));
          }
          issues.add(new RequirementCaptureEditor.Issue("INVALID_AUTHOR_AMENDMENT", issuePath + "/path", null,
              "Requirement path '" + path + "' does not identify an existing amendable field."
                  + (RequirementRevisionAdmission.validPath(base, parent)
                      ? " Amend the existing parent '" + parent + "' when its child does not exist." : "")));
        } else if (!supportsAmendment(conversationId, normalized, base, draft.authoredDraft())) {
          issues.add(new RequirementCaptureEditor.Issue("INVALID_AUTHOR_AMENDMENT", issuePath, null,
              "Cite a fresh READY author source and its exact nonempty quote for an existing requirement path."
                  + " A repair instruction or an earlier consumed source does not authorize an amendment."));
        } else {
          authorPaths.add(normalized.path());
        }
      }
    }
    if (!issues.isEmpty()) {
      return focused(issues);
    }
    issues.addAll(validatePolicySelections(conversationId, current.map(Revision::reference).orElse(null), draft.authoredDraft()));
    if (!issues.isEmpty()) return focused(issues);
    if (base != null) for (var fact : draft.authoredDraft().facts()) {
      if (fact.policyConflict() == null || fact.policyConflict().decision() == null) continue;
      var old = base.facts().stream().filter(value -> value.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
      if (old != null && Objects.equals(old.policyConflict(), fact.policyConflict())
          && old.interactionIds().equals(fact.interactionIds())) continue;
      if (!canResolvePolicyChoice(conversationId, fact)) {
        String decisionPath = "/facts/" + fact.sourceFactId() + "/policyConflict/decision";
        if (authorPaths.stream().anyMatch(path -> decisionPath.equals(path) || decisionPath.startsWith(path + "/"))) {
          policyChoices.offerReplacement(conversationId, fact);
        }
        issues.add(new RequirementCaptureEditor.Issue("POLICY_RESPONSE_REQUIRED", "/changes/facts", fact.sourceFactId(),
            "Record the option selected on the current policy card. A free-text reply or amendment does not select an option."));
      }
    }
    if (!issues.isEmpty()) return focused(issues);
    var repairPaths = activeRepairPaths(conversationId, base, draft);
    var baseline = semanticBaseline(conversationId).orElse(null);
    var packet = sourcePacket(conversationId);
    var restored = sourceRestorationBaseline(conversationId, base, draft, baseline, packet);
    if (restored.isPresent()) {
      baseline = restored.get();
      var paths = new java.util.LinkedHashSet<>(repairPaths);
      var review = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
          .binding(conversationId).orElseThrow().sourceRestoration();
      review.omissions().forEach(report -> paths.add("/facts/" + report.sourceFactId() + "/text"));
      repairPaths = java.util.Set.copyOf(paths);
    }
    issues.addAll(RequirementFailureConsistency.unauthorizedModeChanges(base, draft.authoredDraft(),
        current.map(revision -> artifacts.payload(revision, RequirementDraft.class).catalogBindings()).orElse(List.of()),
        authorPaths, repairPaths));
    if (base == null) {
      issues.addAll(RequirementFailureConsistency.continuedResponsesRequireInlineResponse(
          draft.authoredDraft(), draft.catalogBindings()));
    }
    var admission = RequirementRevisionAdmission.admissionDecision(base, draft.authoredDraft(),
        rejectedCaptureFacts(conversationId), rejectedCaptureValues.get(conversationId), authorPaths, repairPaths, mappingPaths,
        fact -> canResolvePolicyChoice(conversationId, fact), baseline, packet);
    issues.addAll(admission.issues());
    var strategy = admission.strategy();
    var facts = draft.authoredDraft() == null || draft.authoredDraft().facts() == null
        ? List.<RequirementCaptureInput.FactInput>of() : draft.authoredDraft().facts();
    var amendments = changes == null || changes.amendments() == null
        ? List.<RequirementCaptureInput.AmendmentInput>of() : changes.amendments();
    // Accepted claim text and polarity stay protected. A source correction remains an unapproved proposal.
    // Initial capture has no saved draft. Treat the candidate as that draft so a quote-less fact stays unclaimed.
    var accepted = base == null ? facts : base.facts();
    issues.addAll(RequirementClaimEvidenceValidator.validate(baseline, accepted, facts, packet, amendments));
    if (!issues.isEmpty()) {
      return new CaptureAdmission(List.copyOf(issues), strategy);
    }
    var clarificationVerdict = suppliedChainCallVerdict(clarification, draft,
        RequirementRevisionAdmission.clarify(clarification, draft, validCandidate,
            target -> questionDefaults(draft.authoredDraft(), target)));
    var transitionIssues = clarificationNoProgress(clarification, draft, clarificationVerdict.disposition());
    boolean unappliedOnly = clarificationVerdict.issues().stream()
        .allMatch(issue -> "UNAPPLIED_AUTHOR_ANSWER".equals(issue.code()));
    if (!transitionIssues.isEmpty() && unappliedOnly) {
      pendingClarificationAnswers.remove(conversationId);
      var merged = new ArrayList<>(clarificationVerdict.issues());
      merged.addAll(transitionIssues);
      return new CaptureAdmission(List.copyOf(merged), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR,
          clarificationFailureKey(clarification, draft));
    }
    if (!clarificationVerdict.issues().isEmpty()) {
      pendingClarificationAnswers.remove(conversationId);
      return new CaptureAdmission(clarificationVerdict.issues(), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
    }
    if (clarificationVerdict.disposition() != null && clarification != null && clarification.offer() != null) {
      pendingClarificationAnswers.put(conversationId, new org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Answer(
          clarification.offer().offerId(), clarification.answerTurnId(), clarificationVerdict.disposition()));
    } else {
      pendingClarificationAnswers.remove(conversationId);
    }
    if (restored.isPresent()) {
      clearRejectedCaptureFacts(conversationId);
      return new CaptureAdmission(List.of(), strategy, null, true);
    }
    if (current.isEmpty() || !draft.equals(artifacts.payload(current.orElseThrow(), RequirementDraft.class))) {
      appendDraft(conversationId, draft, true);
      recordSemanticBaseline(conversationId, baseline, facts, packet, amendments);
    }
    clearRejectedCaptureFacts(conversationId);
    return new CaptureAdmission(List.of(), strategy);
  }

  /** Blocks provisional source restoration after any brief or explicit draft approval was recorded. */
  public boolean sourceRestorationBlocked(String runId) {
    if (runId == null || runId.isBlank() || !artifacts.history(runId, Kind.REQUIREMENT_BRIEF).isEmpty()) return true;
    for (var revision : artifacts.history(runId, Kind.APPROVAL_RECORD)) {
      var approval = artifacts.payload(revision, com.fasterxml.jackson.databind.JsonNode.class);
      if (!approval.path("target").hasNonNull("kind")
          || Kind.REQUIREMENT_DRAFT.name().equals(approval.path("target").path("kind").asText())
          || java.util.stream.StreamSupport.stream(approval.path("approvedCandidates").spliterator(), false)
              .anyMatch(candidate -> Kind.REQUIREMENT_DRAFT.name().equals(candidate.path("kind").asText()))) return true;
    }
    return false;
  }

  /** Returns the unique current typed brief approval input, or null when it cannot be bound. */
  public String sourceRestorationApprovalSource(String conversationId, Reference target, long expectedRunRevision) {
    if (conversations == null || target == null || target.kind() != Kind.REQUIREMENT_BRIEF) return null;
    var inputs = conversations.archive().read(conversationId).activeTurns().stream()
        .filter(turn -> turn.message().role()
            == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER).toList();
    if (inputs.isEmpty()) return null;
    var latest = inputs.getLast();
    var matching = inputs.stream().filter(turn -> typedBriefApproval(turn, target)
        && turn.metadata().path("resolvedDecision").path("revision").asLong(-1) == expectedRunRevision).toList();
    return matching.size() == 1 && matching.getFirst().equals(latest) ? latest.id() : null;
  }

  private static boolean typedBriefApproval(
      org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Turn turn, Reference target) {
    if (!"READY".equals(turn.status()) || turn.metadata() == null) return false;
    var decision = turn.metadata().path("resolvedDecision");
    return org.qubership.integration.platform.ai.chat.ChatEvent.APPROVE_ACTION.equals(decision.path("action").asText())
        && "requirement-brief".equals(decision.path("artifactType").asText())
        && Objects.equals(target.contentHash(), decision.path("artifactHash").asText())
        && decision.path("comment").asText("").isBlank();
  }

  /** Identifies a brief that references a persisted provisional source correction. */
  public boolean hasSourceRestorationCandidate(String conversationId, Reference brief) {
    if (runJournal == null || brief == null || brief.kind() != Kind.REQUIREMENT_BRIEF) return false;
    var document = runJournal.loadByConversation(conversationId).orElse(null);
    if (document == null || document.run() == null) return false;
    var candidate = sourceCorrectionCandidate(document.run(), brief, true);
    return candidate != null && captureAttemptsNewestFirst(conversationId).stream().anyMatch(attempt ->
        attempt.accepted() && attempt.sourceRestoration() != null
            && Objects.equals(attempt.resolvedCandidate(), candidate.authoredDraft()));
  }

  /**
   * Checks a staged source correction without changing its draft or run. Unrelated briefs are unchanged.
   *
   * @throws IllegalStateException when a staged correction has stale source, base, or candidate pins
   */
  public void validateSourceRestorationApproval(String conversationId, Reference brief, String sourceTurnId) {
    if (runJournal == null || brief == null || brief.kind() != Kind.REQUIREMENT_BRIEF) return;
    withWriteAuthority(conversationId, () -> {
      var document = runJournal.loadByConversation(conversationId).orElse(null);
      if (document == null || document.run() == null) return null;
      var run = document.run();
      if (run.status() != org.qubership.integration.platform.ai.productpipeline.store.RunStatus.WAITING_FOR_APPROVAL
          || run.stages().stream().noneMatch(stage -> Objects.equals(stage.stageId(), run.currentStageId())
              && stage.status() == org.qubership.integration.platform.ai.productpipeline.store.StageStatus.WAITING_FOR_APPROVAL
              && brief.equals(stage.approvableReference()) && stage.candidateReferences().contains(brief))) return null;
      var candidate = sourceCorrectionCandidate(run, brief, true);
      if (candidate == null) return null;
      for (var attempt : captureAttemptsNewestFirst(conversationId)) {
        if (!attempt.accepted() || attempt.sourceRestoration() == null
            || !Objects.equals(attempt.resolvedCandidate(), candidate.authoredDraft())) continue;
        var current = get(conversationId).orElse(null);
        var baseline = semanticBaseline(conversationId).orElse(null);
        if (!Objects.equals(attempt.acceptedDraftReference(), latestRevision(conversationId).map(Revision::reference).orElse(null))
            || !Objects.equals(attempt.semanticBaselineReference(), baselineReference(conversationId))
            || sourceRestorationBaseline(current, candidate, baseline,
                sourceRestorationPacket(conversationId, brief, sourceTurnId), attempt.sourceRestoration()).isEmpty()) {
          throw new IllegalStateException("Source correction approval has obsolete source, base, or candidate pins.");
        }
        break;
      }
      return null;
    });
  }

  private RequirementDraft sourceCorrectionCandidate(
      org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot run, Reference briefReference, boolean includeCandidates) {
    var briefRevision = artifacts.get(run.runId(), briefReference).orElse(null);
    if (briefRevision == null) return null;
    var brief = artifacts.payload(briefRevision,
        org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief.class);
    return run.stages().stream().flatMap(stage -> java.util.stream.Stream.concat(
        stage.outputRefs().stream(), includeCandidates ? stage.candidateReferences().stream() : java.util.stream.Stream.empty()))
        .filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT
            && Objects.equals(ref.artifactId(), brief.approvedDraftReference()))
        .map(ref -> artifacts.get(run.runId(), ref).orElse(null)).filter(Objects::nonNull)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class)).findFirst().orElse(null);
  }

  private AuthorSourceEvidence.Packet sourceRestorationPacket(String conversationId, Reference brief, String sourceTurnId) {
    var packet = sourcePacket(conversationId);
    if (conversations == null || sourceTurnId == null || sourceTurnId.isBlank()) return packet;
    boolean pinnedControl = conversations.archive().read(conversationId).activeTurns().stream()
        .anyMatch(turn -> Objects.equals(turn.id(), sourceTurnId) && typedBriefApproval(turn, brief));
    return !pinnedControl ? packet : new AuthorSourceEvidence.Packet(packet.generation(), packet.inputs().stream()
        .filter(input -> !Objects.equals(input.sourceTurnId(), sourceTurnId)).toList());
  }

  /**
   * Promotes a staged source correction after its exact brief approval commits. Unrelated briefs are unchanged;
   * replay completes an interrupted draft and baseline pair without repeating an applied promotion.
   *
   * @throws IllegalStateException when a pending correction no longer matches its pinned source, draft, or baseline
   */
  public void promoteSourceRestoration(String conversationId, Reference approvedBrief) {
    if (runJournal == null || approvedBrief == null || approvedBrief.kind() != Kind.REQUIREMENT_BRIEF) return;
    withWriteAuthority(conversationId, () -> {
      var document = runJournal.loadByConversation(conversationId).orElse(null);
      if (document == null || document.run() == null) return null;
      var run = document.run();
      var committedApproval = run.stages().stream().filter(stage ->
          stage.status() == org.qubership.integration.platform.ai.productpipeline.store.StageStatus.SUCCEEDED
              && Objects.equals(stage.approvedArtifactId(), approvedBrief.artifactId())
              && stage.outputRefs().contains(approvedBrief))
              .flatMap(stage -> stage.outputRefs().stream()).filter(ref -> ref.kind() == Kind.APPROVAL_RECORD)
                  .map(ref -> artifacts.get(run.runId(), ref).orElse(null)).filter(Objects::nonNull)
                  .map(revision -> artifacts.payload(revision, com.fasterxml.jackson.databind.JsonNode.class))
                  .filter(record -> record.path("target").equals(new ObjectMapper().valueToTree(approvedBrief))
                      && Objects.equals(record.path("targetContentHash").asText(), approvedBrief.contentHash()))
                  .findFirst().orElse(null);
      if (committedApproval == null) return null;
      var candidate = sourceCorrectionCandidate(run, approvedBrief, false);
      if (candidate == null) return null;
      var current = get(conversationId).orElse(null);
      if (current == null) return null;
      var currentBaseline = semanticBaseline(conversationId).orElse(null);
      var packet = sourceRestorationPacket(conversationId, approvedBrief, committedApproval.path("sourceTurnId").asText(""));
      for (var attempt : captureAttemptsNewestFirst(conversationId)) {
        if (!attempt.accepted() || attempt.sourceRestoration() == null
            || !Objects.equals(attempt.resolvedCandidate(), candidate.authoredDraft())) continue;
        String compilationId = sessions.current(conversationId).orElseThrow().compilationId();
        var baseRevision = artifacts.get(compilationId, attempt.acceptedDraftReference()).orElseThrow(
            () -> new IllegalStateException("Source correction base is unavailable."));
        var baselineRevision = artifacts.get(compilationId, attempt.semanticBaselineReference()).orElseThrow(
            () -> new IllegalStateException("Source correction baseline is unavailable."));
        var base = artifacts.payload(baseRevision, RequirementDraft.class);
        var baseline = artifacts.payload(baselineRevision, RequirementSemanticBaseline.class);
        if (!attempt.sourceRestoration().omissions().isEmpty() && Objects.equals(current, candidate)
            && Objects.equals(currentBaseline, restoredBaseline(baseline, attempt.sourceRestoration().omissions()))) {
          return null;
        }
        var restored = sourceRestorationBaseline(base, candidate, baseline, packet, attempt.sourceRestoration())
            .orElseThrow(() -> new IllegalStateException("Source correction approval has obsolete source or candidate pins."));
        if ((!Objects.equals(current, base) && !Objects.equals(current, candidate))
            || (!Objects.equals(currentBaseline, baseline) && !Objects.equals(currentBaseline, restored))) {
          throw new IllegalStateException("Source correction approval no longer matches the admitted draft and baseline.");
        }
        if (!Objects.equals(current, candidate)) appendDraft(conversationId, candidate, false);
        if (!Objects.equals(currentBaseline, restored)) appendSemanticBaseline(conversationId, restored);
        break;
      }
      return null;
    });
  }

  private Optional<RequirementSemanticBaseline> sourceRestorationBaseline(String conversationId,
      RequirementCaptureInput.DraftInput base, RequirementDraft candidate, RequirementSemanticBaseline baseline,
      AuthorSourceEvidence.Packet packet) {
    var binding = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .binding(conversationId).filter(value -> value.mode()
            == org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode.DISCOVERY)
        .orElse(null);
    var review = binding == null ? null : binding.sourceRestoration();
    var current = get(conversationId).orElse(null);
    if (review == null || sourceRestorationBlocked(binding.runId())
        || binding.approvedDraft() == null || !Objects.equals(current, binding.approvedDraft())
        || !currentSource(conversationId) || !Objects.equals(packet, currentBodySource(conversationId))) {
      return Optional.empty();
    }
    if (baseline != null) for (var report : review.omissions()) {
      String factId = report.sourceFactId();
      var claim = baseline.claims().stream().filter(value -> Objects.equals(value.sourceFactId(), factId))
          .findFirst().orElse(null);
      if (sessions.current(conversationId).stream().flatMap(link ->
          artifacts.history(link.compilationId(), Kind.REQUIREMENT_SEMANTIC_BASELINE).stream())
          .flatMap(revision -> artifacts.payload(revision, RequirementSemanticBaseline.class).claims().stream())
          .filter(value -> Objects.equals(value.sourceFactId(), factId))
          .anyMatch(value -> !value.equals(claim))) return Optional.empty();
    }
    return sourceRestorationBaseline(current, candidate, baseline, packet, review);
  }

  private Optional<RequirementSemanticBaseline> sourceRestorationBaseline(RequirementDraft current,
      RequirementDraft candidate, RequirementSemanticBaseline baseline,
      AuthorSourceEvidence.Packet packet, ProductRequirementBriefTool.SourceReview review) {
    var base = current == null ? null : current.authoredDraft();
    if (review == null
        || baseline == null || base == null || current == null || candidate == null
        || review.omissions().isEmpty() || packet == null
        || !Objects.equals(review.generation(), packet.generation())
        || !Objects.equals(review.sourceHash(), org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(packet))
        || !Objects.equals(review.draftHash(), org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(current))
        || !Objects.equals(current.catalogBindings(), candidate.catalogBindings())) return Optional.empty();
    var replacements = new java.util.LinkedHashMap<String, RequirementCaptureInput.FactInput>();
    for (var report : review.omissions()) {
      if (report.kind() != RequirementAnalysisCapture.SourceDiscrepancyKind.UNSUPPORTED_ADDITION
          || replacements.containsKey(report.sourceFactId())) return Optional.empty();
      var fact = base.facts().stream().filter(value -> Objects.equals(value.sourceFactId(), report.sourceFactId()))
          .findFirst().orElse(null);
      var claim = baseline.claims().stream().filter(value -> Objects.equals(value.sourceFactId(), report.sourceFactId()))
          .findFirst().orElse(null);
      if (fact == null || claim == null || fact.evidence() == null || fact.policyConflict() != null
          || !Objects.equals(fact.evidence(), claim.evidence()) || !Objects.equals(fact.text(), claim.text())
          || fact.polarity() != claim.polarity() || Objects.equals(fact.text(), report.quote())
          || !Objects.equals(fact.evidence().sourceTurnId(), report.sourceTurnId())
          || !Objects.equals(fact.evidence().quote(), report.quote())
          || packet.inputs().stream().noneMatch(input -> Objects.equals(input.sourceTurnId(), report.sourceTurnId())
              && report.quote() != null && !report.quote().isBlank() && input.text().contains(report.quote()))) return Optional.empty();
      replacements.put(fact.sourceFactId(), new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(),
          fact.polarity(), report.quote(), fact.fieldMapping(), fact.policyConflict(), fact.elementIntent(), fact.evidence()));
    }
    var expected = new RequirementCaptureInput.DraftInput(base.flow(), base.facts().stream()
        .map(value -> replacements.getOrDefault(value.sourceFactId(), value)).toList(),
        base.capabilities(), base.openQuestions(), base.settings());
    if (!expected.equals(candidate.authoredDraft())) return Optional.empty();
    return Optional.of(restoredBaseline(baseline, review.omissions()));
  }

  private static RequirementSemanticBaseline restoredBaseline(RequirementSemanticBaseline baseline,
      List<RequirementAnalysisCapture.SourceOmission> reports) {
    return new RequirementSemanticBaseline(baseline.sourceGeneration(), baseline.authorEvidenceHash(),
        baseline.claims().stream().map(value -> reports.stream()
            .filter(report -> Objects.equals(value.sourceFactId(), report.sourceFactId())).findFirst()
            .map(report -> new RequirementSemanticClaim(value.sourceFactId(), report.quote(), value.polarity(), value.evidence()))
            .orElse(value)).toList());
  }

  private static CaptureAdmission focused(List<RequirementCaptureEditor.Issue> issues) {
    return new CaptureAdmission(List.copyOf(issues), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
  }

  private static List<RequirementCaptureEditor.Issue> clarificationNoProgress(
      ClarificationAdmissionContext clarification, RequirementDraft candidate,
      org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition admitted) {
    if (clarification == null || clarification.offer() == null || clarification.pinnedQuestion() == null
        || candidate == null) {
      return List.of();
    }
    var active = new RequirementClarificationCoordinator.ActiveQuestion(clarification.offer().draftReference(),
        clarification.pinnedQuestion(), clarification.offer().sourceGeneration(), clarification.targetProjection());
    return new RequirementClarificationCoordinator().validateTransition(active, clarification.answerTurnId(),
        clarification.pinnedBase(), candidate, admitted);
  }

  private static CaptureAttemptFeedbackStore.ClarificationFailureKey clarificationFailureKey(
      ClarificationAdmissionContext clarification, RequirementDraft candidate) {
    var question = clarification.pinnedQuestion();
    var evidence = question == null ? null : question.evidence();
    var before = clarification.targetProjection();
    var after = new RequirementClarificationCoordinator().targetProjection(candidate, question);
    return new CaptureAttemptFeedbackStore.ClarificationFailureKey(clarification.answerTurnId(),
        clarification.offer().offerId(), evidence == null ? "" : evidence.interactionId(),
        evidence == null ? "" : evidence.propertyKey(), before, after);
  }

  public Optional<RequirementSemanticBaseline> semanticBaseline(String conversationId) {
    return sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_SEMANTIC_BASELINE))
        .map(revision -> artifacts.payload(revision, RequirementSemanticBaseline.class));
  }

  private void recordSemanticBaseline(String conversationId, RequirementSemanticBaseline previous,
      List<RequirementCaptureInput.FactInput> facts, AuthorSourceEvidence.Packet packet,
      List<RequirementCaptureInput.AmendmentInput> amendments) {
    var baseline = RequirementClaimEvidenceValidator.reconcile(previous, facts, packet, amendments);
    if (previous == null && baseline.claims().isEmpty()) {
      return;
    }
    appendSemanticBaseline(conversationId, baseline);
  }

  private Reference baselineReference(String conversationId) {
    return sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_SEMANTIC_BASELINE))
        .map(Revision::reference).orElse(null);
  }

  private Reference appendSemanticBaseline(String conversationId, RequirementSemanticBaseline baseline) {
    String compilationId = sessions.active(conversationId);
    var prior = artifacts.latest(compilationId, Kind.REQUIREMENT_SEMANTIC_BASELINE);
    return artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_SEMANTIC_BASELINE, "1",
        "requirement-draft-store", "1", baseline, List.of(), prior.map(Revision::artifactId).orElse(null)))
        .reference();
  }

  private AuthorSourceEvidence.Packet sourcePacket(String conversationId) {
    if (conversations != null) {
      return AuthorSourceEvidence.packet(conversations.archive().read(conversationId));
    }
    var authority = writeAuthority(conversationId);
    String text = currentAuthorText.get(conversationId);
    if (text == null || text.isBlank()) {
      return new AuthorSourceEvidence.Packet(authority.sourceGeneration(), List.of());
    }
    return new AuthorSourceEvidence.Packet(authority.sourceGeneration(),
        List.of(new AuthorSourceEvidence.Input(
            authority.sourceTurnId() == null ? "current" : authority.sourceTurnId(), text)));
  }

  com.fasterxml.jackson.databind.node.ObjectNode copyRejectedCaptureValues(String conversationId) {
    var values = rejectedCaptureValues.get(conversationId);
    return values == null ? null : values.deepCopy();
  }

  void replaceRejectedCaptureValues(String conversationId, com.fasterxml.jackson.databind.node.ObjectNode values) {
    if (values == null) rejectedCaptureValues.remove(conversationId);
    else rejectedCaptureValues.put(conversationId, values);
  }

  void noteNamedModeCorrection(String conversationId, RequirementCaptureInput.DraftInput restored,
      List<CatalogBindingHint> bindings) {
    var values = rejectedCaptureValues.get(conversationId);
    if (values == null || restored == null || restored.flow() == null) return;
    var interactions = values.path("interactions");
    if (!interactions.isObject() || restored.flow().interactions() == null) return;
    var reverted = restored.flow().interactions().stream().map(value -> {
      var stored = interactions.path(value.interactionId()).path("failureMode");
      boolean named = value.failureMode()
          == org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.INLINE_RESPONSE
          && (stored.isMissingNode() || stored.isNull()
              || org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.PROPAGATE.name()
                  .equals(stored.asText()));
      return named ? new RequirementCaptureInput.InteractionInput(value.interactionId(), value.direction(),
          value.participant(), value.operation(), value.description(),
          org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.PROPAGATE, value.retryPolicy())
          : value;
    }).toList();
    var probe = new RequirementCaptureInput.DraftInput(
        new RequirementCaptureInput.FlowInput(reverted, restored.flow().transitions()), restored.facts(),
        restored.capabilities(), restored.openQuestions(), restored.settings());
    for (var finding : RequirementFailureConsistency.issues(probe, bindings == null ? List.of() : bindings)) {
      var node = interactions.path(finding.entityId());
      var storedMode = node.path("failureMode");
      if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object
          && (storedMode.isNull() || storedMode.isMissingNode()
              || org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.PROPAGATE.name()
                  .equals(storedMode.asText()))) {
        object.put("failureMode",
            org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.INLINE_RESPONSE.name());
      }
    }
  }

  RequirementCaptureInput.DraftInput restoreProtectedCandidate(String conversationId,
      RequirementCaptureInput.DraftInput candidate, List<CatalogBindingHint> bindings,
      List<RequirementCaptureEditor.Issue> issues) {
    var base = latestRevision(conversationId)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class).authoredDraft()).orElse(null);
    return RequirementRevisionAdmission.restoreProtectedSnapshot(base, candidate,
        rejectedCaptureValues.get(conversationId), bindings, issues);
  }

  public List<RequirementCaptureEditor.Issue> validateCurrent(String conversationId) {
    var current = get(conversationId).orElse(null);
    if (current == null || current.authoredDraft() == null) return List.of();
    return RequirementRevisionAdmission.validate(current.authoredDraft(), current.authoredDraft(),
        rejectedCaptureFacts(conversationId), rejectedCaptureValues.get(conversationId), java.util.Set.of(),
        java.util.Set.of(), java.util.Map.of(), fact -> false);
  }

  public List<RequirementCaptureEditor.Issue> validateForContinuation(String conversationId) {
    var issues = new ArrayList<>(validateCurrent(conversationId));
    var current = get(conversationId).orElse(null);
    issues.addAll(RequirementFailureConsistency.issues(current));
    if (current == null || current.authoredDraft() == null) return issues;
    var input = current.authoredDraft();
    issues.addAll(validatePolicySelections(conversationId,
        latestRevision(conversationId).map(Revision::reference).orElse(null), input));
    for (var capability : input.capabilities()) {
      if (issues.stream().anyMatch(issue -> "CAPABILITY_ANCHOR_CONFLICT".equals(issue.code())
          && capability.interactionId().equals(issue.entityId()))) continue;
      if (!current.readyForPlan() && input.openQuestions().stream().noneMatch(question ->
          question.evidence() != null && capability.interactionId().equals(question.evidence().interactionId()))) {
        continue;
      }
      boolean declared = input.facts().stream()
          .filter(fact -> fact.polarity() == RequirementCaptureInput.Polarity.POSITIVE)
          .map(RequirementCaptureInput.FactInput::elementIntent)
          .anyMatch(intent -> intent != null && intent.placement() == RequirementElementIntent.Placement.ANCHOR
              && capability.interactionId().equals(intent.elementRef())
              && capability.interactionId().equals(intent.ownerInteractionId())
              && capability.capabilityKey().equals(intent.elementType()));
      if (!declared) {
        issues.add(new RequirementCaptureEditor.Issue("MISSING_ANCHOR_INTENT", "/draft/facts",
            capability.interactionId(), "Selected native element '" + capability.interactionId()
                + "' needs a positive fact with elementIntent placement=ANCHOR, elementRef=ownerInteractionId='"
                + capability.interactionId() + "', and elementType='" + capability.capabilityKey()
                + "'. Add the fact without replacing accepted facts. Copy its explicit name from the saved author"
                + " source into requestedName; use null only when no name was supplied. Participant and operation"
                + " do not bind an element name. This is generated metadata to complete, not missing author data."));
      }
    }
    return List.copyOf(issues);
  }

  public java.util.Set<String> activeRequirementRepairPaths(String conversationId) {
    return activeRepairPaths(conversationId, get(conversationId).map(RequirementDraft::authoredDraft).orElse(null));
  }

  private java.util.Set<String> activeRepairPaths(String conversationId, RequirementCaptureInput.DraftInput base) {
    return activeRepairPaths(conversationId, base, null);
  }

  private java.util.Set<String> activeRepairPaths(String conversationId, RequirementCaptureInput.DraftInput base,
      RequirementDraft candidate) {
    var context = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .binding(conversationId)
        .filter(binding -> binding.mode()
            == org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode.DISCOVERY);
    var paths = new java.util.LinkedHashSet<>(context
        .filter(binding -> binding.approvedDraft() != null && Objects.equals(base, binding.approvedDraft().authoredDraft()))
        .map(binding -> binding.requirementRepairPaths()).orElse(java.util.Set.of()));
    var current = get(conversationId).orElse(null);
    if (context.isPresent() && current != null && base != null && Objects.equals(base, current.authoredDraft())) {
      var findings = RequirementFailureConsistency.issues(base, current.catalogBindings());
      // A finding's pointer identifies the obligation; source proof supplies completion authority.
      paths.removeIf(path -> path.matches("/facts/[^/]+/elementIntent/bodyBinding(?:/.*)?"));
      var source = currentBodySource(conversationId);
      boolean sameBase = context.orElseThrow().approvedDraft() == null
          || Objects.equals(base, context.orElseThrow().approvedDraft().authoredDraft());
      boolean sameBindings = candidate == null || Objects.equals(current.catalogBindings(), candidate.catalogBindings());
      var bodyPaths = source == null || !sameBase || !sameBindings ? java.util.Set.<String>of()
          : candidate == null ? RequirementBodySources.missingBindingPaths(base, source)
              : RequirementBodySources.completionPaths(base, candidate.authoredDraft(), source);
      paths.addAll(bodyPaths);
      // A causal-reopen grant must obey the same boundary as the freshly derived native finding.
      if (candidate != null) findings.forEach(issue ->
          paths.remove("/interactions/" + issue.entityId() + "/failureMode"));
      if (currentSource(conversationId)
          && (context.orElseThrow().approvedDraft() == null
              || Objects.equals(base, context.orElseThrow().approvedDraft().authoredDraft()))
          && (candidate == null || Objects.equals(current.catalogBindings(), candidate.catalogBindings())
              && RequirementFailureConsistency.correctsOnlyModes(base,
                  RequirementBodySources.normalizeCompletedBindings(candidate.authoredDraft(), bodyPaths), findings))) {
        findings.stream()
            .filter(issue -> candidate == null || candidate.authoredDraft().flow().interactions().stream()
                .anyMatch(value -> issue.entityId().equals(value.interactionId())
                    && value.failureMode() == org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.INLINE_RESPONSE))
            .forEach(issue -> paths.add("/interactions/" + issue.entityId() + "/failureMode"));
      }
    }
    return java.util.Set.copyOf(paths);
  }

  private AuthorSourceEvidence.Packet currentBodySource(String conversationId) {
    if (conversations == null || !sourceInputs.containsKey(conversationId) || !currentSource(conversationId)) return null;
    var source = AuthorSourceEvidence.packet(sourceInputs.get(conversationId));
    var current = AuthorSourceEvidence.packet(conversations.archive().read(conversationId));
    return Objects.equals(source, current) ? source : null;
  }

  public static boolean isRequirementRepairPath(RequirementDraft draft, String path) {
    return draft != null && RequirementRevisionAdmission.validPath(draft.authoredDraft(), path)
        && !path.contains("/policyConflict");
  }

  /** Resolves a native finding to the same stable field used by revision admission. */
  public static String requirementRepairPath(RequirementDraft draft,
      org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding finding) {
    if (finding == null || !finding.blocker() || finding.fieldPointer().isBlank()) return null;
    try {
      String path = finding.fieldPointer();
      if (path.startsWith("/draft/")) path = path.substring("/draft".length());
      if (path.startsWith("/flow/")) path = path.substring("/flow".length());
      path = path.replace("/facts[]/", "/facts/").replace("/interactions[]/", "/interactions/")
          .replace("/capabilities[]/", "/capabilities/");
      if (path.startsWith("/") && !finding.originRecordId().isBlank() && !isRequirementRepairPath(draft, path)) {
        var pointer = com.fasterxml.jackson.core.JsonPointer.compile(path);
        String family = pointer.getMatchingProperty();
        if (List.of("facts", "interactions", "capabilities").contains(family)) {
          var tail = pointer.tail();
          if (tail.getMatchingIndex() >= 0) tail = tail.tail();
          path = "/" + family + "/" + finding.originRecordId() + tail;
        } else {
          path = "/facts/" + finding.originRecordId() + path;
        }
      }
      var pointer = com.fasterxml.jackson.core.JsonPointer.compile(path);
      if (List.of("facts", "interactions", "capabilities").contains(pointer.getMatchingProperty())
          && !finding.originRecordId().isBlank()
          && !finding.originRecordId().equals(pointer.tail().getMatchingProperty())) return null;
      return isRequirementRepairPath(draft, path) ? path : null;
    } catch (IllegalArgumentException invalidEvidence) {
      return null;
    }
  }

  private boolean supportsAmendment(String conversationId, RequirementCaptureInput.AmendmentInput amendment,
      RequirementCaptureInput.DraftInput base, RequirementCaptureInput.DraftInput candidate) {
    if (amendment.quote() == null || amendment.quote().isBlank() || amendmentAlreadyAdmitted(conversationId, amendment.path())) {
      return false;
    }
    var source = sourceInputs.get(conversationId);
    if (source == null) {
      return "current".equals(amendment.sourceTurnId())
          && currentAuthorText.getOrDefault(conversationId, "").contains(amendment.quote());
    }
    return source.activeTurns().stream().anyMatch(turn -> Objects.equals(turn.id(), amendment.sourceTurnId())
        && turn.message().role() == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
        && "READY".equals(turn.status()) && turn.consumedDraftReference() == null
        && (org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection.isPolicyDecisionSource(turn)
            ? turn.message().content().equals(amendment.quote()) && policyChoices.permitsAmendment(conversationId,
                latestRevision(conversationId).map(Revision::reference).orElse(null), source.generation(), turn.id(), base, candidate, amendment.path())
            : turn.message().content().contains(amendment.quote())));
  }

  private boolean amendmentAlreadyAdmitted(String conversationId, String path) {
    var current = factProtection(conversationId, List.of());
    return artifacts.history(sessions.active(conversationId), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream()
        .map(revision -> artifacts.payload(revision, RequirementCaptureAttempt.class))
        .filter(RequirementCaptureAttempt::accepted)
        .filter(attempt -> new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(),
            attempt.authorText(), List.of()).sameSource(current))
        .map(attempt -> new ObjectMapper().<com.fasterxml.jackson.databind.JsonNode>valueToTree(attempt.arguments()))
        .anyMatch(arguments -> arguments.path("amendments").findValuesAsText("path").stream()
            .map(RequirementRevisionAdmission::canonicalPath)
            .anyMatch(prior -> prior.equals(path) || prior.startsWith(path + "/") || path.startsWith(prior + "/")));
  }

  private boolean currentSource(String conversationId) {
    if (conversations == null || !sourceInputs.containsKey(conversationId)) {
      return true;
    }
    var expected = sourceInputs.get(conversationId);
    var current = conversations.archive().read(conversationId);
    return expected.generation().equals(current.generation())
        && Objects.equals(org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(expected),
            org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(current));
  }

  private void requireCurrentSource(String conversationId) {
    if (!currentSource(conversationId)) {
      throw new IllegalStateException("Requirement source authority is obsolete.");
    }
  }

  private void appendDraft(String conversationId, RequirementDraft draft, boolean consumeSource) {
    if (draft.authoredDraft() != null) {
      var authored = draft.authoredDraft();
      var facts = RequirementCaptureProjection.facts(authored).subList(0, authored.facts().size());
      if (!draft.facts().containsAll(facts)
          || !draft.flow().equals(RequirementCaptureProjection.flow(authored))) {
        throw new IllegalArgumentException("Derived requirements must retain the admitted source facts and flow.");
      }
    }
    var previousAuthored = get(conversationId).map(RequirementDraft::authoredDraft).orElse(null);
    lastCaptureRejection.remove(conversationId);
    String compilationId = sessions.active(conversationId);
    String revisesArtifactId =
        artifacts
            .latest(compilationId, Kind.REQUIREMENT_DRAFT)
            .map(Revision::artifactId)
            .orElse(null);
    var source = consumeSource ? sourceInputs.get(conversationId) : null;
    List<Reference> inputs = source == null ? List.of() : source.activeTurns().stream()
        .filter(turn -> turn.message().role()
            == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()) && turn.consumedDraftReference() == null)
        .map(turn -> artifacts.append(new AppendCommand(compilationId, Kind.USER_INPUT, "1",
            "conversation-source", "1",
            new org.qubership.integration.platform.ai.productpipeline.artifact.UserInput(turn.id(),
                "conversation-source", turn.message().content(), turn.message().createdAt(), null, List.of(turn.id())),
            List.of(), null)).reference()).toList();
    Revision saved = artifacts.append(
        new AppendCommand(
            compilationId,
            Kind.REQUIREMENT_DRAFT,
            "2",
            draft.sourceSkillId() != null ? draft.sourceSkillId() : "requirement-draft-store",
            draft.sourceSkillVersion(),
            draft,
            inputs,
            revisesArtifactId));
    if (!Objects.equals(previousAuthored, draft.authoredDraft())) clearRejectedCaptureFacts(conversationId);
    if (source != null && conversations != null) {
      conversations.archive().consume(conversationId, source.generation(),
          source.activeTurns().stream().map(turn -> turn.id()).toList(), saved.artifactId());
    }
  }

  /** Restores producer state without treating the pending correction as captured. */
  public void restoreAccepted(String conversationId, RequirementDraft draft) {
    if (get(conversationId).filter(draft::equals).isPresent()) {
      return;
    }
    beginTurn(conversationId);
    put(conversationId, draft);
  }

  public Optional<Revision> latestRevision(String conversationId) {
    return sessions
        .current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_DRAFT));
  }

  public record WriteAuthority(Reference base, String sourceGeneration, String sourceTurnId) {}

  public WriteAuthority writeAuthority(String conversationId) {
    var source = conversations == null ? null : conversations.archive().read(conversationId);
    return new WriteAuthority(latestRevision(conversationId).map(Revision::reference).orElse(null),
        source == null ? null : source.generation(), source == null ? null
            : org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source));
  }

  public boolean isCurrentWrite(String conversationId, WriteAuthority expected) {
    return Objects.equals(expected, writeAuthority(conversationId));
  }

  public boolean putIfCurrent(String conversationId, WriteAuthority expected, RequirementDraft draft) {
    return withWriteAuthority(conversationId, () -> {
      if (!isCurrentWrite(conversationId, expected) || !currentSource(conversationId)) {
        return false;
      }
      put(conversationId, draft);
      return true;
    });
  }

  public Optional<RequirementDraft> get(String conversationId) {
    return latestRevision(conversationId)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class));
  }

  /** Returns effective answers with the revision and sources that first admitted each value. */
  public List<AuthorAnswer> confirmedAnswers(String conversationId) {
    var current = sessions.current(conversationId);
    if (current.isEmpty()) {
      return List.of();
    }
    var answers = new java.util.LinkedHashMap<String, AuthorAnswer>();
    for (var revision : artifacts.history(current.get().compilationId(), Kind.REQUIREMENT_DRAFT)) {
      var draft = artifacts.payload(revision, RequirementDraft.class).authoredDraft();
      if (draft == null) {
        continue;
      }
      var effective = new java.util.HashSet<String>();
      for (var fact : draft.facts()) {
        var policy = fact.policyConflict();
        if (policy != null && policy.decision() != null) {
          effective.add(fact.sourceFactId());
          var previous = answers.get(fact.sourceFactId());
          if (previous == null || !Objects.equals(previous.policy(), policy)
              || !previous.interactionIds().equals(fact.interactionIds())) {
            answers.put(fact.sourceFactId(), new AuthorAnswer(fact.sourceFactId(), policy.questionId(),
                fact.interactionIds(), policy, revision.reference(), revision.inputs()));
          }
        }
      }
      answers.keySet().retainAll(effective);
    }
    return List.copyOf(answers.values());
  }

  public record AuthorAnswer(String sourceFactId, String questionId, List<String> interactionIds,
      RequirementPolicyConflict policy, Reference admittedRevision, List<Reference> sourceInputs) {
    public AuthorAnswer {
      interactionIds = List.copyOf(interactionIds);
      sourceInputs = List.copyOf(sourceInputs);
    }
  }

  public List<org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection.TrustedSelection>
      trustedPolicyChoices(String conversationId) {
    return policyChoices.trusted(conversationId);
  }

  public Optional<org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection.Pending>
      pendingPolicyReplacement(String conversationId) {
    return policyChoices.pendingReplacement(conversationId);
  }

  private List<org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection.TrustedSelection>
      activePolicyChoices(String conversationId, Reference base) {
    if (conversations == null || base == null) return List.of();
    var source = conversations.archive().read(conversationId);
    String turnId = org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    return policyChoices.trusted(conversationId).stream().filter(choice ->
        choice.selection().offered().draftReference().equals(base)
            && Objects.equals(choice.selection().sourceGeneration(), source.generation())
            && Objects.equals(choice.selection().sourceTurnId(), turnId)).toList();
  }

  private List<RequirementCaptureEditor.Issue> validatePolicySelections(String conversationId, Reference base,
      RequirementCaptureInput.DraftInput candidate) {
    var issues = new ArrayList<RequirementCaptureEditor.Issue>();
    for (var choice : activePolicyChoices(conversationId, base)) {
      var selected = choice.selection();
      var fact = candidate.facts().stream().filter(value ->
          value.sourceFactId().equals(selected.offered().fact().sourceFactId())).findFirst().orElse(null);
      if (fact == null || !policyChoices.permits(conversationId, base,
          selected.sourceGeneration(), selected.sourceTurnId(), fact)) {
        issues.add(new RequirementCaptureEditor.Issue("POLICY_RESPONSE_REQUIRED",
            "/draft/facts/" + selected.offered().fact().sourceFactId() + "/policyConflict/decision",
            selected.offered().fact().sourceFactId(), "Record the validated " + selected.decision()
                + " selection (" + selected.selectedValue() + ") without changing its policy contract or owner."));
      }
      var owner = selected.offered().fact().interactionIds().getFirst();
      for (var original : get(conversationId).orElseThrow().authoredDraft().facts()) {
        var intent = original.elementIntent();
        if (intent == null || intent.placement() != RequirementElementIntent.Placement.ANCHOR
            || !owner.equals(intent.elementRef()) || !owner.equals(intent.ownerInteractionId())) continue;
        var replacement = candidate.facts().stream().filter(value -> value.sourceFactId().equals(original.sourceFactId()))
            .map(RequirementCaptureInput.FactInput::elementIntent).filter(Objects::nonNull).findFirst().orElse(null);
        for (int index = 0; index < intent.requestedProperties().size(); index++) {
          var property = intent.requestedProperties().get(index);
          if (!property.key().equals(selected.offered().fact().policyConflict().propertyKey())) continue;
          if (replacement == null || !owner.equals(replacement.elementRef()) || !owner.equals(replacement.ownerInteractionId())
              || replacement.placement() != intent.placement() || !intent.elementType().equals(replacement.elementType())
              || replacement.requestedProperties().size() <= index
              || !property.key().equals(replacement.requestedProperties().get(index).key())
              || !selected.selectedValue().equals(replacement.requestedProperties().get(index).value())) {
            issues.add(new RequirementCaptureEditor.Issue("POLICY_RESPONSE_REQUIRED",
                "/draft/facts/" + original.sourceFactId() + "/elementIntent/requestedProperties/" + index + "/value",
                original.sourceFactId(), "Apply the validated " + selected.decision() + " value (" + selected.selectedValue()
                    + ") to this existing owned property in the same capture as its decision."));
          }
        }
      }
    }
    return List.copyOf(issues);
  }

  public void recordIdsChoice(String conversationId, Boolean choice) {
    withWriteAuthority(conversationId, () -> {
    var previous = get(conversationId).orElseThrow();
    if (previous.idsRequested() != null || choice == null) {
      throw new IllegalArgumentException("An unresolved IDS choice and an author response are required.");
    }
    requireCurrentSource(conversationId);
    if (sourceInputs.containsKey(conversationId) && wasCapturedThisTurn(conversationId)) {
      throw new IllegalArgumentException("A fresh author response is required for the IDS choice.");
    }
    appendDraft(conversationId, previous.withIdsRequested(choice), true);
    return null;
    });
  }

  public ArtifactDecision approve(
      String conversationId, Reference target, String actor, String comment) {
    Objects.requireNonNull(target, "target");
    if (target.kind() != Kind.REQUIREMENT_DRAFT) {
      throw new IllegalArgumentException("approval target must be a requirement draft");
    }

    String compilationId =
        sessions
            .current(conversationId)
            .map(link -> link.compilationId())
            .orElseThrow(() -> new IllegalStateException("active compilation is required"));

    Revision revision =
        artifacts
            .get(compilationId, target)
            .orElseThrow(
                () -> new IllegalArgumentException("requirement draft revision was not found"));

    if (isStale(conversationId, target)) {
      throw new IllegalStateException("requirement draft is stale");
    }

    RequirementDraft draft = artifacts.payload(revision, RequirementDraft.class);
    if (!draft.readyForPlan()) {
      throw new IllegalStateException("requirement draft is not ready for plan");
    }

    return artifacts.recordDecision(
        new DecisionCommand(compilationId, target, Decision.APPROVED, actor, comment));
  }

  public boolean isApproved(String conversationId, Reference target) {
    if (target == null || target.kind() != Kind.REQUIREMENT_DRAFT) {
      return false;
    }
    return sessions
        .current(conversationId)
        .map(link -> artifacts.isApproved(link.compilationId(), target))
        .orElse(false);
  }

  public boolean isStale(String conversationId, Reference target) {
    if (target == null || target.kind() != Kind.REQUIREMENT_DRAFT) {
      return true;
    }
    Optional<String> compilationId =
        sessions.current(conversationId).map(link -> link.compilationId());
    if (compilationId.isEmpty()) {
      return true;
    }
    if (artifacts.get(compilationId.get(), target).isEmpty()) {
      return true;
    }
    Optional<Revision> latestDraft = latestRevision(conversationId);
    if (latestDraft.isEmpty()) {
      return true;
    }
    return !latestDraft.orElseThrow().artifactId().equals(target.artifactId());
  }

  public Optional<Revision> latestApprovedRevision(String conversationId) {
    return latestRevision(conversationId)
        .filter(
            revision -> {
              Reference reference = revision.reference();
              return !isStale(conversationId, reference)
                  && isApproved(conversationId, reference);
            });
  }

  public void remove(String conversationId) {
    sessions.startNew(conversationId);
    capturedThisTurn.remove(conversationId);
    discussionTurns.remove(conversationId);
    pendingClarificationAnswers.remove(conversationId);
    turnDirectives.remove(conversationId);
    lastCaptureRejection.remove(conversationId);
    captureMutationScopes.remove(conversationId);
    captureMutationRejected.remove(conversationId);
    currentAuthorText.remove(conversationId);
    rejectedCaptureFacts.remove(conversationId);
  }

  /** Removes the pending projection. The semantic baseline and the accepted revision stay. */
  public void rebuildPendingProjection(String conversationId) {
    withWriteAuthority(conversationId, () -> {
      rejectedCaptureFacts.remove(conversationId);
      rejectedCaptureValues.remove(conversationId);
      var previous = latestCaptureAttempt(conversationId).orElse(null);
      if (previous == null || clearsPending(previous)) {
        return null;
      }
      saveCaptureAttempt(conversationId, new RequirementCaptureAttempt(previous.sourceGeneration(),
          previous.sourceTurnId(), previous.authorText(), previous.mutationScope(), "projection-rebuild", null, true,
          List.of(), previous.acceptedDraftReference(), previous.repairAuthority(), previous.argumentBinding(),
          previous.authorEvidenceHash(), null, previous.semanticBaselineReference(),
          RequirementRepairPolicy.Strategy.PROJECTION_REBUILD, previous.clarificationAnswer()));
      return null;
    });
  }

  /** Clears per-turn gather flags without starting a new compilation. */
  public void clearTurnFlags(String conversationId) {
    clearRejectedCaptureFacts(conversationId);
    latestCaptureAttempt(conversationId).ifPresent(previous -> saveCaptureAttempt(conversationId,
        new RequirementCaptureAttempt(previous.sourceGeneration(), previous.sourceTurnId(), previous.authorText(),
            previous.mutationScope(), "reset", null, true, List.of(), previous.acceptedDraftReference())));
    sourceInputs.remove(conversationId);
    capturedThisTurn.remove(conversationId);
    discussionTurns.remove(conversationId);
    pendingClarificationAnswers.remove(conversationId);
    turnDirectives.remove(conversationId);
    lastCaptureRejection.remove(conversationId);
    captureMutationScopes.remove(conversationId);
    captureMutationRejected.remove(conversationId);
    currentAuthorText.remove(conversationId);
    rejectedCaptureFacts.remove(conversationId);
  }

  /** Returns the active compilation identity used for subsequent artifact revisions. */
  public String activeCompilationId(String conversationId) {
    return sessions.active(conversationId);
  }

  /** Starts a gather invocation while retaining protection for the same author source. */
  public void beginTurn(String conversationId) {
    beginTurn(conversationId, null);
  }

  public void beginTurn(String conversationId, String authorText) {
    discussionTurns.remove(conversationId);
    var source = conversations == null ? null : conversations.archive().read(conversationId);
    var previousSource = sourceInputs.get(conversationId);
    boolean sameAuthorTurn = source == null
        ? authorText != null && Objects.equals(currentAuthorText.get(conversationId), authorText)
        : previousSource != null && previousSource.generation().equals(source.generation())
            && Objects.equals(
                org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(previousSource),
                org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source));
    if (!sameAuthorTurn) {
      if (previousSource != null || currentAuthorText.containsKey(conversationId)) {
        lastCaptureRejection.remove(conversationId);
      }
      capturedThisTurn.put(conversationId, capturedFromSource(conversationId, source));
      rejectedCaptureFacts.remove(conversationId);
      rejectedCaptureValues.remove(conversationId);
      if (source != null || authorText != null) {
        captureMutationScopes.remove(conversationId);
      }
    }
    if (source != null && !sameAuthorTurn) {
      sourceInputs.put(conversationId, source);
    }
    if (authorText == null) {
      currentAuthorText.remove(conversationId);
    } else {
      currentAuthorText.put(conversationId, authorText);
    }
    if (!sameAuthorTurn) {
      var session = sessions.current(conversationId);
      if (session.isPresent()) {
        LegacyCaptureProtectionAdapter.authority(artifacts, session.orElseThrow().compilationId(),
            factProtection(conversationId, List.of())).ifPresent(previous -> {
              rejectedCaptureFacts.put(conversationId, previous.facts());
              if (previous.values() != null) {
                rejectedCaptureValues.put(conversationId,
                    RequirementRevisionAdmission.pendingValues(previous.values()));
              }
            });
      }
    }
    turnDirectives.remove(conversationId);
  }

  private boolean capturedFromSource(String conversationId,
      org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document source) {
    if (source == null) {
      return false;
    }
    String inputId = org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    if (inputId == null) {
      return false;
    }
    Optional<Revision> accepted = latestRevision(conversationId);
    if (accepted.isEmpty()) {
      return false;
    }
    if (source.activeTurns().stream().anyMatch(turn -> inputId.equals(turn.id())
        && turn.consumedDraftReference() != null)) {
      return true;
    }
    // The accepted artifact can persist before the archive acknowledges source consumption.
    Revision draft = accepted.get();
    return draft.inputs().stream().filter(ref -> ref.kind() == Kind.USER_INPUT)
        .map(ref -> artifacts.get(draft.compilationId(), ref).orElseThrow())
        .map(revision -> artifacts.payload(revision,
            org.qubership.integration.platform.ai.productpipeline.artifact.UserInput.class))
        .anyMatch(input -> input.sourceTurnIds().contains(inputId));
  }

  boolean canResolvePolicyChoice(String conversationId, RequirementCaptureInput.FactInput candidate) {
    var source = sourceInputs.get(conversationId);
    if (source == null || conversations == null || !currentSource(conversationId)) return false;
    String turnId = org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    boolean fresh = conversations.archive().read(conversationId).activeTurns().stream().anyMatch(turn ->
        turn.id().equals(turnId)
            && turn.message().role() == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()) && turn.consumedDraftReference() == null);
    return fresh && policyChoices.permits(conversationId, latestRevision(conversationId).map(Revision::reference).orElse(null),
        source.generation(), turnId, candidate);
  }

  void rememberRejectedCaptureFacts(
      String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    var accumulated = RequirementRevisionAdmission.accumulate(rejectedCaptureFacts(conversationId), facts);
    if (!accumulated.equals(rejectedCaptureFacts(conversationId))) {
      rejectedCaptureFacts.put(conversationId, accumulated);
    }
  }

  List<RequirementCaptureInput.FactInput> rejectedCaptureFacts(String conversationId) {
    return rejectedCaptureFacts.getOrDefault(conversationId, List.of());
  }

  public RequirementCaptureResult.RepairConstraints repairConstraints(String conversationId) {
    var facts = rejectedCaptureFacts(conversationId);
    var values = RequirementRevisionAdmission.pendingValues(rejectedCaptureValues.get(conversationId));
    return facts.isEmpty() && values.isEmpty() ? null : new RequirementCaptureResult.RepairConstraints(facts, values);
  }

  void clearRejectedCaptureFacts(String conversationId) {
    rejectedCaptureFacts.remove(conversationId);
    rejectedCaptureValues.remove(conversationId);
  }

  private CaptureFactProtection factProtection(
      String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    var source = sourceInputs.get(conversationId);
    String inputId = source == null ? null
        : org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    return new CaptureFactProtection(inputId == null ? null : source.generation(), inputId,
        inputId == null ? currentAuthorText.get(conversationId) : null, facts, rejectedCaptureValues.get(conversationId),
        get(conversationId).map(RequirementDraft::authoredDraft)
            .map(org.qubership.integration.platform.ai.compiler.capture.CaptureProposal::digest).orElse(null));
  }

  public record CaptureFactProtection(String sourceGeneration, String sourceTurnId, String authorText,
      List<RequirementCaptureInput.FactInput> facts, com.fasterxml.jackson.databind.JsonNode values, String baseIdentity) {
    public CaptureFactProtection(String sourceGeneration, String sourceTurnId, String authorText,
        List<RequirementCaptureInput.FactInput> facts) {
      this(sourceGeneration, sourceTurnId, authorText, facts, null, null);
    }

    public CaptureFactProtection {
      facts = List.copyOf(facts);
    }

    boolean sameSource(CaptureFactProtection other) {
      return sourceTurnId != null
          ? Objects.equals(sourceGeneration, other.sourceGeneration) && sourceTurnId.equals(other.sourceTurnId)
          : authorText != null && other.sourceTurnId == null && authorText.equals(other.authorText);
    }
  }

  public String readSourceTurn(String conversationId, String turnId, int offset, int maxChars) {
    if (conversations == null) {
      throw new IllegalStateException("Conversation source storage is unavailable.");
    }
    return conversations.archive().readSource(conversationId, turnId, offset, maxChars);
  }

  public Optional<String> currentAuthorText(String conversationId) {
    var source = sourceInputs.get(conversationId);
    if (source != null) {
      return source.activeTurns().stream().filter(turn -> turn.message().role()
          == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
          && "READY".equals(turn.status())).reduce((first, last) -> last)
          .map(turn -> turn.message().content());
    }
    return Optional.ofNullable(currentAuthorText.get(conversationId));
  }

  private ProductPipelineRunStore runJournal;
  private final ConcurrentHashMap<String, org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Answer>
      pendingClarificationAnswers = new ConcurrentHashMap<>();

  public void useRunJournal(ProductPipelineRunStore runs) {
    this.runJournal = runs;
  }

  /** Returns the journal run id for this conversation, or an empty string when no run is open. */
  public String activeRunId(String conversationId) {
    if (runJournal == null || conversationId == null || conversationId.isBlank()) {
      return "";
    }
    return runJournal.loadByConversation(conversationId)
        .map(document -> document.run() == null || document.run().runId() == null ? "" : document.run().runId())
        .orElse("");
  }

  /**
   * The pinned offer, answer turn, and optional model disposition for the current author turn.
   * Null when this conversation has no journal offer for the latest ready turn.
   */
  public ClarificationAdmissionContext clarificationContext(String conversationId,
      RequirementCaptureInput.DraftUpdate changes) {
    if (runJournal == null || conversations == null || conversationId == null) {
      return null;
    }
    var source = conversations.archive().read(conversationId);
    var latest = source.activeTurns().stream()
        .filter(turn -> turn.message().role()
            == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()))
        .reduce((earlier, later) -> later);
    if (latest.isEmpty()) {
      return null;
    }
    String offerId = clarificationOfferId(latest.get().metadata());
    if (offerId.isBlank()) {
      return null;
    }
    var document = runJournal.loadByConversation(conversationId).orElse(null);
    if (document == null) {
      return null;
    }
    org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Offer offer = null;
    for (var transition : document.transitions()) {
      var clarification = transition.clarification();
      if (clarification != null && clarification.offer() != null
          && offerId.equals(clarification.offer().offerId())) {
        offer = clarification.offer();
      }
    }
    if (offer == null) {
      return null;
    }
    var pinnedOffer = offer;
    var pinned = loadPinnedDraft(conversationId, pinnedOffer.draftReference());
    if (pinned == null || pinned.authoredDraft() == null) {
      return null;
    }
    var question = pinned.authoredDraft().openQuestions().stream()
        .filter(value -> value != null && pinnedOffer.questionId().equals(value.questionId()))
        .findFirst().orElse(null);
    if (question == null) {
      return null;
    }
    var coordinator = new RequirementClarificationCoordinator();
    var disposition = changes == null ? null : changes.clarificationDisposition();
    boolean focused = focusedAnswerRepair(conversationId, latest.get().id());
    var readyInputIds = source.activeTurns().stream()
        .filter(turn -> turn.message().role()
            == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()))
        .map(turn -> turn.id()).collect(java.util.stream.Collectors.toSet());
    return new ClarificationAdmissionContext(pinnedOffer, latest.get().id(), pinned, question,
        coordinator.targetProjection(pinned, question), disposition, focused, latest.get().message().content(),
        readyInputIds);
  }

  private RequirementDraft loadPinnedDraft(String conversationId, Reference reference) {
    if (reference == null) {
      return null;
    }
    return artifacts.get(sessions.active(conversationId), reference)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class)).orElse(null);
  }

  public Optional<org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Answer>
      latestClarificationAnswer(String conversationId) {
    for (var attempt : captureAttemptsNewestFirst(conversationId)) {
      if (attempt.accepted() && attempt.clarificationAnswer() != null) {
        return Optional.of(attempt.clarificationAnswer());
      }
    }
    return Optional.empty();
  }

  private boolean focusedAnswerRepair(String conversationId, String answerTurnId) {
    for (var attempt : captureAttemptsNewestFirst(conversationId)) {
      if (attempt.recoveryStrategy() == null) {
        continue;
      }
      var codes = attempt.issues().stream().map(issue -> issue.code()).toList();
      return !attempt.accepted()
          && attempt.recoveryStrategy() == RequirementRepairPolicy.Strategy.FOCUSED_REPAIR
          && Objects.equals(attempt.sourceTurnId(), answerTurnId)
          && codes.contains("UNAPPLIED_AUTHOR_ANSWER")
          && !codes.contains("DISPOSITION_NOT_AUTHORIZED");
    }
    return false;
  }

  private static String clarificationOfferId(com.fasterxml.jackson.databind.JsonNode metadata) {
    if (metadata == null || metadata.isNull()) {
      return "";
    }
    var direct = metadata.get("clarificationOfferId");
    if (direct != null && !direct.isNull()) {
      return direct.asText("");
    }
    var submitted = metadata.get("submitted");
    if (submitted != null && submitted.isObject()) {
      return clarificationOfferId(submitted);
    }
    return "";
  }

  /** Offer, archived answer, pinned draft, and the optional model disposition for one admission. */
  public record ClarificationAdmissionContext(
      org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Offer offer,
      String answerTurnId,
      RequirementDraft pinnedBase,
      RequirementCaptureInput.QuestionInput pinnedQuestion,
      com.fasterxml.jackson.databind.JsonNode targetProjection,
      RequirementCaptureInput.ClarificationDispositionInput disposition,
      boolean focusedAnswerRepair,
      String answerText,
      java.util.Set<String> readyInputIds) {
    public ClarificationAdmissionContext {
      readyInputIds = readyInputIds == null ? java.util.Set.of() : java.util.Set.copyOf(readyInputIds);
    }
  }

  /** An accepted but unrelated revision cannot consume an answer to an unchanged question. */
  public List<RequirementCaptureEditor.Issue> validateAuthorQuestions(String conversationId) {
    var current = get(conversationId).orElse(null);
    if (current == null) return List.of();
    var issues = new java.util.ArrayList<RequirementCaptureEditor.Issue>();
    var source = conversations == null ? null : conversations.archive().read(conversationId);
    var inputIds = source == null ? java.util.Set.<String>of() : source.activeTurns().stream()
        .filter(turn -> turn.message().role()
            == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()))
        .map(turn -> turn.id()).collect(java.util.stream.Collectors.toSet());
    var context = clarificationContext(conversationId, null);
    var base = context == null ? null : context.pinnedBase();
    if (current.authoredDraft() == null) {
      if (base != null && base.openQuestions().equals(current.openQuestions())) {
        issues.add(new RequirementCaptureEditor.Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions", null,
            "The admitted answer has not changed the saved question. Read the saved source and apply it,"
                + " or capture scoped evidence explaining the remaining ambiguity."));
      }
      return issues;
    }
    var draft = current.authoredDraft();
    var facts = RequirementCaptureProjection.facts(draft);
    var accepted = latestClarificationAnswer(conversationId).orElse(null);
    boolean classifiedOpen = context != null && accepted != null
        && accepted.offerId().equals(context.offer().offerId())
        && accepted.disposition()
            != org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition.ANSWER;
    String pinnedId = context == null ? null : context.pinnedQuestion().questionId();
    for (var question : draft.openQuestions()) {
      if (question == null) continue;
      var evidence = question.evidence();
      String path = "/draft/openQuestions/" + question.questionId();
      boolean pinned = pinnedId != null && pinnedId.equals(question.questionId());
      boolean untouched = !pinned && base != null && base.authoredDraft() != null
          && base.authoredDraft().openQuestions().stream().anyMatch(old -> old != null
              && old.questionId().equals(question.questionId()) && Objects.equals(old, question));
      var findings = new java.util.ArrayList<String>();
      if (evidence != null) {
        if (!question.interactionIds().contains(evidence.interactionId()) && facts.stream().noneMatch(fact ->
            fact.elementIntent() != null && evidence.interactionId().equals(fact.elementIntent().elementRef())
                && question.interactionIds().contains(fact.elementIntent().ownerInteractionId()))) {
          findings.add("Question evidence must target the question's interaction.");
        }
        org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission.validate(
            evidence, facts, current.flow(), current.catalogBindings(), inputIds,
            untouched ? null : inputIds, questionDefaults(draft, evidence.interactionId()),
            true, findings);
      } else if (source != null && !untouched) {
        var schema = (dev.langchain4j.model.chat.request.json.JsonObjectSchema) RequirementCaptureSchemas
            .parameters("evidence", org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.class)
            .properties().get("evidence");
        var kinds = (dev.langchain4j.model.chat.request.json.JsonEnumSchema) schema.properties().get("kind");
        findings.add("Field " + path + "/evidence has rejected value null. Evidence is required when source history"
            + " is available or this repeats a prior question; null is reserved for legacy questions with neither."
            + " The native evidence schema requires fields " + schema.required() + "; kind enum: " + kinds.enumValues()
            + ". Review saved author sources, then update this existing question using changes.updateQuestions."
            + " Keep its questionId and interactionIds; target its existing interaction or owned element and the"
            + " unresolved propertyKey. Set reviewedInputIds to exactly the source turn IDs below."
            + " Keep unresolved choices open and leave unrelated accepted records unchanged; do not add them again.");
      }
      if (pinned && !classifiedOpen) {
        findings.add("The admitted answer has not resolved this question. Apply it to its target field,"
            + " or classify it from focused answer repair.");
      }
      if (!findings.isEmpty()) issues.add(new RequirementCaptureEditor.Issue(
          pinned ? "UNAPPLIED_AUTHOR_ANSWER" : "AUTHOR_QUESTION_NOT_SUPPORTED", path, question.questionId(),
          String.join(" ", findings) + " Source turn IDs: " + inputIds));
    }
    if (base != null && base.authoredDraft() != null) {
      var removals = new java.util.ArrayList<>(withoutSuppliedChainCalls(
          RequirementRevisionAdmission.unappliedRemovals(base.authoredDraft(), current,
              target -> questionDefaults(draft, target)),
          base.authoredDraft(), current));
      for (var extra : unsuppliedChainCallRemovals(base.authoredDraft(), current)) {
        if (removals.stream().noneMatch(issue -> extra.code().equals(issue.code())
            && extra.entityId().equals(issue.entityId()))) {
          removals.add(extra);
        }
      }
      issues.addAll(removals);
    }
    return List.copyOf(issues);
  }

  /**
   * A removed chain-call catalog question is supplied when the capability already names one verified
   * chain-trigger element, or the projection already resolved one. Fact-field checks do not see
   * {@code targetReference}.
   */
  private RequirementRevisionAdmission.ClarificationVerdict suppliedChainCallVerdict(
      ClarificationAdmissionContext clarification, RequirementDraft candidate,
      RequirementRevisionAdmission.ClarificationVerdict verdict) {
    if (verdict == null || clarification == null || clarification.pinnedBase() == null
        || clarification.pinnedBase().authoredDraft() == null) {
      return verdict;
    }
    var base = clarification.pinnedBase().authoredDraft();
    var issues = new java.util.ArrayList<>(verdict.issues());
    for (var extra : unsuppliedChainCallRemovals(base, candidate)) {
      if (issues.stream().noneMatch(issue -> extra.code().equals(issue.code())
          && extra.entityId().equals(issue.entityId()))) {
        issues.add(extra);
      }
    }
    var remaining = withoutSuppliedChainCalls(issues, base, candidate);
    if (remaining.equals(verdict.issues())) {
      return verdict;
    }
    if (!remaining.isEmpty()) {
      return new RequirementRevisionAdmission.ClarificationVerdict(remaining, verdict.disposition());
    }
    boolean pinnedClosed = clarification.pinnedQuestion() != null && candidate != null
        && candidate.authoredDraft() != null && candidate.authoredDraft().openQuestions().stream()
            .noneMatch(question -> question != null
                && clarification.pinnedQuestion().questionId().equals(question.questionId()));
    var disposition = verdict.disposition() != null ? verdict.disposition() : pinnedClosed
        ? org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition.ANSWER
        : null;
    return new RequirementRevisionAdmission.ClarificationVerdict(List.of(), disposition);
  }

  private List<RequirementCaptureEditor.Issue> withoutSuppliedChainCalls(
      List<RequirementCaptureEditor.Issue> issues, RequirementCaptureInput.DraftInput base,
      RequirementDraft current) {
    if (issues == null || issues.isEmpty() || base == null || base.openQuestions() == null) {
      return issues == null ? List.of() : issues;
    }
    return issues.stream().filter(issue -> !suppliedChainCallRemoval(issue, base, current)).toList();
  }

  private boolean suppliedChainCallRemoval(RequirementCaptureEditor.Issue issue,
      RequirementCaptureInput.DraftInput base, RequirementDraft current) {
    if (issue == null || !"UNAPPLIED_AUTHOR_ANSWER".equals(issue.code()) || issue.entityId() == null) {
      return false;
    }
    var question = base.openQuestions().stream()
        .filter(item -> item != null && issue.entityId().equals(item.questionId())).findFirst().orElse(null);
    if (question == null || question.evidence() == null || question.evidence().kind()
        != org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.CATALOG_CHOICE) {
      return false;
    }
    return chainCallSupplied(current, question.evidence().interactionId());
  }

  private List<RequirementCaptureEditor.Issue> unsuppliedChainCallRemovals(
      RequirementCaptureInput.DraftInput base, RequirementDraft current) {
    if (base == null || base.openQuestions() == null || current == null || current.authoredDraft() == null) {
      return List.of();
    }
    var open = current.authoredDraft().openQuestions();
    var issues = new java.util.ArrayList<RequirementCaptureEditor.Issue>();
    for (var question : base.openQuestions()) {
      if (question == null || question.evidence() == null || question.evidence().kind()
          != org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.CATALOG_CHOICE
          || open.stream().anyMatch(item -> item != null && question.questionId().equals(item.questionId()))
          || !chainCallQuestion(current, question.evidence().interactionId())
          || chainCallSupplied(current, question.evidence().interactionId())) {
        continue;
      }
      issues.add(new RequirementCaptureEditor.Issue("UNAPPLIED_AUTHOR_ANSWER",
          "/draft/openQuestions/" + question.questionId(), question.questionId(),
          "Removing a chain-call question requires targetReference or one verified chain trigger."));
    }
    return List.copyOf(issues);
  }

  private static boolean chainCallQuestion(RequirementDraft current, String interactionId) {
    return current != null && current.authoredDraft() != null && current.authoredDraft().capabilities() != null
        && interactionId != null && current.authoredDraft().capabilities().stream().anyMatch(item -> item != null
            && interactionId.equals(item.interactionId()) && "chain-call-2".equals(item.capabilityKey()));
  }

  private boolean chainCallSupplied(RequirementDraft current, String interactionId) {
    if (current == null || current.authoredDraft() == null || current.authoredDraft().capabilities() == null
        || interactionId == null || interactionId.isBlank()) {
      return false;
    }
    var capability = current.authoredDraft().capabilities().stream()
        .filter(item -> item != null && interactionId.equals(item.interactionId())
            && "chain-call-2".equals(item.capabilityKey())).findFirst().orElse(null);
    if (capability == null) {
      return false;
    }
    if (suppliedChainTriggerId(capability.targetReference())) {
      return true;
    }
    return suppliedChainTriggerId(RequirementCaptureProjection.resolvedTargets(current).get(interactionId));
  }

  private boolean suppliedChainTriggerId(String value) {
    if (value == null || value.isBlank() || value.contains("/")) {
      return false;
    }
    return targetResolver == null || targetResolver.listedChainTrigger(value);
  }

  private java.util.Set<String> questionDefaults(RequirementCaptureInput.DraftInput draft, String target) {
    if (elementSchemas == null) return java.util.Set.of();
    String type = draft.facts().stream().map(RequirementCaptureInput.FactInput::elementIntent)
        .filter(intent -> intent != null && target.equals(intent.elementRef()))
        .map(RequirementElementIntent::elementType).findFirst().orElseGet(() -> draft.capabilities().stream()
            .filter(capability -> target.equals(capability.interactionId()))
            .map(RequirementCaptureInput.CapabilityInput::capabilityKey).findFirst().orElse(null));
    var properties = draft.facts().stream().map(RequirementCaptureInput.FactInput::elementIntent)
        .filter(intent -> intent != null && target.equals(intent.elementRef()))
        .flatMap(intent -> intent.requestedProperties().stream()).toList();
    var existing = properties.stream().map(org.qubership.integration.platform.ai.plan.model.PlanProperty::key)
        .collect(java.util.stream.Collectors.toSet());
    return elementSchemas.withUnconditionalSchemaDefaults(type, properties).stream().map(
        org.qubership.integration.platform.ai.plan.model.PlanProperty::key).filter(key -> !existing.contains(key))
        .collect(java.util.stream.Collectors.toSet());
  }

  public void allowDiscussion(String conversationId) { discussionTurns.add(conversationId); }
  public boolean discussionAllowed(String conversationId) { return discussionTurns.contains(conversationId); }

  public void finishTurn(String conversationId, RequirementDiscoveryDirective directive) {
    Objects.requireNonNull(conversationId, "conversationId");
    turnDirectives.put(
        conversationId,
        directive == null ? RequirementDiscoveryDirective.NONE : directive);
    if (!Boolean.TRUE.equals(captureMutationRejected.get(conversationId))) {
      captureMutationScopes.remove(conversationId);
    }
  }

  public String captureMutationScope(String conversationId) {
    return captureMutationScopes.computeIfAbsent(conversationId,
        ignored -> currentRejectedCapture(conversationId)
            .map(RequirementCaptureAttempt::mutationScope).orElseGet(() -> UUID.randomUUID().toString()));
  }

  void recordCaptureOutcome(String conversationId, String toolName, Object arguments,
      RequirementCaptureResult result) {
    recordCaptureOutcome(conversationId, toolName, arguments, result, null);
  }

  void recordCaptureOutcome(String conversationId, String toolName, Object arguments,
      RequirementCaptureResult result, RequirementCaptureInput.DraftInput resolvedCandidate) {
    recordCaptureOutcome(conversationId, toolName, arguments, result, resolvedCandidate, null);
  }

  void recordCaptureOutcome(String conversationId, String toolName, Object arguments,
      RequirementCaptureResult result, RequirementCaptureInput.DraftInput resolvedCandidate, Reference selectedAttempt) {
    recordCaptureOutcome(conversationId, toolName, arguments, result, resolvedCandidate, selectedAttempt, null);
  }

  void recordCaptureOutcome(String conversationId, String toolName, Object arguments,
      RequirementCaptureResult result, RequirementCaptureInput.DraftInput resolvedCandidate, Reference selectedAttempt,
      RequirementRepairPolicy.Strategy strategyOverride) {
    if (result.accepted() && !List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)) {
      return;
    }
    CaptureFactProtection source = factProtection(conversationId, List.of());
    boolean captureTool = List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName);
    Reference baselineRef = baselineReference(conversationId);
    var strategy = strategyOverride != null ? strategyOverride
        : result.accepted() || !captureTool ? null : recoveryStrategy(result);
    var answer = result.accepted() ? pendingClarificationAnswers.remove(conversationId) : null;
    if (!result.accepted()) {
      pendingClarificationAnswers.remove(conversationId);
    }
    saveCaptureAttempt(conversationId, new RequirementCaptureAttempt(source.sourceGeneration(), source.sourceTurnId(),
        source.authorText(), captureMutationScope(conversationId), toolName,
        arguments, result.accepted(), result.issues(), result.revision(), repairAuthority(conversationId),
        RequirementCaptureToolProvider.argumentBinding(), authorEvidenceHash(conversationId),
        result.accepted() && result.nextAction() == RequirementCaptureResult.NextAction.AWAIT_APPROVAL
            ? result.draft() : resolvedCandidate,
        baselineRef, strategy, answer,
        result.accepted() && result.nextAction() == RequirementCaptureResult.NextAction.AWAIT_APPROVAL
            ? org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
                .binding(conversationId).map(value -> value.sourceRestoration()).orElse(null) : null), selectedAttempt);
  }

  private String authorEvidenceHash(String conversationId) {
    var source = sourceInputs.get(conversationId);
    return source == null ? null : org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        AuthorSourceEvidence.packet(source));
  }

  private String repairAuthority(String conversationId) {
    return org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.binding(conversationId)
            .map(binding -> java.util.Map.of("requirements", binding.requirementRepairPaths(),
                "mappings", binding.mappingRepairPaths(), "mode", binding.mode().name()))
            .orElse(java.util.Map.of()));
  }

  Optional<RequirementCaptureAttempt> rejectedProposal(String conversationId, String toolName, Object arguments) {
    return rejectedProposal(conversationId, toolName, arguments, null);
  }

  Optional<RequirementCaptureAttempt> rejectedProposal(String conversationId, String toolName, Object arguments,
      RequirementCaptureInput.DraftInput candidate) {
    return identicalRejections(conversationId, toolName, arguments, candidate).stream().findFirst();
  }

  List<RequirementCaptureAttempt> identicalRejections(String conversationId, String toolName, Object arguments,
      RequirementCaptureInput.DraftInput candidate) {
    return identicalRejections(conversationId, toolName, arguments, candidate, true);
  }

  /** True when a projection rebuild is still the latest staged attempt. */
  boolean latestProjectionRebuild(String conversationId) {
    for (var attempt : captureAttemptsNewestFirst(conversationId)) {
      if ("projection-rebuild".equals(attempt.toolName())) {
        return true;
      }
      if (attempt.recoveryStrategy() != null) {
        return false;
      }
    }
    return false;
  }

  /** Newest saved stage. A later bookkeeping attempt with no stage does not hide it. */
  Optional<RequirementRepairPolicy.Strategy> latestRecoveryStrategy(String conversationId) {
    for (var attempt : captureAttemptsNewestFirst(conversationId)) {
      if (attempt.recoveryStrategy() != null) {
        return Optional.of(attempt.recoveryStrategy());
      }
    }
    return Optional.empty();
  }

  private List<RequirementCaptureAttempt> captureAttemptsNewestFirst(String conversationId) {
    var revisions = sessions.current(conversationId).stream()
        .flatMap(link -> artifacts.history(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream())
        .toList();
    var attempts = new ArrayList<RequirementCaptureAttempt>();
    for (int index = revisions.size() - 1; index >= 0; index--) {
      attempts.add(artifacts.payload(revisions.get(index), RequirementCaptureAttempt.class));
    }
    return attempts;
  }

  List<RequirementCaptureAttempt> identicalRejections(String conversationId, String toolName, Object arguments,
      RequirementCaptureInput.DraftInput candidate, boolean stopAtRebuild) {
    if (!currentSource(conversationId) || arguments == null
        || stopAtRebuild && latestCaptureAttempt(conversationId).filter(RequirementDraftStore::clearsPending).isPresent()) {
      return List.of();
    }
    var source = factProtection(conversationId, List.of());
    String digest = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(arguments);
    boolean initialCandidate = candidate != null && latestRevision(conversationId).isEmpty();
    return sessions.current(conversationId).stream()
        .flatMap(link -> artifacts.history(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream())
        .filter(revision -> {
          var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
          return !attempt.accepted()
              && new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of()).sameSource(source)
              && sameAcceptedBase(conversationId, attempt.acceptedDraftReference())
              && Objects.equals(attempt.repairAuthority(), repairAuthority(conversationId))
              && ((toolName.equals(attempt.toolName())
                  && digest.equals(org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(attempt.arguments())))
                  || initialCandidate && attempt.acceptedDraftReference() == null && sameCandidate(candidate, revision))
              && samePendingProtection(conversationId, revision);
        })
        .map(revision -> artifacts.payload(revision, RequirementCaptureAttempt.class))
        .toList();
  }

  private boolean sameCandidate(RequirementCaptureInput.DraftInput candidate, Revision revision) {
    if (candidate == null) return false;
    try {
      return candidate.equals(captureCandidate(revision));
    } catch (IllegalArgumentException malformedArguments) {
      return false;
    }
  }

  private boolean samePendingProtection(String conversationId, Revision attemptRevision) {
    var recorded = artifacts.payload(attemptRevision, RequirementCaptureAttempt.class);
    if (recorded.recoveryStrategy() != null) {
      return true;
    }
    var current = factProtection(conversationId, rejectedCaptureFacts(conversationId));
    var previous = LegacyCaptureProtectionAdapter.protectionBefore(artifacts, attemptRevision, recorded)
        .map(revision -> LegacyCaptureProtectionAdapter.payload(artifacts, revision))
        .filter(protection -> protection.sameSource(current)
            && Objects.equals(protection.baseIdentity(), current.baseIdentity()));
    var values = RequirementRevisionAdmission.pendingValues(current.values());
    if (previous.isEmpty()) return current.facts().isEmpty() && values.isEmpty();
    var original = previous.orElseThrow();
    var projected = RequirementRevisionAdmission.pendingValues(original.values());
    return original.facts().equals(current.facts()) && projected.equals(values)
        && !removedPendingConstraint(original.values(), projected);
  }

  private static boolean removedPendingConstraint(com.fasterxml.jackson.databind.JsonNode original,
      com.fasterxml.jackson.databind.JsonNode projected) {
    if (original == null || original.isNull()) return false;
    if (!original.isObject()) return !original.equals(projected);
    if (!projected.isObject()) return true;
    var fields = original.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      if (!field.getKey().equals("interactionId")
          && removedPendingConstraint(field.getValue(), projected.path(field.getKey()))) return true;
    }
    return false;
  }

  public String rejectedProposalHistory(String conversationId) {
    var revisions = sessions.current(conversationId).stream()
        .flatMap(link -> artifacts.history(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream())
        .toList();
    int start = 0;
    for (int index = revisions.size() - 1; index >= 0; index--) {
      var attempt = artifacts.payload(revisions.get(index), RequirementCaptureAttempt.class);
      if ("projection-rebuild".equals(attempt.toolName())) {
        start = index + 1;
        break;
      }
    }
    var source = factProtection(conversationId, List.of());
    var mapper = new ObjectMapper();
    var attempts = revisions.subList(start, revisions.size()).stream()
        .map(revision -> artifacts.payload(revision, RequirementCaptureAttempt.class))
        .filter(attempt -> !attempt.accepted())
        .filter(attempt -> new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(),
            attempt.authorText(), List.of()).sameSource(source))
        .map(attempt -> withoutCandidateBody(mapper.valueToTree(attempt)))
        .toList();
    return attempts.isEmpty() ? "" : org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory
        .projectValues(attempts, mapper,
            org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.CHAR_BUDGET).toString();
  }

  /** A rejected draft is not a source for the next capture. */
  private static com.fasterxml.jackson.databind.JsonNode withoutCandidateBody(
      com.fasterxml.jackson.databind.JsonNode node) {
    if (!(node instanceof ObjectNode object)) {
      return node;
    }
    object.remove("arguments");
    object.remove("resolvedCandidate");
    return object;
  }

  private Optional<RequirementCaptureAttempt> latestCaptureAttempt(String conversationId) {
    return sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT))
        .map(revision -> artifacts.payload(revision, RequirementCaptureAttempt.class));
  }

  private Optional<RequirementCaptureAttempt> currentRejectedCapture(String conversationId) {
    CaptureFactProtection source = factProtection(conversationId, List.of());
    return latestCaptureAttempt(conversationId).filter(attempt -> !attempt.accepted())
        .filter(attempt -> new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(),
            attempt.authorText(), List.of()).sameSource(source))
        .filter(attempt -> sameAcceptedBase(conversationId, attempt.acceptedDraftReference()));
  }

  private boolean sameAcceptedBase(String conversationId, Reference reference) {
    var current = latestRevision(conversationId).orElse(null);
    if (Objects.equals(reference, current == null ? null : current.reference())) return true;
    if (reference == null || current == null) return false;
    var authored = artifacts.payload(current, RequirementDraft.class).authoredDraft();
    return authored != null && artifacts.get(current.compilationId(), reference)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class).authoredDraft())
        .filter(authored::equals).isPresent();
  }

  public Optional<Revision> rejectedCaptureRevision(String conversationId) {
    var source = factProtection(conversationId, List.of());
    var session = sessions.current(conversationId);
    if (session.isEmpty()) {
      return Optional.empty();
    }
    var history = artifacts.history(session.orElseThrow().compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    for (int index = history.size() - 1; index >= 0; index--) {
      var revision = history.get(index);
      var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
      if (clearsPending(attempt)) {
        return Optional.empty();
      }
      if (!attempt.accepted() && List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())
          && new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of())
              .sameSource(source)
          && sameAcceptedBase(conversationId, attempt.acceptedDraftReference())) {
        return Optional.of(revision);
      }
    }
    return Optional.empty();
  }

  /**
   * Strategy, conflict participants, and editable paths for the next capture. This is display
   * guidance. It does not return the rejected draft.
   */
  public Optional<RecoveryGuidance> recoveryGuidance(String conversationId) {
    var session = sessions.current(conversationId);
    if (session.isEmpty()) {
      return Optional.empty();
    }
    var history = artifacts.history(session.orElseThrow().compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    RequirementRepairPolicy.Strategy chosen = null;
    RequirementCaptureAttempt display = null;
    for (int index = history.size() - 1; index >= 0; index--) {
      var attempt = artifacts.payload(history.get(index), RequirementCaptureAttempt.class);
      if (chosen == null && attempt.recoveryStrategy() != null) {
        chosen = attempt.recoveryStrategy();
      }
      if (display == null && !attempt.accepted()
          && List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())) {
        display = attempt;
      }
      if (chosen != null && display != null) {
        break;
      }
    }
    if (chosen == null) {
      return Optional.empty();
    }
    var participants = new java.util.TreeSet<String>();
    var paths = new java.util.TreeSet<String>();
    if (display != null) {
      for (var issue : display.issues()) {
        if ("UNCHANGED_PROPOSAL".equals(issue.code()) || "REPAIR_NO_PROGRESS".equals(issue.code())) {
          continue;
        }
        if (issue.path() != null && !issue.path().isBlank() && !"/".equals(issue.path())) {
          paths.add(issue.path());
        }
        if (issue.entityId() != null && !issue.entityId().isBlank()) {
          participants.add(issue.entityId());
        }
        if (issue.details() != null && issue.details().relatedEntityIds() != null) {
          issue.details().relatedEntityIds().stream()
              .filter(id -> id != null && !id.isBlank())
              .forEach(participants::add);
        }
      }
    }
    var stage = org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.Stage
        .valueOf(chosen.name());
    return Optional.of(new RecoveryGuidance(stage, List.copyOf(participants), List.copyOf(paths)));
  }

  /** Display fields for the next requirement capture. The rejected draft is not included. */
  public record RecoveryGuidance(
      org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.Stage strategy,
      List<String> participants, List<String> editablePaths) {}

  record PendingInitialUpdate(RequirementCaptureEditor.EditResult edit, Reference attempt) {}

  PendingInitialUpdate pendingInitialUpdate(String conversationId,
      RequirementCaptureInput.DraftUpdate changes) {
    var pending = pendingProposal(conversationId);
    var session = sessions.current(conversationId);
    var revision = session.isEmpty() || pending.isEmpty() ? Optional.<Revision>empty()
        : artifacts.get(session.orElseThrow().compilationId(), pending.orElseThrow().attempt())
            .filter(value -> value.kind() == Kind.REQUIREMENT_CAPTURE_ATTEMPT
                && "requirement-capture-tools".equals(value.producerId()));
    var source = conversations == null ? null : conversations.archive().read(conversationId);
    var attempt = revision.map(value -> artifacts.payload(value, RequirementCaptureAttempt.class));
    if (!currentSource(conversationId) || latestRevision(conversationId).isPresent()
        || pending.isEmpty() || !Objects.equals(pending.orElseThrow().token(), changes.pendingProposalToken())
        || revision.isEmpty() || revision.orElseThrow().kind() != Kind.REQUIREMENT_CAPTURE_ATTEMPT
        || !"requirement-capture-tools".equals(revision.orElseThrow().producerId())
        || attempt.orElseThrow().accepted() || attempt.orElseThrow().acceptedDraftReference() != null
        || source == null || !Objects.equals(source.generation(), attempt.orElseThrow().sourceGeneration())
        || !Objects.equals(org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source),
            attempt.orElseThrow().sourceTurnId())
        || !Objects.equals(repairAuthority(conversationId), attempt.orElseThrow().repairAuthority())
        || attempt.orElseThrow().authorEvidenceHash() == null
        || !Objects.equals(attempt.orElseThrow().authorEvidenceHash(), authorEvidenceHash(conversationId))
        || !Objects.equals(attempt.orElseThrow().authorEvidenceHash(),
            org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(AuthorSourceEvidence.packet(source)))) {
      return new PendingInitialUpdate(new RequirementCaptureEditor.EditResult(null, List.of(new RequirementCaptureEditor.Issue(
          "PENDING_PROPOSAL_NOT_CURRENT", "/changes/pendingProposalToken", null,
          "Use the exact current rejected initial pendingProposal.token from readRequirementDraft under the same author source and repair authority.")), false), null);
    }
    var edited = RequirementCaptureEditor.apply(pending.orElseThrow().draft(), changes);
    if (edited.candidate() == null) return new PendingInitialUpdate(
        new RequirementCaptureEditor.EditResult(null, edited.issues(), false), revision.orElseThrow().reference());
    var issues = new ArrayList<>(edited.issues());
    issues.addAll(RequirementCaptureEditor.initialize(edited.candidate()).issues());
    return new PendingInitialUpdate(new RequirementCaptureEditor.EditResult(edited.candidate(), issues.stream().distinct().toList(),
        edited.changed(), edited.candidate()), revision.orElseThrow().reference());
  }

  RequirementCaptureInput.DraftInput captureCandidate(Revision revision) {
    var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
    if (attempt.resolvedCandidate() != null) return attempt.resolvedCandidate();
    var mapper = new ObjectMapper();
    if ("captureRequirementDraft".equals(attempt.toolName())) {
      return mapper.convertValue(attempt.arguments(), RequirementCaptureInput.DraftInput.class);
    }
    if (!"updateRequirementDraft".equals(attempt.toolName())) return null;
    var original = attempt.acceptedDraftReference() == null ? null
        : artifacts.get(revision.compilationId(), attempt.acceptedDraftReference())
            .map(value -> artifacts.payload(value, RequirementDraft.class).authoredDraft()).orElse(null);
    return RequirementCaptureEditor.apply(original,
        mapper.convertValue(attempt.arguments(), RequirementCaptureInput.DraftUpdate.class)).candidate();
  }

  Optional<RequirementCaptureResult.PendingProposal> pendingProposal(String conversationId) {
    var session = sessions.current(conversationId);
    if (session.isEmpty()) return Optional.empty();
    String compilationId = session.orElseThrow().compilationId();
    var history = artifacts.history(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    var authority = writeAuthority(conversationId);
    var source = conversations == null ? factProtection(conversationId, List.of())
        : new CaptureFactProtection(authority.sourceGeneration(), authority.sourceTurnId(), null, List.of());
    Reference base = authority.base();
    for (int i = history.size() - 1; i >= 0; i--) {
      var revision = history.get(i);
      var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
      if (clearsPending(attempt)) return Optional.empty();
      if (!List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())) continue;
      if (attempt.accepted() || !Objects.equals(base, attempt.acceptedDraftReference())
          || !new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of())
              .sameSource(source)) return Optional.empty();
      RequirementCaptureInput.DraftInput candidate;
      try {
        candidate = captureCandidate(revision);
      } catch (IllegalArgumentException malformedArguments) {
        return Optional.empty();
      }
      if (candidate == null) continue;
      return Optional.of(new RequirementCaptureResult.PendingProposal(
          revision.reference(), attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.acceptedDraftReference(),
          attempt.toolName(), candidate, attempt.issues(), pendingToken(revision, attempt)));
    }
    return Optional.empty();
  }

  private void saveCaptureAttempt(String conversationId, RequirementCaptureAttempt attempt) {
    saveCaptureAttempt(conversationId, attempt, null);
  }

  private void saveCaptureAttempt(String conversationId, RequirementCaptureAttempt attempt, Reference selectedAttempt) {
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    var inputs = new ArrayList<Reference>();
    if (attempt.acceptedDraftReference() != null) inputs.add(attempt.acceptedDraftReference());
    if (selectedAttempt != null) inputs.add(selectedAttempt);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1",
        "requirement-capture-tools", "1", attempt, inputs,
        previous.map(Revision::artifactId).orElse(null)));
  }

  /** Values that make one pending-proposal token. A change to any value invalidates the token. */
  public record PendingProposalBinding(Reference attempt, String sourceHash, Reference acceptedBase,
      Reference semanticBaseline) {}

  private static String pendingToken(Revision revision, RequirementCaptureAttempt attempt) {
    return org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        new PendingProposalBinding(revision.reference(), attempt.authorEvidenceHash(),
            attempt.acceptedDraftReference(), attempt.semanticBaselineReference()));
  }

  private static boolean clearsPending(RequirementCaptureAttempt attempt) {
    return "reset".equals(attempt.toolName()) || "projection-rebuild".equals(attempt.toolName());
  }

  private static RequirementRepairPolicy.Strategy recoveryStrategy(RequirementCaptureResult result) {
    return result.nextAction() == RequirementCaptureResult.NextAction.PROJECTION_REBUILD
        ? RequirementRepairPolicy.Strategy.PROJECTION_REBUILD
        : RequirementRepairPolicy.Strategy.FOCUSED_REPAIR;
  }

  public record RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
      String mutationScope, String toolName, Object arguments, boolean accepted,
      List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
      RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      RequirementCaptureInput.DraftInput resolvedCandidate,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      Reference semanticBaselineReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      RequirementRepairPolicy.Strategy recoveryStrategy,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      @com.fasterxml.jackson.annotation.JsonProperty(required = false)
      org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Answer
          clarificationAnswer,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      ProductRequirementBriefTool.SourceReview sourceRestoration) {
    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
        RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash,
        RequirementCaptureInput.DraftInput resolvedCandidate, Reference semanticBaselineReference,
        RequirementRepairPolicy.Strategy recoveryStrategy,
        org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Answer clarificationAnswer) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, argumentBinding, authorEvidenceHash, resolvedCandidate,
          semanticBaselineReference, recoveryStrategy, clarificationAnswer, null);
    }
    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
        RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash,
        RequirementCaptureInput.DraftInput resolvedCandidate, Reference semanticBaselineReference,
        RequirementRepairPolicy.Strategy recoveryStrategy) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, argumentBinding, authorEvidenceHash, resolvedCandidate,
          semanticBaselineReference, recoveryStrategy, null);
    }

    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
        RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash,
        RequirementCaptureInput.DraftInput resolvedCandidate) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, argumentBinding, authorEvidenceHash, resolvedCandidate,
          null, null);
    }
    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
        RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, argumentBinding, authorEvidenceHash, null);
    }
    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
        RequirementCaptureToolProvider.ArgumentBinding argumentBinding) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, argumentBinding, null);
    }
    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, repairAuthority, null);
    }

    public RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
        String mutationScope, String toolName, Object arguments, boolean accepted,
        List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference) {
      this(sourceGeneration, sourceTurnId, authorText, mutationScope, toolName, arguments, accepted,
          issues, acceptedDraftReference, "");
    }

    public RequirementCaptureAttempt {
      issues = List.copyOf(issues);
    }
  }

  public void recordCaptureAttempt(String conversationId, boolean accepted) {
    captureMutationRejected.put(conversationId, !accepted);
  }

  public RequirementDiscoveryDirective turnDirective(String conversationId) {
    return turnDirectives.getOrDefault(conversationId, RequirementDiscoveryDirective.NONE);
  }

  public void markCaptured(String conversationId) {
    capturedThisTurn.put(conversationId, true);
  }

  public boolean wasCapturedThisTurn(String conversationId) {
    return Boolean.TRUE.equals(capturedThisTurn.get(conversationId))
        || capturedFromSource(conversationId, sourceInputs.get(conversationId));
  }

  public void recordCaptureRejection(String conversationId, String message) {
    if (conversationId == null || conversationId.isBlank() || message == null || message.isBlank()) {
      return;
    }
    lastCaptureRejection.put(conversationId, message);
  }

  public Optional<String> lastCaptureRejection(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(lastCaptureRejection.get(conversationId)).or(() ->
        currentRejectedCapture(conversationId).map(attempt -> attempt.issues().stream()
            .map(issue -> issue.code() + " " + issue.path() + ": " + issue.message())
            .collect(java.util.stream.Collectors.joining("\n"))));
  }

  /** Clears {@code awaitingPlanContinuation} when the user continues after import. */
  public boolean clearAwaitingPlanContinuation(String conversationId) {
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null || !current.awaitingPlanContinuation()) {
      return false;
    }
    put(conversationId, current.withAwaitingPlanContinuation(false));
    return true;
  }

  public void applyImportResult(String conversationId, ResolvedCatalogBinding binding) {
    RequirementDraft current = get(conversationId).orElse(null);
    String interactionId = current == null ? null : current.apiHubCandidateInteractionId();
    applyImportResult(conversationId, interactionId, binding);
  }

  public void applyImportResult(
      String conversationId, String interactionId, ResolvedCatalogBinding binding) {
    applyImportResult(conversationId, writeAuthority(conversationId), interactionId, binding);
  }

  public boolean applyImportResult(String conversationId, WriteAuthority expected,
      String interactionId, ResolvedCatalogBinding binding) {
    return withWriteAuthority(conversationId, () -> {
      if (!isCurrentWrite(conversationId, expected)) {
        return false;
      }
      applyCurrentImportResult(conversationId, interactionId, binding);
      return true;
    });
  }

  private void applyCurrentImportResult(String conversationId, String interactionId, ResolvedCatalogBinding binding) {
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null || binding == null) {
      return;
    }
    String owner = resolveImportOwner(current, interactionId);
    if (owner == null) {
      return;
    }
    RequirementFlow.Interaction target = current.flow().interaction(owner).orElse(null);
    if (target == null) {
      return;
    }
    CatalogBindingHint hint = hintFromImport(target, binding);
    put(conversationId, current.withBoundInteraction(target.interactionId(), hint));
  }

  private static String resolveImportOwner(RequirementDraft current, String interactionId) {
    if (interactionId != null && !interactionId.isBlank()) {
      return interactionId.trim();
    }
    if (current.apiHubCandidateInteractionId() != null) {
      return current.apiHubCandidateInteractionId();
    }
    List<String> unbound =
        current.flow().interactions().stream()
            .filter(
                interaction ->
                    RequirementFlowValidator.requiresCatalogBinding(interaction, current.facts()))
            .map(RequirementFlow.Interaction::interactionId)
            .filter(
                id ->
                    current.catalogBindings().stream()
                        .noneMatch(hint -> id.equals(hint.interactionId())))
            .toList();
    if (unbound.size() == 1) {
      return unbound.getFirst();
    }
    if (current.flow().interactions().size() == 1) {
      return current.flow().interactions().getFirst().interactionId();
    }
    return null;
  }

  private static CatalogBindingHint hintFromImport(
      RequirementFlow.Interaction interaction, ResolvedCatalogBinding binding) {
    CatalogMatch match =
        new CatalogMatch(
            binding.systemId(),
            binding.specificationGroupId(),
            binding.specificationId(),
            binding.integrationOperationId(),
            "",
            "",
            null,
            null,
            interaction.operation(),
            "import:" + binding.integrationOperationId());
    return CatalogBindingHint.from(interaction, match, "catalog", Instant.now());
  }

  /**
   * Clears the pending API Hub candidate after import failure while keeping durable
   * {@code importIntent} for soft re-gather.
   */
  public boolean recordImportFailure(String conversationId, WriteAuthority expected) {
    return withWriteAuthority(conversationId, () -> {
      if (!isCurrentWrite(conversationId, expected)) return false;
      recordImportFailure(conversationId);
      return true;
    });
  }

  public void recordImportFailure(String conversationId) {
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null) {
      return;
    }
    put(conversationId, current.clearApiHubCandidate().withImportIntent(true));
  }

  public void rememberPreferredSystemType(String conversationId, String systemType) {
    withWriteAuthority(conversationId, () -> {
      beginTurn(conversationId);
      RequirementDraft current = get(conversationId).orElse(null);
      if (current == null) {
        put(conversationId, new RequirementDraft(false, "").withPreferredSystemType(systemType));
        return null;
      }
      requireCurrentSource(conversationId);
      appendDraft(conversationId, current.withPreferredSystemType(systemType), true);
      return null;
    });
  }

  /** Ensures durable import intent is set when cold IMPORT soft-advances into gather. */
  public void ensureImportIntent(String conversationId) {
    ensureImportIntent(conversationId, null);
  }

  /**
   * Ensures durable import intent and seeds {@code assembledText} from the user message when the
   * draft is missing or blank so package ids stay available for API Hub tool group injection.
   */
  public void ensureImportIntent(String conversationId, String seedText) {
    String seed = seedText != null ? seedText.trim() : "";
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null) {
      put(conversationId, new RequirementDraft(false, seed).withImportIntent(true));
      return;
    }
    RequirementDraft next = current;
    if (!current.importIntent()) {
      next = next.withImportIntent(true);
    }
    if (current.assembledText().isBlank() && !seed.isBlank()) {
      next =
          new RequirementDraft(
              next.complete(),
              seed,
              next.decision(),
              next.openQuestions(),
              next.sourceSkillId(),
              next.sourceSkillVersion(),
              next.sourceSkillHash(),
              next.apiHubCandidate(),
              next.awaitingPlanContinuation(),
              next.facts(),
              true,
              next.apiHubCandidateInteractionId(),
              next.idsRequested(),
              next.flow(),
              next.catalogBindings(),
              next.preferredSystemType(),
              next.authoredDraft());
    }
    if (next != current) {
      put(conversationId, next);
    }
  }

  private static MemoryRuntime memoryRuntime() {
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    Clock clock = Clock.systemUTC();
    return new MemoryRuntime(
        new CompilationArtifacts(blobStore, objectMapper, clock),
        new CompilationSessions(blobStore, objectMapper, clock));
  }

  private record MemoryRuntime(
      CompilationArtifacts artifacts, CompilationSessions sessions) {}
}
