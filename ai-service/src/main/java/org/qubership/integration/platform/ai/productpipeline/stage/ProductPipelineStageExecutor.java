package org.qubership.integration.platform.ai.productpipeline.stage;

import io.smallrye.mutiny.Uni;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputIdsPathPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.BypassPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.SkipPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Runs one profile stage against pinned inputs and committed evidence. Lifecycle transitions stay
 * with the runtime or Flow.
 */
public final class ProductPipelineStageExecutor implements StageExecutor {

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final StageCapabilityRegistry capabilities;
  private final Clock clock;
  private final Map<String, ProductPipelineProfile> profilesByRun;
  private final Map<String, RunManifest> manifestsByRun;
  private final Map<String, Map<String, Object>> attributesByRun;
  private final Map<String, Integer> technicalRetriesByStage;
  private final ApprovalPrompts approvalPrompts;

  public ProductPipelineStageExecutor(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock,
      Map<String, ProductPipelineProfile> profilesByRun,
      Map<String, RunManifest> manifestsByRun,
      Map<String, Map<String, Object>> attributesByRun,
      Map<String, Integer> technicalRetriesByStage,
      ApprovalPrompts approvalPrompts) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.profilesByRun = Objects.requireNonNull(profilesByRun, "profilesByRun");
    this.manifestsByRun = Objects.requireNonNull(manifestsByRun, "manifestsByRun");
    this.attributesByRun = Objects.requireNonNull(attributesByRun, "attributesByRun");
    this.technicalRetriesByStage =
        Objects.requireNonNull(technicalRetriesByStage, "technicalRetriesByStage");
    this.approvalPrompts =
        approvalPrompts == null ? new ApprovalPrompts() : approvalPrompts;
  }

  @Override
  public Uni<StageExecutionResult> execute(String runId, String expectedStageId) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(expectedStageId, "expectedStageId");
    return Uni.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(runId);
              if (!expectedStageId.equals(doc.run().currentStageId())) {
                return Uni.createFrom()
                    .failure(
                        new IllegalStateException(
                            "expected stage "
                                + expectedStageId
                                + " but run is at "
                                + doc.run().currentStageId()));
              }
              StageSnapshot expected = currentStageSnapshot(doc, expectedStageId);
              if (expected.status() == StageStatus.SUCCEEDED) {
                return Uni.createFrom()
                    .item(
                        new StageExecutionResult(
                            new StageDecision.Continue(expectedStageId), List.of()));
              }
              if (doc.run().status() != RunStatus.RUNNING) {
                return Uni.createFrom()
                    .item(replaySettledDecision(doc, expectedStageId, expected));
              }
              return executeRunningStage(runId, doc);
            });
  }

  /**
   * Flow can re-enter {@code executeStage} after a wait is already committed. Returning Continue
   * would skip the human gate (live design-input advanced to design-planning without an IDS).
   */
  private StageExecutionResult replaySettledDecision(
      ProductPipelineRunDocument doc, String expectedStageId, StageSnapshot expected) {
    return switch (doc.run().status()) {
      case WAITING_FOR_INPUT -> {
        String prompt = latestWaitingForInputPrompt(doc);
        yield new StageExecutionResult(
            new StageDecision.WaitForInput(expectedStageId, prompt),
            List.of(new PipelineSignal.WaitingForInput(expectedStageId, prompt)));
      }
      case WAITING_FOR_APPROVAL -> {
        Reference candidate =
            expected.approvableReference() != null
                ? expected.approvableReference()
                : expected.candidateReferences().stream()
                    .reduce((first, second) -> second)
                    .orElse(expected.outputRefs().stream().findFirst().orElse(null));
        if (candidate == null) {
          String prompt = latestWaitingForInputPrompt(doc);
          yield new StageExecutionResult(
              new StageDecision.WaitForInput(expectedStageId, prompt),
              List.of(new PipelineSignal.WaitingForInput(expectedStageId, prompt)));
        }
        String prompt = approvalPromptFor(doc.run().runId(), expectedStageId);
        yield new StageExecutionResult(
            new StageDecision.WaitForApproval(expectedStageId, candidate, prompt),
            List.of(
                new PipelineSignal.WaitingForApproval(expectedStageId, candidate, prompt)));
      }
      case WAITING_FOR_IMPLEMENT ->
          new StageExecutionResult(
              new StageDecision.WaitForImplementation(expectedStageId, ""),
              List.of(new PipelineSignal.WaitingForImplement(expectedStageId, "")));
      case FAILED -> {
        String message = latestTransitionReason(doc);
        yield new StageExecutionResult(
            new StageDecision.Fail(expectedStageId, StageOutcomeClass.DOMAIN_FAILURE, message),
            List.of(
                new PipelineSignal.Failed(
                    expectedStageId, StageOutcomeClass.DOMAIN_FAILURE, message)));
      }
      case PLAN_APPROVED, CHAIN_MATERIALIZED ->
          new StageExecutionResult(
              new StageDecision.Complete(expectedStageId, doc.run().status()),
              List.of(new PipelineSignal.Completed(doc.run().status())));
      case RUNNING ->
          throw new IllegalStateException(
              "cannot replay a settled decision while run " + doc.run().runId() + " is RUNNING");
    };
  }

  private static String latestWaitingForInputPrompt(ProductPipelineRunDocument doc) {
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElse("");
  }

  private static String latestTransitionReason(ProductPipelineRunDocument doc) {
    if (doc.transitions().isEmpty()) {
      return "";
    }
    String reason = doc.transitions().get(doc.transitions().size() - 1).reason();
    return reason == null ? "" : reason;
  }

  private Uni<StageExecutionResult> executeRunningStage(
      String runId, ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(runId);
    ProfileStage stage = currentStage(doc);
    if (stage.bypass() != null) {
      return Uni.createFrom().item(executeBypass(runId, doc, stage));
    }
    List<Reference> committed = committedInputs(doc);
    Map<String, Object> attributes =
        enrichAttributesFromCommittedInputs(
            runId, committed, attributesByRun.getOrDefault(runId, Map.of()));
    Optional<SkipPolicy.SkipAction> skipAction = evaluateSkip(stage, attributes);
    if (skipAction.isPresent()) {
      return Uni.createFrom()
          .item(
              switch (skipAction.get()) {
                case NO_OUTPUT -> executeNoOutputSkip(runId, stage);
                case REQUIREMENT_DRAFT_PASSTHROUGH ->
                    executeSkip(runId, stage, attributes);
              });
    }
    DeclaredInputResolution inputResolution = resolveDeclaredInputs(profile, stage, committed);
    if (inputResolution.missingRequired() != null) {
      ArtifactTypeRef missing = inputResolution.missingRequired();
      String prompt =
          isProfileRunInput(profile, missing)
              ? ""
              : "missing required input " + missing.type() + "@" + missing.schemaVersion();
      return Uni.createFrom()
          .item(
              handleOutcome(
                  runId,
                  stage,
                  StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, prompt),
                  List.of()));
    }
    List<Reference> inputs = inputResolution.inputs();
    StageCapability capability = capabilities.require(stage.capabilityId());
    String attemptId = UUID.randomUUID().toString();
    StageExecutionContext context =
        new StageExecutionContext(
            runId,
            doc.run().conversationId(),
            stage.stageId(),
            executionKey(runId, stage.stageId()),
            attemptId,
            profile,
            manifestsByRun.get(runId),
            inputs,
            attributes);
    return capability
        .execute(context)
        .onItem()
        .invoke(signal -> forwardLiveSkillProgress(runId, signal))
        .collect()
        .asList()
        .map(signals -> handleCapabilitySignals(runId, stage, signals));
  }

  /**
   * Flow waits to drain stage signals until the capability Multi completes. Push skill rows to the
   * in-flight chat command as each {@link CapabilitySignal.SkillProgress} arrives.
   */
  private static void forwardLiveSkillProgress(String runId, CapabilitySignal signal) {
    if (signal instanceof CapabilitySignal.SkillProgress skillProgress) {
      PipelineSignalLiveSink.emit(
          runId, new PipelineSignal.SkillProgress(skillProgress.skillId(), skillProgress.status()));
    }
  }

  private StageExecutionResult handleCapabilitySignals(
      String runId, ProfileStage stage, List<CapabilitySignal> signals) {
    List<PipelineSignal> live = new ArrayList<>();
    for (CapabilitySignal signal : signals) {
      if (signal instanceof CapabilitySignal.SkillProgress skillProgress) {
        // Live rows already went out through forwardLiveSkillProgress. Putting them in the drain
        // batch replays the skill timeline after the stage ends.
        if (PipelineSignalLiveSink.isBound(runId)) {
          continue;
        }
        live.add(new PipelineSignal.SkillProgress(skillProgress.skillId(), skillProgress.status()));
      } else if (signal instanceof CapabilitySignal.Progress progress) {
        live.add(new PipelineSignal.Progress(stage.stageId(), progress.label()));
      } else if (signal instanceof CapabilitySignal.Message message) {
        live.add(new PipelineSignal.Message(message.text()));
      }
    }
    return handleOutcome(runId, stage, requireSingleCompleted(signals), live);
  }

  private StageExecutionResult executeNoOutputSkip(String runId, ProfileStage stage) {
    return handleOutcome(
        runId,
        stage,
        new StageOutcome(
            StageOutcomeClass.SUCCEEDED,
            List.of(),
            "stage skipped with no output by profile skip policy",
            null),
        List.of());
  }

  private StageExecutionResult executeSkip(
      String runId, ProfileStage stage, Map<String, Object> attributes) {
    RequirementDraft draft =
        attributes.get("approvedDraft") instanceof RequirementDraft requirementDraft
            ? requirementDraft
            : null;
    if (draft == null) {
      return handleOutcome(
          runId,
          stage,
          StageOutcome.of(
              StageOutcomeClass.MISSING_MANDATORY_INPUT,
              "skip requires committed requirement-draft"),
          List.of());
    }
    return handleOutcome(
        runId,
        stage,
        new StageOutcome(
            StageOutcomeClass.SUCCEEDED,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
            "stage skipped by profile skip policy",
            null),
        List.of());
  }

  private StageExecutionResult executeBypass(
      String runId, ProductPipelineRunDocument doc, ProfileStage stage) {
    BypassPolicy bypass = stage.bypass();
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                runId,
                Kind.IDS_BYPASS,
                "1",
                "product-pipeline-runtime",
                "1",
                Map.of(
                    "type",
                    bypass.produces().type(),
                    "schemaVersion",
                    bypass.produces().schemaVersion()),
                List.of(),
                null,
                provenance(runId, stage.stageId(), null)));
    List<StageSnapshot> updated =
        markStageOutputs(doc, stage.stageId(), List.of(revision.reference()), StageStatus.SUCCEEDED);
    ProductPipelineProfile profile = profilesByRun.get(runId);
    if (profile.terminal().stageId().equals(stage.stageId())) {
      RunStatus terminalStatus = terminalStatus(profile);
      commitStatus(doc, terminalStatus, StageStatus.SUCCEEDED, updated, "bypass terminal");
      return new StageExecutionResult(
          new StageDecision.Complete(stage.stageId(), terminalStatus),
          List.of(new PipelineSignal.Completed(terminalStatus)));
    }
    commitStatus(doc, RunStatus.RUNNING, StageStatus.SUCCEEDED, updated, "bypass committed");
    return new StageExecutionResult(new StageDecision.Continue(stage.stageId()), List.of());
  }

  private StageExecutionResult handleOutcome(
      String runId,
      ProfileStage stage,
      StageOutcome outcome,
      List<PipelineSignal> live) {
    ProductPipelineRunDocument doc = requireRun(runId);
    List<PipelineSignal> emitted = new ArrayList<>(live);
    return switch (outcome.outcomeClass()) {
      case NEEDS_INPUT -> {
        commitStatus(
            doc,
            RunStatus.WAITING_FOR_INPUT,
            StageStatus.WAITING_FOR_INPUT,
            doc.run().stages(),
            outcome.message());
        String prompt = outcome.message() == null ? "" : outcome.message();
        emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
        yield new StageExecutionResult(
            new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
      }
      case CANDIDATE -> {
        CandidateResolution resolution = resolveCandidateResolution(stage, outcome.candidates());
        if (resolution.contractFailure() != null) {
          String evidence = "CONTRACT_FAILURE: " + resolution.contractFailure();
          commitStatus(
              doc,
              RunStatus.FAILED,
              StageStatus.FAILED,
              doc.run().stages(),
              evidence,
              evidence);
          emitted.add(
              new PipelineSignal.Failed(
                  stage.stageId(),
                  StageOutcomeClass.CONTRACT_FAILURE,
                  resolution.contractFailure()));
          yield new StageExecutionResult(
              new StageDecision.Fail(
                  stage.stageId(),
                  StageOutcomeClass.CONTRACT_FAILURE,
                  resolution.contractFailure()),
              emitted);
        }
        List<Reference> refs =
            appendCandidates(runId, stage, resolution.resolvedCandidates(), committedInputs(doc));
        Reference approvable = selectByPolicy(refs, stage.approval().artifact());
        int nextCandidateRevision =
            currentStageSnapshot(doc, stage.stageId()).candidateRevision() == null
                ? 1
                : currentStageSnapshot(doc, stage.stageId()).candidateRevision() + 1;
        List<StageSnapshot> updated = new ArrayList<>();
        for (StageSnapshot snapshot : doc.run().stages()) {
          if (snapshot.stageId().equals(stage.stageId())) {
            List<Reference> allCandidates = new ArrayList<>(snapshot.candidateReferences());
            allCandidates.addAll(refs);
            updated.add(
                new StageSnapshot(
                    stage.stageId(),
                    StageStatus.WAITING_FOR_APPROVAL,
                    refs,
                    null,
                    allCandidates,
                    approvable,
                    nextCandidateRevision));
          } else {
            updated.add(snapshot);
          }
        }
        commitStatus(
            doc,
            RunStatus.WAITING_FOR_APPROVAL,
            StageStatus.WAITING_FOR_APPROVAL,
            updated,
            outcome.message());
        String prompt = approvalPromptFor(runId, stage.stageId());
        emitted.add(new PipelineSignal.WaitingForApproval(stage.stageId(), approvable, prompt));
        yield new StageExecutionResult(
            new StageDecision.WaitForApproval(stage.stageId(), approvable, prompt), emitted);
      }
      case SUCCEEDED -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        List<StageSnapshot> updated =
            markStageOutputs(doc, stage.stageId(), refs, StageStatus.SUCCEEDED);
        ProductPipelineProfile profile = profilesByRun.get(runId);
        if (profile.terminal().stageId().equals(stage.stageId())) {
          RunStatus terminalStatus = terminalStatus(profile);
          commitStatus(doc, terminalStatus, StageStatus.SUCCEEDED, updated, outcome.message());
          emitted.add(new PipelineSignal.Completed(terminalStatus));
          yield new StageExecutionResult(
              new StageDecision.Complete(stage.stageId(), terminalStatus), emitted);
        }
        commitStatus(doc, RunStatus.RUNNING, StageStatus.SUCCEEDED, updated, outcome.message());
        yield new StageExecutionResult(new StageDecision.Continue(stage.stageId()), emitted);
      }
      case RETRYABLE_TECHNICAL_FAILURE -> {
        String key = stageRetryKey(runId, stage.stageId());
        int used = technicalRetriesByStage.getOrDefault(key, 0);
        int max = stage.retry().maxTechnicalRetries();
        if (used >= max) {
          String evidence =
              outcome.outcomeClass().name()
                  + (outcome.message() == null || outcome.message().isBlank()
                      ? ""
                      : ": " + outcome.message());
          commitStatus(
              doc,
              RunStatus.FAILED,
              StageStatus.FAILED,
              doc.run().stages(),
              evidence,
              evidence);
          emitted.add(
              new PipelineSignal.Failed(
                  stage.stageId(), outcome.outcomeClass(), outcome.message()));
          yield new StageExecutionResult(
              new StageDecision.Fail(stage.stageId(), outcome.outcomeClass(), outcome.message()),
              emitted);
        }
        technicalRetriesByStage.put(key, used + 1);
        long delayMs =
            outcome.retryDelayMs() != null
                ? outcome.retryDelayMs()
                : stage.retry().defaultDelayMs();
        yield new StageExecutionResult(
            new StageDecision.Retry(stage.stageId(), Duration.ofMillis(Math.max(delayMs, 0L))),
            emitted);
      }
      case VALIDATION_FAILURE -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        String failureMessage =
            outcome.message() == null || outcome.message().isBlank()
                ? outcome.outcomeClass().name()
                : outcome.message();
        ProductPipelineProfile profile = profilesByRun.get(runId);
        Optional<String> reopenStageId = previousApprovalStageId(profile, stage.stageId());
        if (reopenStageId.isPresent()) {
          yield new StageExecutionResult(
              new StageDecision.ReopenApproval(
                  stage.stageId(), reopenStageId.get(), failureMessage, refs),
              emitted);
        }
        yield failClosed(doc, stage, refs, outcome.outcomeClass(), failureMessage, emitted);
      }
      case CONTRACT_FAILURE,
          POLICY_FAILURE,
          DOMAIN_FAILURE,
          MISSING_MANDATORY_INPUT -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        yield failClosed(
            doc,
            stage,
            refs,
            outcome.outcomeClass(),
            outcome.message(),
            emitted);
      }
    };
  }

  private StageExecutionResult failClosed(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      StageOutcomeClass outcomeClass,
      String message,
      List<PipelineSignal> emitted) {
    List<StageSnapshot> failedStages =
        refs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, stage.stageId(), refs, StageStatus.FAILED);
    String evidence =
        outcomeClass.name()
            + (message == null || message.isBlank() ? "" : ": " + message);
    commitStatus(doc, RunStatus.FAILED, StageStatus.FAILED, failedStages, evidence, evidence);
    emitted.add(new PipelineSignal.Failed(stage.stageId(), outcomeClass, message));
    return new StageExecutionResult(
        new StageDecision.Fail(stage.stageId(), outcomeClass, message), emitted);
  }

  private Map<String, Object> enrichAttributesFromCommittedInputs(
      String runId, List<Reference> inputs, Map<String, Object> base) {
    Map<String, Object> attributes = new HashMap<>(base == null ? Map.of() : base);
    for (Reference ref : inputs) {
      if (ref == null || ref.kind() == null) {
        continue;
      }
      Optional<Revision> revision = artifactStore.get(runId, ref);
      if (revision.isEmpty()) {
        continue;
      }
      if (ref.kind() == Kind.REQUIREMENT_DRAFT) {
        attributes.put(
            "approvedDraft", artifactStore.payload(revision.get(), RequirementDraft.class));
      } else if (ref.kind() == Kind.REQUIREMENT_BRIEF
          && !attributes.containsKey("requirementBrief")) {
        attributes.put(
            "requirementBrief", artifactStore.payload(revision.get(), RequirementBrief.class));
      } else if (ref.kind() == Kind.DESIGN_ENTRY_ROUTE
          && !attributes.containsKey("designEntryRoute")) {
        attributes.put(
            "designEntryRoute", artifactStore.payload(revision.get(), DesignEntryRoute.class));
      } else if (ref.kind() == Kind.IDS_DOCUMENT && !attributes.containsKey("idsDocument")) {
        attributes.put("idsDocument", artifactStore.payload(revision.get(), IdsDocument.class));
      } else if (ref.kind() == Kind.NORMALIZED_DESIGN_FLOW
          && !attributes.containsKey("normalizedDesignFlow")) {
        attributes.put(
            "normalizedDesignFlow",
            artifactStore.payload(revision.get(), NormalizedDesignFlow.class));
      }
    }
    attributesByRun.put(runId, attributes);
    return attributes;
  }

  private Optional<SkipPolicy.SkipAction> evaluateSkip(
      ProfileStage stage, Map<String, Object> attributes) {
    SkipPolicy skip = stage.skip();
    if (skip == null || skip.whenAny().isEmpty()) {
      return Optional.empty();
    }
    RequirementDraft draft =
        attributes.get("approvedDraft") instanceof RequirementDraft requirementDraft
            ? requirementDraft
            : null;
    DesignEntryRoute route =
        attributes.get("designEntryRoute") instanceof DesignEntryRoute designEntryRoute
            ? designEntryRoute
            : null;
    return skip.evaluate(new SkipPolicy.SkipEvaluationContext(draft, route));
  }

  private DeclaredInputResolution resolveDeclaredInputs(
      ProductPipelineProfile profile, ProfileStage stage, List<Reference> committed) {
    if (profile == null || !"2".equals(profile.profileVersion())) {
      return new DeclaredInputResolution(committed, null);
    }
    List<Reference> resolved = new ArrayList<>();
    for (Reference ref : committed) {
      if (ref != null && ref.kind() == Kind.RUN_MANIFEST) {
        resolved.add(ref);
      }
    }
    for (ArtifactTypeRef required : stage.consumes()) {
      Reference found = findCommitted(committed, required);
      if (found == null) {
        return new DeclaredInputResolution(List.of(), required);
      }
      if (!resolved.contains(found)) {
        resolved.add(found);
      }
    }
    for (ArtifactTypeRef optional : stage.optionalConsumes()) {
      Reference found = findCommitted(committed, optional);
      if (found != null && !resolved.contains(found)) {
        resolved.add(found);
      }
    }
    return new DeclaredInputResolution(List.copyOf(resolved), null);
  }

  private static Reference findCommitted(List<Reference> committed, ArtifactTypeRef typeRef) {
    if (typeRef == null || committed == null) {
      return null;
    }
    return committed.stream()
        .filter(ref -> typeRef.matches(ref.kind()))
        .reduce((first, second) -> second)
        .orElse(null);
  }

  private static boolean isProfileRunInput(
      ProductPipelineProfile profile, ArtifactTypeRef missing) {
    if (profile == null || missing == null || missing.type() == null) {
      return false;
    }
    return profile.runInputs().stream()
        .anyMatch(
            input ->
                input != null
                    && missing.type().equals(input.type())
                    && missing.schemaVersion() == input.schemaVersion());
  }

  private StageOutcome requireSingleCompleted(List<CapabilitySignal> signals) {
    List<StageOutcome> completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .map(CapabilitySignal.Completed::outcome)
            .toList();
    if (completed.size() != 1) {
      return StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE,
          "capability must emit exactly one Completed signal, got " + completed.size());
    }
    return completed.get(0);
  }

  private CandidateResolution resolveCandidateResolution(
      ProfileStage stage, List<ArtifactCandidate> candidates) {
    if (stage.approval() == null) {
      return new CandidateResolution(List.of(), "candidate outcome requires approval policy");
    }
    if (candidates == null || candidates.isEmpty()) {
      return new CandidateResolution(List.of(), "candidate outcome emitted no artifacts");
    }
    List<ArtifactTypeRef> allowedTypes = candidateResolutionTypes(stage);
    List<ResolvedCandidate> resolved = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      if (candidate == null || candidate.kind() == null) {
        return new CandidateResolution(List.of(), "candidate artifact kind is required");
      }
      List<ArtifactTypeRef> matches =
          allowedTypes.stream().filter(typeRef -> typeRef.matches(candidate.kind())).toList();
      if (matches.isEmpty()) {
        return new CandidateResolution(
            List.of(), "unknown candidate kind " + candidate.kind().name());
      }
      if (matches.size() > 1) {
        return new CandidateResolution(
            List.of(),
            "duplicate candidate-set declarations for kind " + candidate.kind().name());
      }
      ArtifactTypeRef resolvedType = matches.get(0);
      if (resolvedType.schemaVersion() <= 0) {
        return new CandidateResolution(
            List.of(), "undeclared schema version for kind " + candidate.kind().name());
      }
      resolved.add(new ResolvedCandidate(candidate, resolvedType));
    }
    for (ArtifactTypeRef required : stage.approval().candidateSet()) {
      long count =
          resolved.stream()
              .map(ResolvedCandidate::candidate)
              .filter(candidate -> required.matches(candidate.kind()))
              .count();
      if (count != 1) {
        return new CandidateResolution(
            List.of(),
            "candidate set kind "
                + required.type()
                + " must occur exactly once, but occurred "
                + count);
      }
    }
    long approvableCount =
        resolved.stream()
            .map(ResolvedCandidate::candidate)
            .filter(candidate -> stage.approval().artifact().matches(candidate.kind()))
            .count();
    if (approvableCount != 1) {
      return new CandidateResolution(
          List.of(),
          "approval target kind "
              + stage.approval().artifact().type()
              + " must occur exactly once, but occurred "
              + approvableCount);
    }
    return new CandidateResolution(List.copyOf(resolved), null);
  }

  private List<ResolvedCandidate> resolveProducedCandidates(
      ProfileStage stage, List<ArtifactCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<ArtifactTypeRef> producedTypes = declaredProduces(stage);
    List<ResolvedCandidate> resolved = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      if (candidate == null || candidate.kind() == null) {
        continue;
      }
      ArtifactTypeRef matched =
          producedTypes.stream()
              .filter(typeRef -> typeRef.matches(candidate.kind()))
              .findFirst()
              .orElse(null);
      int schemaVersion =
          matched == null || matched.schemaVersion() <= 0 ? 1 : matched.schemaVersion();
      String typeName = matched == null ? candidate.kind().name() : matched.type();
      resolved.add(new ResolvedCandidate(candidate, new ArtifactTypeRef(typeName, schemaVersion)));
    }
    return List.copyOf(resolved);
  }

  private static List<ArtifactTypeRef> declaredProduces(ProfileStage stage) {
    List<ArtifactTypeRef> produced = new ArrayList<>();
    if (stage.produces() != null) {
      produced.addAll(stage.produces());
    }
    if (stage.optionalProduces() != null) {
      produced.addAll(stage.optionalProduces());
    }
    return produced;
  }

  private static List<ArtifactTypeRef> declaredConsumes(ProfileStage stage) {
    List<ArtifactTypeRef> consumed = new ArrayList<>();
    if (stage.consumes() != null) {
      consumed.addAll(stage.consumes());
    }
    if (stage.optionalConsumes() != null) {
      consumed.addAll(stage.optionalConsumes());
    }
    return consumed;
  }

  private static List<ArtifactTypeRef> candidateResolutionTypes(ProfileStage stage) {
    List<ArtifactTypeRef> types = new ArrayList<>(declaredProduces(stage));
    if (stage.approval() == null) {
      return types;
    }
    List<ArtifactTypeRef> consumes = declaredConsumes(stage);
    for (ArtifactTypeRef candidateType : stage.approval().candidateSet()) {
      boolean alreadyDeclared = types.stream().anyMatch(candidateType::equals);
      if (alreadyDeclared) {
        continue;
      }
      boolean fromConsumes = consumes.stream().anyMatch(candidateType::equals);
      if (fromConsumes) {
        types.add(candidateType);
      }
    }
    return types;
  }

  private static boolean isProducedKind(ProfileStage stage, Kind kind) {
    return declaredProduces(stage).stream().anyMatch(typeRef -> typeRef.matches(kind));
  }

  private Reference selectByPolicy(List<Reference> refs, ArtifactTypeRef targetType) {
    return refs.stream()
        .filter(ref -> targetType.matches(ref.kind()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "approval target kind " + targetType.type() + " is missing in candidate refs"));
  }

  private List<Reference> appendCandidates(
      String runId, ProfileStage stage, List<ResolvedCandidate> candidates) {
    return appendCandidates(runId, stage, candidates, List.of());
  }

  private List<Reference> appendCandidates(
      String runId,
      ProfileStage stage,
      List<ResolvedCandidate> candidates,
      List<Reference> reusableInputs) {
    List<Reference> refs = new ArrayList<>();
    for (ResolvedCandidate candidate : candidates) {
      Kind kind = candidate.candidate().kind();
      if (!isProducedKind(stage, kind)) {
        Reference existing =
            reusableInputs.stream()
                .filter(ref -> ref != null && ref.kind() == kind)
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "immutable candidate kind "
                                + kind.name()
                                + " is missing from committed inputs"));
        refs.add(existing);
        continue;
      }
      Revision revision =
          artifactStore.append(
              new AppendCommand(
                  runId,
                  kind,
                  String.valueOf(candidate.typeRef().schemaVersion()),
                  stage.capabilityId() == null ? "bypass" : stage.capabilityId(),
                  "1",
                  candidate.candidate().payload(),
                  candidate.candidate().inputs(),
                  null,
                  provenance(runId, stage.stageId(), stage.capabilityId())));
      refs.add(revision.reference());
    }
    return List.copyOf(refs);
  }

  private List<Reference> committedInputs(ProductPipelineRunDocument doc) {
    List<Reference> refs = new ArrayList<>();
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.approvedArtifactId() != null) {
        if (profile == null) {
          snapshot.outputRefs().stream()
              .filter(ref -> ref.artifactId().equals(snapshot.approvedArtifactId()))
              .findFirst()
              .ifPresent(refs::add);
        } else {
          ProfileStage approvedStage = stageById(profile, snapshot.stageId());
          if (approvedStage.approval() != null) {
            refs.addAll(approvedCandidates(snapshot.outputRefs(), approvedStage.approval()));
          } else {
            snapshot.outputRefs().stream()
                .filter(ref -> ref.artifactId().equals(snapshot.approvedArtifactId()))
                .findFirst()
                .ifPresent(refs::add);
          }
          snapshot.outputRefs().stream()
              .filter(ref -> ref.kind() == Kind.APPROVAL_RECORD)
              .reduce((first, second) -> second)
              .ifPresent(refs::add);
        }
      } else if (snapshot.status() == StageStatus.SUCCEEDED) {
        refs.addAll(snapshot.outputRefs());
      }
    }
    if (doc.run().runManifestRef() != null) {
      refs.add(0, doc.run().runManifestRef());
    }
    artifactStore.history(doc.run().runId(), Kind.USER_INPUT).stream()
        .map(Revision::reference)
        .forEach(refs::add);
    return List.copyOf(refs);
  }

  private List<Reference> approvedCandidates(
      List<Reference> refs, ApprovalPolicy approval) {
    if (approval == null) {
      return List.of();
    }
    List<Reference> approved = new ArrayList<>();
    for (ArtifactTypeRef required : approval.candidateSet()) {
      Reference reference =
          refs.stream()
              .filter(ref -> required.matches(ref.kind()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "required candidate kind " + required.type() + " is missing"));
      approved.add(reference);
    }
    return List.copyOf(approved);
  }

  private void commitStatus(
      ProductPipelineRunDocument doc,
      RunStatus nextStatus,
      StageStatus stageStatus,
      List<StageSnapshot> stages,
      String reason) {
    commitStatus(doc, nextStatus, stageStatus, stages, reason, null);
  }

  private void commitStatus(
      ProductPipelineRunDocument doc,
      RunStatus nextStatus,
      StageStatus stageStatus,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence) {
    List<StageSnapshot> nextStages = new ArrayList<>();
    for (StageSnapshot snapshot : stages) {
      if (snapshot.stageId().equals(doc.run().currentStageId())) {
        nextStages.add(
            new StageSnapshot(
                snapshot.stageId(),
                stageStatus,
                snapshot.outputRefs(),
                snapshot.approvedArtifactId(),
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
      } else {
        nextStages.add(snapshot);
      }
    }
    long expected = doc.run().runRevision();
    runStore.commit(
        expected,
        new LogicalCommit(
            doc.run().runId(),
            expected,
            nextStatus,
            doc.run().currentStageId(),
            nextStages,
            new StageAttempt(
                UUID.randomUUID().toString(),
                doc.run().currentStageId(),
                expected + 1L,
                stageStatus,
                clock.instant(),
                clock.instant(),
                nextStages.stream()
                    .filter(s -> s.stageId().equals(doc.run().currentStageId()))
                    .findFirst()
                    .map(StageSnapshot::outputRefs)
                    .orElse(List.of()),
                failureEvidence),
            new RunTransition(
                expected,
                expected + 1L,
                doc.run().status(),
                nextStatus,
                doc.run().currentStageId(),
                clock.instant(),
                reason,
                null,
                null)));
  }

  private static List<StageSnapshot> markStageOutputs(
      ProductPipelineRunDocument doc, String stageId, List<Reference> refs, StageStatus status) {
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.stageId().equals(stageId)) {
        updated.add(
            new StageSnapshot(
                stageId,
                status,
                refs,
                snapshot.approvedArtifactId(),
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
      } else {
        updated.add(snapshot);
      }
    }
    return updated;
  }

  private StageSnapshot currentStageSnapshot(ProductPipelineRunDocument doc, String stageId) {
    return doc.run().stages().stream()
        .filter(snapshot -> snapshot.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private ProfileStage currentStage(ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    return profile.stages().stream()
        .filter(stage -> stage.stageId().equals(doc.run().currentStageId()))
        .findFirst()
        .orElseThrow();
  }

  private static ProfileStage stageById(ProductPipelineProfile profile, String stageId) {
    return profile.stages().stream()
        .filter(snapshot -> snapshot.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  public static Optional<String> previousApprovalStageId(
      ProductPipelineProfile profile, String failedStageId) {
    if (profile == null || profile.stages() == null || failedStageId == null) {
      return Optional.empty();
    }
    String previous = null;
    for (ProfileStage stage : profile.stages()) {
      if (failedStageId.equals(stage.stageId())) {
        return Optional.ofNullable(previous);
      }
      if (stage.approval() != null) {
        previous = stage.stageId();
      }
    }
    return Optional.empty();
  }

  private ProductPipelineRunDocument requireRun(String runId) {
    return runStore
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
  }

  private ArtifactProvenance provenance(String runId, String stageId, String capabilityId) {
    RunManifest manifest = manifestsByRun.get(runId);
    return new ArtifactProvenance(
        runId,
        stageId,
        manifest == null ? "unknown" : manifest.profileId(),
        manifest == null ? "1" : manifest.profileVersion(),
        manifest == null ? "unknown" : manifest.profileDigest(),
        capabilityId == null ? "runtime" : capabilityId,
        "1",
        manifest == null ? "unknown" : manifest.dependencyClosureDigest());
  }

  private static String executionKey(String runId, String stageId) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((runId + ":" + stageId).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String stageRetryKey(String runId, String stageId) {
    return runId + ":" + stageId;
  }

  private String approvalPromptFor(String runId, String stageId) {
    RunManifest manifest = manifestsByRun.get(runId);
    String responseLocale = manifest == null ? "en" : manifest.responseLocale();
    return approvalPrompts.stageApprovalPrompt(stageId, responseLocale, languageReferenceFor(runId));
  }

  private String languageReferenceFor(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return "";
    }
    Object brief = attributes.get("requirementBrief");
    if (brief instanceof RequirementBrief requirementBrief) {
      return DesignInputIdsPathPrompts.languageReference(requirementBrief);
    }
    Object discovery = attributes.get("discoveryUserText");
    if (discovery instanceof String text && !text.isBlank()) {
      return text.trim();
    }
    Object userText = attributes.get("userText");
    if (userText instanceof String text && !text.isBlank()) {
      return text.trim();
    }
    return "";
  }

  private static RunStatus terminalStatus(ProductPipelineProfile profile) {
    return RunStatus.valueOf(profile.terminal().state());
  }

  private record DeclaredInputResolution(List<Reference> inputs, ArtifactTypeRef missingRequired) {
    private DeclaredInputResolution {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
  }

  private record ResolvedCandidate(ArtifactCandidate candidate, ArtifactTypeRef typeRef) {}

  private record CandidateResolution(
      List<ResolvedCandidate> resolvedCandidates, String contractFailure) {

    private CandidateResolution {
      resolvedCandidates =
          resolvedCandidates == null ? List.of() : List.copyOf(resolvedCandidates);
    }
  }
}
