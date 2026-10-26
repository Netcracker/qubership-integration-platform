package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.ArtifactDecision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Decision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.DecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
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
  private final ConcurrentHashMap<String, org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document>
      sourceInputs = new ConcurrentHashMap<>();


  @Inject
  public RequirementDraftStore(
      CompilationArtifacts artifacts, CompilationSessions sessions,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.conversations = conversations;
    this.policyChoices = new org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection(artifacts, sessions, conversations);
  }

  public RequirementDraftStore(CompilationArtifacts artifacts, CompilationSessions sessions) {
    this(artifacts, sessions, null);
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

  /** Admission issues and the repair strategy that produced them. */
  record CaptureAdmission(List<RequirementCaptureEditor.Issue> issues, RequirementRepairPolicy.Strategy strategy) {}

  List<RequirementCaptureEditor.Issue> admitCapture(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths) {
    return admitCaptureDecision(conversationId, expectedBase, draft, changes, mappingPaths).issues();
  }

  CaptureAdmission admitCaptureDecision(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths) {
    return withWriteAuthority(conversationId,
        () -> admitCaptureLocked(conversationId, expectedBase, draft, changes, mappingPaths));
  }

  private CaptureAdmission admitCaptureLocked(String conversationId, Reference expectedBase,
      RequirementDraft draft, RequirementCaptureInput.DraftUpdate changes,
      java.util.Map<String, java.util.Set<String>> mappingPaths) {
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
    issues.addAll(RequirementFailureConsistency.unauthorizedModeChanges(base, draft.authoredDraft(),
        current.map(revision -> artifacts.payload(revision, RequirementDraft.class).catalogBindings()).orElse(List.of()),
        authorPaths, repairPaths));
    var admission = RequirementRevisionAdmission.admissionDecision(base, draft.authoredDraft(),
        rejectedCaptureFacts(conversationId), rejectedCaptureValues.get(conversationId), authorPaths, repairPaths, mappingPaths,
        fact -> canResolvePolicyChoice(conversationId, fact), baseline);
    issues.addAll(admission.issues());
    var strategy = admission.strategy();
    var facts = draft.authoredDraft() == null || draft.authoredDraft().facts() == null
        ? List.<RequirementCaptureInput.FactInput>of() : draft.authoredDraft().facts();
    var amendments = changes == null || changes.amendments() == null
        ? List.<RequirementCaptureInput.AmendmentInput>of() : changes.amendments();
    var packet = sourcePacket(conversationId);
    // Claim text and polarity stay protected. Representation fields are not part of the claim.
    // Initial capture has no saved draft. Treat the candidate as that draft so a quote-less fact stays unclaimed.
    var accepted = base == null ? facts : base.facts();
    issues.addAll(RequirementClaimEvidenceValidator.validate(baseline, accepted, facts, packet, amendments));
    if (!issues.isEmpty()) {
      return new CaptureAdmission(List.copyOf(issues), strategy);
    }
    if (current.isEmpty() || !draft.equals(artifacts.payload(current.orElseThrow(), RequirementDraft.class))) {
      appendDraft(conversationId, draft, true);
      recordSemanticBaseline(conversationId, baseline, facts, packet, amendments);
    }
    clearRejectedCaptureFacts(conversationId);
    return new CaptureAdmission(List.of(), strategy);
  }

  private static CaptureAdmission focused(List<RequirementCaptureEditor.Issue> issues) {
    return new CaptureAdmission(List.copyOf(issues), RequirementRepairPolicy.Strategy.FOCUSED_REPAIR);
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
    String compilationId = sessions.active(conversationId);
    var prior = artifacts.latest(compilationId, Kind.REQUIREMENT_SEMANTIC_BASELINE);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_SEMANTIC_BASELINE, "1",
        "requirement-draft-store", "1", baseline, List.of(), prior.map(Revision::artifactId).orElse(null)));
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
    return RequirementRevisionAdmission.restoreProtectedSnapshot(base, candidate, rejectedCaptureFacts(conversationId),
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
    clarificationBases.remove(conversationId);
    turnDirectives.remove(conversationId);
    lastCaptureRejection.remove(conversationId);
    captureMutationScopes.remove(conversationId);
    captureMutationRejected.remove(conversationId);
    currentAuthorText.remove(conversationId);
    rejectedCaptureFacts.remove(conversationId);
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
    clarificationBases.remove(conversationId);
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
      CaptureFactProtection scope = factProtection(conversationId, List.of());
      latestFactProtection(conversationId)
          .filter(previous -> previous.sameSource(scope) && Objects.equals(previous.baseIdentity(), scope.baseIdentity()))
          .ifPresent(previous -> {
            rejectedCaptureFacts.put(conversationId, previous.facts());
            if (previous.values() != null) rejectedCaptureValues.put(conversationId,
                RequirementRevisionAdmission.pendingValues(previous.values()));
          });
    }
    revalidateRejectedReferences(conversationId);
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

  void rememberRejectedCapture(String conversationId, RequirementCaptureInput.DraftInput input,
      List<RequirementCaptureEditor.Issue> issues) {
    rememberRejectedCapture(conversationId, input, issues, List.of());
  }

  void revalidateRejectedModeCorrection(String conversationId, RequirementCaptureInput.DraftInput candidate,
      List<CatalogBindingHint> bindings) {
    if (!currentSource(conversationId) || conversations == null || authorEvidenceHash(conversationId) == null) return;
    var source = factProtection(conversationId, List.of());
    if (!Objects.equals(authorEvidenceHash(conversationId), org.qubership.integration.platform.ai.compiler.capture.CaptureProposal
        .digest(AuthorSourceEvidence.packet(conversations.archive().read(conversationId))))) return;
    var session = sessions.current(conversationId);
    if (session.isEmpty()) return;
    for (var revision : artifacts.history(session.orElseThrow().compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT)) {
      if (!"requirement-capture-tools".equals(revision.producerId())) continue;
      var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
      if (attempt.accepted() || !List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())
          || !new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of()).sameSource(source)
          || !Objects.equals(attempt.authorEvidenceHash(), authorEvidenceHash(conversationId))
          || !Objects.equals(attempt.acceptedDraftReference(), latestRevision(conversationId).map(Revision::reference).orElse(null))
          || !Objects.equals(attempt.repairAuthority(), repairAuthority(conversationId))) continue;
      try {
        var original = captureCandidate(revision);
        var findings = RequirementFailureConsistency.issues(original, bindings);
        if (!findings.isEmpty() && findings.stream().allMatch(finding -> attempt.issues().stream().anyMatch(issue ->
            finding.code().equals(issue.code()) && finding.path().equals(issue.path()) && Objects.equals(finding.entityId(), issue.entityId())))
            && RequirementFailureConsistency.correctsOnlyModes(original, candidate, findings)) {
          rememberRejectedCapture(conversationId, original, findings, bindings);
          return;
        }
      } catch (IllegalArgumentException malformed) {
        // An unreadable historical candidate supplies no correction authority.
      }
    }
  }

  void rememberRejectedCapture(String conversationId, RequirementCaptureInput.DraftInput input,
      List<RequirementCaptureEditor.Issue> issues, List<CatalogBindingHint> bindings) {
    var base = get(conversationId).map(RequirementDraft::authoredDraft).orElse(null);
    var retained = RequirementRevisionAdmission.retainedAdditions(base, input, issues);
    var previousFacts = rejectedCaptureFacts(conversationId);
    var candidateIds = input.facts() == null ? java.util.Set.<String>of() : input.facts().stream()
        .filter(Objects::nonNull).map(RequirementCaptureInput.FactInput::sourceFactId)
        .collect(java.util.stream.Collectors.toSet());
    boolean retainsIdentities = previousFacts.stream()
        .filter(fact -> RequirementCaptureEditor.validFactId(fact.sourceFactId()))
        .allMatch(fact -> candidateIds.contains(fact.sourceFactId()));
    var facts = retainsIdentities ? RequirementRevisionAdmission.accumulate(previousFacts, retained.facts()) : previousFacts;
    var values = RequirementRevisionAdmission.accumulateValues(rejectedCaptureValues.get(conversationId),
        retained.values());
    // A later native check can disprove a value retained before that check was reachable.
    for (var finding : RequirementFailureConsistency.issues(input, bindings)) {
      if (!issues.contains(finding) || !facts.containsAll(input.facts())) continue;
      String id = finding.entityId();
      if (base != null && base.flow().interactions().stream().anyMatch(value -> id.equals(value.interactionId()))) continue;
      var original = input.flow().interactions().stream().filter(value -> id.equals(value.interactionId()))
          .findFirst().orElseThrow();
      var protectedMode = values.path("interactions").path(id).path("failureMode");
      if (original.failureMode() != null && protectedMode.asText().equals(original.failureMode().name())) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) values.path("interactions").path(id))
            .put("failureMode", org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode.INLINE_RESPONSE.name());
      }
    }
    values = RequirementRevisionAdmission.pendingValues(values);
    if (!facts.equals(rejectedCaptureFacts(conversationId)) || !values.equals(rejectedCaptureValues.get(conversationId))) {
      saveFactProtection(conversationId, facts, values);
      rejectedCaptureFacts.put(conversationId, facts);
      rejectedCaptureValues.put(conversationId, values);
    }
  }

  void rememberRejectedCaptureFacts(
      String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    var accumulated = RequirementRevisionAdmission.accumulate(rejectedCaptureFacts(conversationId), facts);
    if (!accumulated.equals(rejectedCaptureFacts(conversationId))) {
      saveFactProtection(conversationId, accumulated);
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
    if (latestFactProtection(conversationId).filter(protection -> !protection.facts().isEmpty() || protection.values() != null && !protection.values().isEmpty()).isPresent()) {
      saveFactProtection(conversationId, List.of());
    }
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

  private Optional<CaptureFactProtection> latestFactProtection(String conversationId) {
    return sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_CAPTURE_PROTECTION))
        .map(revision -> artifacts.payload(revision, CaptureFactProtection.class));
  }

  private void saveFactProtection(String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    saveFactProtection(conversationId, facts, rejectedCaptureValues.get(conversationId));
  }

  private void saveFactProtection(String conversationId, List<RequirementCaptureInput.FactInput> facts,
      com.fasterxml.jackson.databind.JsonNode values) {
    saveFactProtection(conversationId, facts, values, List.of());
  }

  private void saveFactProtection(String conversationId, List<RequirementCaptureInput.FactInput> facts,
      com.fasterxml.jackson.databind.JsonNode values, List<Reference> evidence) {
    var scope = factProtection(conversationId, facts);
    var protection = new CaptureFactProtection(scope.sourceGeneration(), scope.sourceTurnId(), scope.authorText(),
        facts, values, scope.baseIdentity());
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION, "1",
        "requirement-capture-protection", "1", protection, evidence,
        previous.map(Revision::artifactId).orElse(null)));
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

  private final ConcurrentHashMap<String, Reference> clarificationBases = new ConcurrentHashMap<>();

  public void associateClarification(String conversationId) {
    var reference = latestRevision(conversationId).map(Revision::reference).orElse(null);
    if (reference == null) return;
    if (conversations == null) {
      clarificationBases.putIfAbsent(conversationId, reference);
      return;
    }
    var source = conversations.archive().read(conversationId);
    String inputId = org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    if (inputId != null) conversations.archive().associateClarification(conversationId, source.generation(), inputId, reference);
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
    Reference baseRef = clarificationBases.get(conversationId);
    if (source != null) {
      var latest = source.activeTurns().stream().filter(turn -> inputIds.contains(turn.id())).reduce((a, b) -> b);
      if (latest.isPresent() && latest.get().metadata() != null && latest.get().metadata().has("clarificationDraft")) {
        baseRef = new ObjectMapper().convertValue(latest.get().metadata().get("clarificationDraft"), Reference.class);
      }
    }
    var base = baseRef == null ? null : artifacts.get(sessions.active(conversationId), baseRef)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class)).orElse(null);
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
    for (var question : draft.openQuestions()) {
      if (question == null) continue;
      var evidence = question.evidence();
      String path = "/draft/openQuestions/" + question.questionId();
      boolean prior = base != null && base.authoredDraft() != null && base.authoredDraft().openQuestions().stream()
          .anyMatch(old -> old.questionId().equals(question.questionId()) || old.evidence() != null && evidence != null
              && old.evidence().interactionId().equals(evidence.interactionId())
              && old.evidence().propertyKey().equals(evidence.propertyKey()));
      var findings = new java.util.ArrayList<String>();
      if (evidence != null) {
        if (!question.interactionIds().contains(evidence.interactionId()) && facts.stream().noneMatch(fact ->
            fact.elementIntent() != null && evidence.interactionId().equals(fact.elementIntent().elementRef())
                && question.interactionIds().contains(fact.elementIntent().ownerInteractionId()))) {
          findings.add("Question evidence must target the question's interaction.");
        }
        org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission.validate(
            evidence, facts, current.flow(), current.catalogBindings(), inputIds, questionDefaults(draft, evidence.interactionId()), prior || evidence.kind()
                != org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.AMBIGUOUS_ANSWER,
            findings);
      } else if (source != null || prior) {
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
      if (prior && (evidence == null || evidence.kind()
          != org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.AMBIGUOUS_ANSWER)) {
        findings.add("The admitted answer has not resolved this question. Apply it to its target field;"
            + " use AMBIGUOUS_ANSWER only when the reviewed latest answer leaves that field unresolved.");
      }
      if (!findings.isEmpty()) issues.add(new RequirementCaptureEditor.Issue(
          prior ? "UNAPPLIED_AUTHOR_ANSWER" : "AUTHOR_QUESTION_NOT_SUPPORTED", path, question.questionId(),
          String.join(" ", findings) + " Source turn IDs: " + inputIds));
    }
    if (base != null && base.authoredDraft() != null) {
      for (var question : base.authoredDraft().openQuestions()) {
        var evidence = question.evidence();
        if (evidence == null || draft.openQuestions().stream().anyMatch(open -> open.questionId().equals(question.questionId()))) continue;
        boolean resolverWork = RequirementCaptureProjection.pending(draft, current.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(current)).stream().anyMatch(work ->
                evidence.interactionId().equals(work.interactionId()) && work.code().endsWith("LOOKUP_REQUIRED"));
        if (resolverWork) continue;
        var values = facts.stream().filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
            .filter(fact -> fact.targetsInteraction(evidence.interactionId())
            || fact.elementIntent() != null && evidence.interactionId().equals(fact.elementIntent().elementRef()))
            .flatMap(fact -> org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission
                .suppliedValues(fact, evidence.propertyKey()).stream()).toList();
        boolean resolvedPolicy = evidence.kind()
            != org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.POLICY_CHOICE
            || facts.stream().anyMatch(fact -> fact.targetsInteraction(evidence.interactionId()) && fact.policyConflict() != null
                && evidence.propertyKey().equals(fact.policyConflict().propertyKey()) && fact.policyConflict().decision() != null);
        if ((values.isEmpty() && !questionDefaults(draft, evidence.interactionId()).contains(evidence.propertyKey())) || !resolvedPolicy) {
          issues.add(new RequirementCaptureEditor.Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions/" + question.questionId(),
              question.questionId(), "Removing a question is insufficient: apply the saved answer to "
                  + evidence.interactionId() + "." + evidence.propertyKey() + " before continuing."));
        }
      }
    }
    return List.copyOf(issues);
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
    if (result.accepted() && !List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)) {
      return;
    }
    CaptureFactProtection source = factProtection(conversationId, rejectedCaptureFacts(conversationId));
    if (!result.accepted() && List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)) {
      var values = RequirementRevisionAdmission.pendingValues(source.values());
      latestFactProtection(conversationId)
          .filter(previous -> previous.sameSource(source) && Objects.equals(previous.baseIdentity(), source.baseIdentity()))
          .filter(previous -> !previous.facts().equals(source.facts()) || !Objects.equals(previous.values(), values))
          .ifPresent(previous -> saveFactProtection(conversationId, source.facts(), values));
    }
    saveCaptureAttempt(conversationId, new RequirementCaptureAttempt(source.sourceGeneration(), source.sourceTurnId(),
        source.authorText(), captureMutationScope(conversationId), toolName,
        arguments, result.accepted(), result.issues(), result.revision(), repairAuthority(conversationId),
        RequirementCaptureToolProvider.argumentBinding(), authorEvidenceHash(conversationId), resolvedCandidate), selectedAttempt);
    if (!result.accepted() && List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)) {
      revalidateRejectedReferences(conversationId);
    }
  }

  private void revalidateRejectedReferences(String conversationId) {
    if (!currentSource(conversationId)) return;
    String sourceHash = authorEvidenceHash(conversationId);
    if (sourceHash == null || conversations == null || !sourceHash.equals(
        org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
            AuthorSourceEvidence.packet(conversations.archive().read(conversationId))))) return;
    var scope = factProtection(conversationId, rejectedCaptureFacts(conversationId));
    if (scope.sourceGeneration() == null || scope.sourceTurnId() == null) return;
    var protection = latestFactProtection(conversationId);
    if (protection.isEmpty() || !protection.orElseThrow().sameSource(scope)
        || !Objects.equals(protection.orElseThrow().baseIdentity(), scope.baseIdentity())
        || !protection.orElseThrow().facts().equals(scope.facts())) return;
    var current = latestRevision(conversationId);
    var baseRef = current.map(Revision::reference).orElse(null);
    var base = current.map(value -> artifacts.payload(value, RequirementDraft.class).authoredDraft()).orElse(null);
    var session = sessions.current(conversationId);
    if (session.isEmpty()) return;
    var revisions = artifacts.history(session.orElseThrow().compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream()
        .filter(value -> "requirement-capture-tools".equals(value.producerId()))
        .filter(value -> {
          var attempt = artifacts.payload(value, RequirementCaptureAttempt.class);
          return !attempt.accepted() && List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())
              && new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of()).sameSource(scope)
              && Objects.equals(attempt.authorEvidenceHash(), sourceHash)
              && Objects.equals(attempt.acceptedDraftReference(), baseRef)
              && Objects.equals(attempt.repairAuthority(), repairAuthority(conversationId));
        }).sorted(java.util.Comparator.comparingLong(Revision::sequence)).toList();
    if (revisions.isEmpty() || !revisions.getLast().reference().equals(
        artifacts.latest(session.orElseThrow().compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().reference())) return;
    var history = new ArrayList<RejectedReferenceRepair.Attempt>();
    for (var revision : revisions) {
      var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
      try {
        var input = captureCandidate(revision);
        if (input != null && input.facts() != null && input.facts().stream().allMatch(Objects::nonNull)) {
          history.add(new RejectedReferenceRepair.Attempt(revision.sequence(), input, attempt.issues()));
        }
      } catch (IllegalArgumentException malformed) {
        // Malformed arguments cannot establish an internal-reference correction.
      }
    }
    if (history.isEmpty() || history.getLast().sequence() != revisions.getLast().sequence()) return;
    var repaired = RejectedReferenceRepair.repair(base, scope.facts(), history);
    var values = RequirementRevisionAdmission.pendingValues(scope.values());
    var evidenceSequences = new java.util.HashSet<>(repaired.evidenceSequences());
    for (var attempt : history) {
      var projected = RequirementRevisionAdmission.reprojectCapabilityConflicts(base, scope.facts(), values, attempt.input());
      if (!projected.equals(values)) evidenceSequences.add(attempt.sequence());
      values = projected;
    }
    if (repaired.facts().equals(scope.facts())
        && values.equals(RequirementRevisionAdmission.pendingValues(scope.values()))) return;
    var evidence = revisions.stream().filter(value -> evidenceSequences.contains(value.sequence()))
        .map(Revision::reference).toList();
    saveFactProtection(conversationId, repaired.facts(), values, evidence);
    rejectedCaptureFacts.put(conversationId, repaired.facts());
    rejectedCaptureValues.put(conversationId, values);
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
    if (!currentSource(conversationId) || arguments == null
        || latestCaptureAttempt(conversationId).filter(attempt -> "reset".equals(attempt.toolName())).isPresent()) {
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

  private boolean samePendingProtection(String conversationId, Revision attempt) {
    var current = factProtection(conversationId, rejectedCaptureFacts(conversationId));
    var previous = artifacts.history(attempt.compilationId(), Kind.REQUIREMENT_CAPTURE_PROTECTION).stream()
        .filter(revision -> revision.sequence() < attempt.sequence())
        .map(revision -> artifacts.payload(revision, CaptureFactProtection.class))
        .filter(protection -> protection.sameSource(current) && Objects.equals(protection.baseIdentity(), current.baseIdentity()))
        .reduce((first, last) -> last);
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
    var source = factProtection(conversationId, List.of());
    var attempts = sessions.current(conversationId).stream()
        .flatMap(link -> artifacts.history(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream())
        .map(revision -> artifacts.payload(revision, RequirementCaptureAttempt.class))
        .filter(attempt -> !attempt.accepted())
        .filter(attempt -> new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(),
            attempt.authorText(), List.of()).sameSource(source))
        .map(attempt -> (com.fasterxml.jackson.databind.JsonNode) new ObjectMapper().valueToTree(attempt)).toList();
    return attempts.isEmpty() ? "" : org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory
        .projectValues(attempts, new ObjectMapper(), org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.CHAR_BUDGET).toString();
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
    return sessions.current(conversationId).stream()
        .flatMap(link -> artifacts.history(link.compilationId(), Kind.REQUIREMENT_CAPTURE_ATTEMPT).stream())
        .filter(revision -> {
          var attempt = artifacts.payload(revision, RequirementCaptureAttempt.class);
          return !attempt.accepted() && List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())
              && new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(), List.of())
                  .sameSource(source)
              && sameAcceptedBase(conversationId, attempt.acceptedDraftReference());
        }).reduce((first, last) -> last);
  }

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
      if ("reset".equals(attempt.toolName())) return Optional.empty();
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
          attempt.toolName(), candidate, attempt.issues(),
          org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(revision.reference())));
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

  public record RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
      String mutationScope, String toolName, Object arguments, boolean accepted,
      List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference, String repairAuthority,
      RequirementCaptureToolProvider.ArgumentBinding argumentBinding, String authorEvidenceHash,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      RequirementCaptureInput.DraftInput resolvedCandidate) {
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
