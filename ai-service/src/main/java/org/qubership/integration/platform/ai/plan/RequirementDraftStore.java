package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

/** Stores requirement drafts as immutable revisions in the active compilation. */
@ApplicationScoped
public class RequirementDraftStore {

  private final ConcurrentHashMap<String, Boolean> capturedThisTurn = new ConcurrentHashMap<>();
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;

  @Inject
  public RequirementDraftStore(
      CompilationArtifacts artifacts, CompilationSessions sessions) {
    this.artifacts = artifacts;
    this.sessions = sessions;
  }

  public RequirementDraftStore() {
    this(memoryRuntime());
  }

  private RequirementDraftStore(MemoryRuntime runtime) {
    this(runtime.artifacts(), runtime.sessions());
  }

  public void put(String conversationId, RequirementDraft draft) {
    String compilationId = sessions.active(conversationId);
    String revisesArtifactId =
        artifacts
            .latest(compilationId, Kind.REQUIREMENT_DRAFT)
            .map(Revision::artifactId)
            .orElse(null);
    artifacts.append(
        new AppendCommand(
            compilationId,
            Kind.REQUIREMENT_DRAFT,
            "1",
            draft.sourceSkillId() != null ? draft.sourceSkillId() : "requirement-draft-store",
            draft.sourceSkillVersion(),
            draft,
            List.of(),
            revisesArtifactId));
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
  }

  /** Clears per-turn gather flags without starting a new compilation. */
  public void clearTurnFlags(String conversationId) {
    capturedThisTurn.remove(conversationId);
  }

  /** Returns the active compilation identity used for subsequent artifact revisions. */
  public String activeCompilationId(String conversationId) {
    return sessions.active(conversationId);
  }

  /** Marks the start of a gather scenario turn before the agent runs. */
  public void beginTurn(String conversationId) {
    capturedThisTurn.put(conversationId, false);
  }

  public void markCaptured(String conversationId) {
    capturedThisTurn.put(conversationId, true);
  }

  public boolean wasCapturedThisTurn(String conversationId) {
    return Boolean.TRUE.equals(capturedThisTurn.get(conversationId));
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
    if (current == null || binding == null) {
      return;
    }
    put(conversationId, current.withCatalogBinding(binding));
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
              next.catalogBinding(),
              next.awaitingPlanContinuation(),
              next.facts(),
              true,
              next.designModeHint());
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
