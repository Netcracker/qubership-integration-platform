package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
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

  private final ConcurrentHashMap<String, Boolean> capturedThisTurn = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RequirementDiscoveryDirective> turnDirectives =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> lastCaptureRejection = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> captureMutationScopes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> captureMutationRejected = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> currentAuthorText = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RequirementCaptureInput.DraftInput> policyQuestionsAtTurnStart =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<RequirementCaptureInput.FactInput>> rejectedCaptureFacts =
      new ConcurrentHashMap<>();
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;
  private final ConcurrentHashMap<String, org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document>
      sourceInputs = new ConcurrentHashMap<>();


  @Inject
  public RequirementDraftStore(
      CompilationArtifacts artifacts, CompilationSessions sessions,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.conversations = conversations;
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
    lastCaptureRejection.remove(conversationId);
    String compilationId = sessions.active(conversationId);
    String revisesArtifactId =
        artifacts
            .latest(compilationId, Kind.REQUIREMENT_DRAFT)
            .map(Revision::artifactId)
            .orElse(null);
    var source = sourceInputs.get(conversationId);
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
    var source = sourceInputs.remove(conversationId);
    try {
      put(conversationId, draft);
    } finally {
      if (source != null) {
        sourceInputs.put(conversationId, source);
      }
    }
  }

  public Optional<Revision> latestRevision(String conversationId) {
    return sessions
        .current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_DRAFT));
  }

  public Optional<RequirementDraft> get(String conversationId) {
    return latestRevision(conversationId)
        .map(revision -> artifacts.payload(revision, RequirementDraft.class));
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
    turnDirectives.remove(conversationId);
    lastCaptureRejection.remove(conversationId);
    captureMutationScopes.remove(conversationId);
    captureMutationRejected.remove(conversationId);
    currentAuthorText.remove(conversationId);
    policyQuestionsAtTurnStart.remove(conversationId);
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
    turnDirectives.remove(conversationId);
    lastCaptureRejection.remove(conversationId);
    captureMutationScopes.remove(conversationId);
    captureMutationRejected.remove(conversationId);
    currentAuthorText.remove(conversationId);
    policyQuestionsAtTurnStart.remove(conversationId);
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
      policyQuestionsAtTurnStart.remove(conversationId);
      get(conversationId).map(RequirementDraft::authoredDraft)
          .ifPresent(draft -> policyQuestionsAtTurnStart.put(conversationId, draft));
      capturedThisTurn.put(conversationId, capturedFromSource(conversationId, source));
      rejectedCaptureFacts.remove(conversationId);
      if (source != null || authorText != null) {
        captureMutationScopes.remove(conversationId);
      }
    }
    if (source != null) {
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
          .filter(previous -> previous.sameSource(scope) && !previous.facts().isEmpty())
          .ifPresent(previous -> rejectedCaptureFacts.put(conversationId, previous.facts()));
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
    if (conversations != null) {
      var source = sourceInputs.get(conversationId);
      if (source == null || source.activeTurns().stream().noneMatch(turn ->
          turn.message().role() == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
              && "READY".equals(turn.status()) && turn.consumedDraftReference() == null)) {
        return false;
      }
    } else if (currentAuthorText.getOrDefault(conversationId, "").isBlank()) {
      return false;
    }
    return RequirementCaptureEditor.wasPolicyQuestionAsked(
        policyQuestionsAtTurnStart.get(conversationId), candidate);
  }

  void rememberRejectedCaptureFacts(
      String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    if (!facts.isEmpty() && !rejectedCaptureFacts.containsKey(conversationId)) {
      saveFactProtection(conversationId, facts);
      rejectedCaptureFacts.putIfAbsent(conversationId, List.copyOf(facts));
    }
  }

  List<RequirementCaptureInput.FactInput> rejectedCaptureFacts(String conversationId) {
    return rejectedCaptureFacts.getOrDefault(conversationId, List.of());
  }

  void clearRejectedCaptureFacts(String conversationId) {
    rejectedCaptureFacts.remove(conversationId);
    if (latestFactProtection(conversationId).filter(protection -> !protection.facts().isEmpty()).isPresent()) {
      saveFactProtection(conversationId, List.of());
    }
  }

  private CaptureFactProtection factProtection(
      String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    var source = sourceInputs.get(conversationId);
    String inputId = source == null ? null
        : org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(source);
    return new CaptureFactProtection(inputId == null ? null : source.generation(), inputId,
        inputId == null ? currentAuthorText.get(conversationId) : null, facts);
  }

  private Optional<CaptureFactProtection> latestFactProtection(String conversationId) {
    return sessions.current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.REQUIREMENT_CAPTURE_PROTECTION))
        .map(revision -> artifacts.payload(revision, CaptureFactProtection.class));
  }

  private void saveFactProtection(String conversationId, List<RequirementCaptureInput.FactInput> facts) {
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION, "1",
        "requirement-capture-protection", "1", factProtection(conversationId, facts), List.of(),
        previous.map(Revision::artifactId).orElse(null)));
  }

  public record CaptureFactProtection(String sourceGeneration, String sourceTurnId, String authorText,
      List<RequirementCaptureInput.FactInput> facts) {
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
    return Optional.ofNullable(currentAuthorText.get(conversationId));
  }

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
    if (result.accepted() && !List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)) {
      return;
    }
    CaptureFactProtection source = factProtection(conversationId, List.of());
    saveCaptureAttempt(conversationId, new RequirementCaptureAttempt(source.sourceGeneration(), source.sourceTurnId(),
        source.authorText(), captureMutationScope(conversationId), toolName,
        arguments, result.accepted(), result.issues(), result.revision()));
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
        .filter(attempt -> Objects.equals(attempt.acceptedDraftReference(),
            latestRevision(conversationId).map(Revision::reference).orElse(null)));
  }

  private void saveCaptureAttempt(String conversationId, RequirementCaptureAttempt attempt) {
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    artifacts.append(new AppendCommand(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1",
        "requirement-capture-tools", "1", attempt,
        attempt.acceptedDraftReference() == null ? List.of() : List.of(attempt.acceptedDraftReference()),
        previous.map(Revision::artifactId).orElse(null)));
  }

  public record RequirementCaptureAttempt(String sourceGeneration, String sourceTurnId, String authorText,
      String mutationScope, String toolName, Object arguments, boolean accepted,
      List<RequirementCaptureResult.CaptureIssue> issues, Reference acceptedDraftReference) {
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
  public void recordImportFailure(String conversationId) {
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null) {
      return;
    }
    put(conversationId, current.clearApiHubCandidate().withImportIntent(true));
  }

  public void rememberPreferredSystemType(String conversationId, String systemType) {
    RequirementDraft current = get(conversationId).orElse(null);
    if (current == null) {
      put(conversationId, new RequirementDraft(false, "").withPreferredSystemType(systemType));
      return;
    }
    put(conversationId, current.withPreferredSystemType(systemType));
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
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    Clock clock = Clock.systemUTC();
    return new MemoryRuntime(
        new CompilationArtifacts(blobStore, objectMapper, clock),
        new CompilationSessions(blobStore, objectMapper, clock));
  }

  private record MemoryRuntime(
      CompilationArtifacts artifacts, CompilationSessions sessions) {}
}
