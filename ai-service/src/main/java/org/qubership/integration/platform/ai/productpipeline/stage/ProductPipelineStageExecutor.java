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
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.capture.ToolArgumentsFailures;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidate;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerDiagnosis;
import org.qubership.integration.platform.ai.productpipeline.create.ProducerOwnedRecovery;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.BypassPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.SkipPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecisionValidator;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidenceFactory;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryExecutor;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.QipSchemaYamlParser;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptKey;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
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

  private static final Logger LOG =
      Logger.getLogger(ProductPipelineStageExecutor.class);

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final StageCapabilityRegistry capabilities;
  private final Clock clock;
  private final Map<String, ProductPipelineProfile> profilesByRun;
  private final Map<String, RunManifest> manifestsByRun;
  private final Map<String, Map<String, Object>> attributesByRun;
  private final Map<String, Integer> technicalRetriesByStage;
  private final ApprovalPrompts approvalPrompts;
  private final FailureNarrative failureNarrative;
  private final int repeatedFailureThreshold;
  private final RecoveryAttemptLedger recoveryLedger;
  private volatile RecoveryValidationDeps recoveryValidationDeps;

  private static final class RecoveryValidationDeps {
    final ObjectMapper objectMapper;
    final SchemaResourceLoader schemaResourceLoader;
    final SchemaRefResolver schemaRefResolver;
    final DeterministicElementSchemaService schemaService;

    RecoveryValidationDeps() {
      this.objectMapper = new ObjectMapper();
      this.schemaResourceLoader = new SchemaResourceLoader();
      this.schemaRefResolver = new SchemaRefResolver(schemaResourceLoader, new QipSchemaYamlParser());
      this.schemaService = DeterministicElementSchemaService.createForUnitTests(objectMapper);
    }
  }

  public static final int MAX_SEMANTIC_REPAIRS = 1;
  public static final String PRODUCER_REPAIR_REASON_PREFIX = "producer-repair:";

  static final String INTERNAL_RECOVERY_SUMMARY =
      "A step inside the service broke. Repeating the same request will not help.";
  static final String UNKNOWN_PROPERTY_RECOVERY_SUMMARY =
      "The generator produced an invalid property. Repeating the same request will not help.";
  static final String REPEATED_RECOVERY_SUMMARY =
      "The same problem came back. Repeating the same request will not help.";
  static final String UNCLASSIFIED_RECOVERY_SUMMARY =
      "Creation stopped without a recoverable cause. Repeating the same request will not help.";
  private static final String PROGRESS_HALTED = "halted";
  private static final String PROGRESS_NONE = "none";

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
    this(
        runStore,
        artifactStore,
        capabilities,
        clock,
        profilesByRun,
        manifestsByRun,
        attributesByRun,
        technicalRetriesByStage,
        approvalPrompts,
        null);
  }

  public ProductPipelineStageExecutor(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock,
      Map<String, ProductPipelineProfile> profilesByRun,
      Map<String, RunManifest> manifestsByRun,
      Map<String, Map<String, Object>> attributesByRun,
      Map<String, Integer> technicalRetriesByStage,
      ApprovalPrompts approvalPrompts,
      FailureNarrative failureNarrative) {
    this(
        runStore,
        artifactStore,
        capabilities,
        clock,
        profilesByRun,
        manifestsByRun,
        attributesByRun,
        technicalRetriesByStage,
        approvalPrompts,
        failureNarrative,
        2);
  }

  public ProductPipelineStageExecutor(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock,
      Map<String, ProductPipelineProfile> profilesByRun,
      Map<String, RunManifest> manifestsByRun,
      Map<String, Map<String, Object>> attributesByRun,
      Map<String, Integer> technicalRetriesByStage,
      ApprovalPrompts approvalPrompts,
      FailureNarrative failureNarrative,
      int repeatedFailureThreshold) {
    this(
        runStore,
        artifactStore,
        capabilities,
        clock,
        profilesByRun,
        manifestsByRun,
        attributesByRun,
        technicalRetriesByStage,
        approvalPrompts,
        failureNarrative,
        repeatedFailureThreshold,
        new RecoveryAttemptLedger());
  }

  public ProductPipelineStageExecutor(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock,
      Map<String, ProductPipelineProfile> profilesByRun,
      Map<String, RunManifest> manifestsByRun,
      Map<String, Map<String, Object>> attributesByRun,
      Map<String, Integer> technicalRetriesByStage,
      ApprovalPrompts approvalPrompts,
      FailureNarrative failureNarrative,
      int repeatedFailureThreshold,
      RecoveryAttemptLedger recoveryLedger) {
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
    this.failureNarrative =
        failureNarrative == null ? new FailureNarrative() : failureNarrative;
    this.repeatedFailureThreshold = Math.max(2, repeatedFailureThreshold);
    this.recoveryLedger = recoveryLedger == null ? new RecoveryAttemptLedger() : recoveryLedger;
  }

  public ApprovalRecordV2 approveCandidate(
      ChainSemanticRevision revision, CompilerContract contract) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    if (!Objects.equals(revision.compilerContractVersion(), contract.contractVersion())) {
      throw new IllegalStateException("Approved compiler contract version does not match");
    }
    String digest = CanonicalPayloadHash.sha256Hex(revision);
    Reference target =
        new Reference(Kind.CHAIN_SEMANTIC_REVISION, revision.revisionId(), digest);
    return new ApprovalRecordV2(
        target,
        digest,
        List.of(target),
        "user",
        null,
        clock.instant(),
        null,
        null,
        Kind.CHAIN_SEMANTIC_REVISION.name(),
        revision.schemaVersion(),
        revision.revisionId(),
        digest,
        contract.contractVersion(),
        contract.sha256());
  }

  public void verifyApproval(ApprovalRecordV2 approval, ChainSemanticRevision liveRevision) {
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(liveRevision, "liveRevision");
    String liveDigest = CanonicalPayloadHash.sha256Hex(liveRevision);
    if (!Objects.equals(approval.subjectSha256(), liveDigest)) {
      throw new IllegalStateException("Approved semantic revision digest does not match");
    }
    if (!Objects.equals(approval.subjectSchemaVersion(), liveRevision.schemaVersion())) {
      throw new IllegalStateException("Approved semantic schema version does not match");
    }
    if (!Objects.equals(approval.subjectRevisionId(), liveRevision.revisionId())) {
      throw new IllegalStateException("Approved semantic revision id does not match");
    }
    if (!Kind.CHAIN_SEMANTIC_REVISION.name().equals(approval.subjectArtifactKind())) {
      throw new IllegalStateException("Approved semantic artifact kind does not match");
    }
    if (!Objects.equals(
        approval.compilerContractVersion(), liveRevision.compilerContractVersion())) {
      throw new IllegalStateException("Approved compiler contract version does not match");
    }
    if (approval.compilerContractSha256() == null || approval.compilerContractSha256().isBlank()) {
      throw new IllegalStateException("Approved compiler contract digest does not match");
    }
  }

  public void verifyApproval(
      ApprovalRecordV2 approval, ChainSemanticRevision liveRevision, CompilerContract contract) {
    verifyApproval(approval, liveRevision);
    Objects.requireNonNull(contract, "contract");
    if (!Objects.equals(approval.compilerContractVersion(), contract.contractVersion())
        || !Objects.equals(approval.compilerContractSha256(), contract.sha256())) {
      throw new IllegalStateException("Approved compiler contract digest does not match");
    }
  }

  @Override
  public Uni<StageExecutionResult> execute(String runId, String expectedStageId) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(expectedStageId, "expectedStageId");
    return Uni.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(runId);
              LOG.infof(
                  "stage entered: runId=%s, stageId=%s, runStatus=%s",
                  runId, expectedStageId, doc.run().status());
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
            })
        .invoke(
            result ->
                LOG.infof(
                    "stage settled: runId=%s, stageId=%s, decision=%s",
                    runId, expectedStageId, result.decision()))
        .onFailure()
        .invoke(
            error ->
                LOG.errorf(error, "stage failed: runId=%s, stageId=%s", runId, expectedStageId));
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
        publishHaltedAttemptOutputs(
            runId,
            doc,
            stage,
            enrichAttributesFromCommittedInputs(
                runId, committed, attributesByRun.getOrDefault(runId, Map.of())));
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
    verifyPinnedSemanticRevision(runId, stage, inputs);
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
        .onFailure()
        .recoverWithItem(
            failure -> {
              LOG.warnf(
                  failure,
                  "capability threw: runId=%s, stageId=%s",
                  runId,
                  stage.stageId());
              recordNonRetryableEscapedFailure(doc, failure);
              return List.<CapabilitySignal>of(new CapabilitySignal.Completed(outcomeOf(failure)));
            })
        .map(signals -> handleCapabilitySignals(runId, stage, signals));
  }

  /**
   * Records a throwable that escaped {@link #execute} as a halt on the run's current stage. Flow
   * calls this so an unexpected error still reaches the outcome matrix instead of aborting the
   * workflow instance and leaving the run RUNNING with no card.
   */
  @Override
  public StageExecutionResult haltOnEscapedFailure(String runId, Throwable failure) {
    Objects.requireNonNull(runId, "runId");
    ProductPipelineRunDocument doc = requireRun(runId);
    recordNonRetryableEscapedFailure(doc, failure);
    return handleOutcome(runId, currentStage(doc), outcomeOf(failure), List.of());
  }

  /**
   * Classifies a throwable that ended a capability without an outcome. A throwable nothing
   * recognized is an internal failure, not a contract the author can satisfy: no wording of the
   * requirements makes it go away. A capability that recognizes its own failure completes with a
   * better message instead of reaching here.
   */
  private static StageOutcome outcomeOf(Throwable failure) {
    if (TransientFailures.isTransient(failure)) {
      return StageOutcome.of(
          StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, failureMessage(failure));
    }
    if (ToolArgumentsFailures.isToolArgumentsFailure(failure)) {
      return StageOutcome.of(
          StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, ToolArgumentsFailures.message(failure));
    }
    if (TransientFailures.isPermanentEnvironment(failure)) {
      return StageOutcome.of(
          StageOutcomeClass.POLICY_FAILURE,
          TransientFailures.ENVIRONMENT_SUMMARY,
          new RecoveryCause(
              RecoveryCauseCode.POLICY_FAILURE,
              List.of(new PlanValidationFinding("TLS", failureMessage(failure), true)),
              ""));
    }
    String message = failure == null ? null : failure.getMessage();
    return StageOutcome.of(
        StageOutcomeClass.INTERNAL_FAILURE,
        message == null || message.isBlank() ? String.valueOf(failure) : message);
  }

  private static String failureMessage(Throwable failure) {
    String message = failure == null ? null : failure.getMessage();
    return message == null || message.isBlank() ? String.valueOf(failure) : message;
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
        if (resolution.failure() != null) {
          yield haltRecoverable(
              doc,
              stage,
              List.of(),
              resolution.failureClass(),
              resolution.failure(),
              List.of(),
              emitted,
              false,
              false,
              null,
              null);
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
        technicalRetriesByStage.remove(stageRetryKey(runId, stage.stageId()));
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
        recordRetryableFailure(doc, outcome.message());
        doc = requireRun(doc.run().runId());
        String key = stageRetryKey(runId, stage.stageId());
        int used = technicalRetriesByStage.getOrDefault(key, 0);
        int max = stage.retry().maxTechnicalRetries();
        long delayMs =
            outcome.retryDelayMs() != null
                ? outcome.retryDelayMs()
                : stage.retry().defaultDelayMs();
        if (used >= max) {
          yield haltRecoverable(
              doc,
              stage,
              List.of(),
              outcome.outcomeClass(),
              outcome.message(),
              outcome.candidates(),
              emitted,
              false,
              true,
              outcome.recoveryCause(),
              Math.max(delayMs, 0L));
        }
        technicalRetriesByStage.put(key, used + 1);
        yield new StageExecutionResult(
            new StageDecision.Retry(stage.stageId(), Duration.ofMillis(Math.max(delayMs, 0L))),
            emitted);
      }
      case VALIDATION_FAILURE, CONTRACT_FAILURE, DOMAIN_FAILURE, INTERNAL_FAILURE -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        String failureMessage =
            outcome.message() == null || outcome.message().isBlank()
                ? outcome.outcomeClass().name()
                : outcome.message();
        yield haltRecoverable(
            doc,
            stage,
            refs,
            outcome.outcomeClass(),
            failureMessage,
            outcome.candidates(),
            emitted,
            true,
            true,
            outcome.recoveryCause(),
            null);
      }
      case POLICY_FAILURE, MISSING_MANDATORY_INPUT -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        yield haltRecoverable(
            doc,
            stage,
            refs,
            outcome.outcomeClass(),
            outcome.message(),
            outcome.candidates(),
            emitted,
            false,
            true,
            outcome.recoveryCause(),
            null);
      }
    };
  }

  private StageExecutionResult haltRecoverable(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      StageOutcomeClass outcomeClass,
      String message,
      List<ArtifactCandidate> candidates,
      List<PipelineSignal> emitted,
      boolean diagnoseOwner,
      boolean producerRepairAllowed,
      RecoveryCause recoveryCause,
      Long retryDelayMs) {
    boolean internal = outcomeClass == StageOutcomeClass.INTERNAL_FAILURE;
    String evidence = evidenceText(outcomeClass, message, doc.run().runId());
    RecoveryCause cause =
        recoveryCause == null ? RecoveryCause.fromHalt(outcomeClass, candidates) : recoveryCause;
    String findings = FailureNarrative.findingsText(candidates);
    if (findings.isBlank()) {
      findings = cause.formattedFindings();
    }
    RunManifest manifest = manifestsByRun.get(doc.run().runId());
    String locale = manifest == null ? "en" : manifest.responseLocale();
    String followUp = followUpText(doc.run().runId());
    String body;
    String gate = internal ? PipelineGates.STAGE_INTERNAL_FAILURE : PipelineGates.STAGE_RETRY;
    List<String> choiceIds = List.of();
    String diagnosedOwner = "";
    putRunAttribute(
        doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, evidence);
    putRunAttribute(
        doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, outcomeClass.name());
    putRunAttribute(
        doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, stage.stageId());
    putRunAttribute(
        doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, findings);
    putRunAttribute(
        doc.run().runId(),
        ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
        cause.causeCode().name());
    putRunAttribute(
        doc.run().runId(),
        ProductPipelineRunSupport.STAGE_ERROR_REQUESTED_FACT_ATTR,
        cause.requestedFact());
    putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
    if (outcomeClass == StageOutcomeClass.POLICY_FAILURE) {
      String diagnostic = findings.isBlank() ? evidence : findings;
      String evidenceWithRun = diagnostic + " (runId=" + doc.run().runId() + ")";
      body =
          message == null || message.isBlank()
              ? TransientFailures.ENVIRONMENT_SUMMARY
              : message;
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_ENVIRONMENT,
          body,
          evidenceWithRun,
          null,
          emitted);
    }
    if (outcomeClass == StageOutcomeClass.INTERNAL_FAILURE
        || cause.causeCode() == RecoveryCauseCode.UNKNOWN_PROPERTY) {
      String summary =
          cause.causeCode() == RecoveryCauseCode.UNKNOWN_PROPERTY
              ? UNKNOWN_PROPERTY_RECOVERY_SUMMARY
              : INTERNAL_RECOVERY_SUMMARY;
      String diagnostic = findings.isBlank() ? evidence : findings;
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_INTERNAL,
          summary,
          terminalRecoveryDetails(diagnostic, evidence, doc.run().runId(), PROGRESS_HALTED),
          null,
          emitted);
    }
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    List<OwnerCandidate> closed = ownerCandidates(profile, stage.stageId());
    String artifactIdentity =
        RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
    RecoveryAttemptKey observingKey =
        recoveryLedger.key(stage.stageId(), cause, artifactIdentity, doc.transitions());
    ProducerOwnedRecovery.Route recovery =
        producerRepairAllowed
            ? ProducerOwnedRecovery.route(
                new ProducerOwnedRecovery.Request(
                    stage.stageId(),
                    outcomeClass,
                    cause,
                    closed,
                    catalogHasBeenWritten(doc.run().runId()),
                    recoveryLedger.repairsUsed(doc.transitions(), observingKey, InputOrigin.TRUSTED),
                    recoveryLedger.limits().maxSemanticRepairs(),
                    Optional.empty()))
            : new ProducerOwnedRecovery.Route(ProducerOwnedRecovery.Action.PARK, "");
    putRunAttribute(
        doc.run().runId(),
        ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
        recovery.producerStageId());
    if (recovery.action() == ProducerOwnedRecovery.Action.REPAIR_CURRENT) {
      String ownerArtifact =
          RecoveryAttemptLedger.inputArtifactIdentity(doc, recovery.producerStageId());
      RecoveryAttemptKey key =
          recoveryLedger.key(
              recovery.producerStageId(), cause, ownerArtifact, doc.transitions());
      if (recoveryLedger.mayRepair(doc.transitions(), key, InputOrigin.TRUSTED)) {
        recordProducerRepairAttempt(
            doc, stage, refs, recoveryLedger.recordRepair(key, ownerArtifact));
        emitted.add(new PipelineSignal.Progress(stage.stageId(), "Repairing rejected output"));
        return new StageExecutionResult(
            new StageDecision.Retry(stage.stageId(), Duration.ZERO), emitted);
      }
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.REOPEN_UPSTREAM
        && canCausalReopen(doc, recovery.producerStageId(), cause, evidence)) {
      emitted.add(
          new PipelineSignal.Progress(
              recovery.producerStageId(), "Reopening producer for rejected input"));
      return new StageExecutionResult(
          new StageDecision.ReopenProducer(stage.stageId(), recovery.producerStageId()),
          emitted);
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.ASK_CLARIFICATION) {
      String ownerArtifact =
          RecoveryAttemptLedger.inputArtifactIdentity(doc, recovery.producerStageId());
      RecoveryAttemptKey clarificationKey =
          recoveryLedger.key(
              recovery.producerStageId(), cause, ownerArtifact, doc.transitions());
      if (!recoveryLedger.mayRepair(doc.transitions(), clarificationKey, InputOrigin.TRUSTED)) {
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REPEATED,
            REPEATED_RECOVERY_SUMMARY,
            terminalRecoveryDetails(evidence, evidence, doc.run().runId(), PROGRESS_NONE),
            null,
            emitted);
      }
      String question =
          failureNarrative
              .askClarification(
                  doc.run().runId(),
                  locale,
                  recovery.requestedFact(),
                  stage.stageId(),
                  evidence)
              .orElse(recovery.requestedFact());
      String prompt =
          PipelineGates.tag(
              PipelineGates.STAGE_CLARIFICATION,
              question
                  + HaltRecoveryGuard.remainingLine(
                      recoveryLedger.remaining(
                          doc.transitions(), clarificationKey, InputOrigin.TRUSTED)));
      List<StageSnapshot> stages =
          refs.isEmpty()
              ? doc.run().stages()
              : markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
      commitStatus(
          doc,
          RunStatus.WAITING_FOR_INPUT,
          StageStatus.WAITING_FOR_INPUT,
          stages,
          prompt,
          ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null));
      emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
      return new StageExecutionResult(
          new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
    }
    if (outcomeClass == StageOutcomeClass.VALIDATION_FAILURE
        || usesStructuredContractRecovery(stage, outcomeClass, cause)) {
      putRunAttribute(
          doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
      return recoverValidationFailure(
          doc, stage, refs, cause, findings, evidence, emitted);
    }
    if (diagnoseOwner) {
      List<OwnerCandidate> diagnosisSet = closed;
      if (internal) {
        diagnosisSet =
            closed.stream()
                .filter(candidate -> !stage.stageId().equals(candidate.stageId()))
                .toList();
      }
      OwnerDiagnosis diagnosis =
          failureNarrative.diagnose(
              doc.run().runId(),
              locale,
              stage.stageId(),
              outcomeClass,
              evidence,
              findings,
              diagnosisSet,
              followUp,
              cause);
      body = diagnosis.cardBody(evidence);
      if (diagnosis.ambiguous()) {
        gate = internal ? PipelineGates.STAGE_INTERNAL_FAILURE : PipelineGates.OWNER_CHOICE;
        choiceIds = OwnerCandidateSet.stageIds(diagnosisSet);
        putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
      } else if (diagnosis.owner().isPresent()) {
        // An internal failure keeps its own gate even with an owner: reopening that owner may route
        // around the defect, but re-entering this stage cannot.
        diagnosedOwner = diagnosis.owner().orElseThrow();
        putRunAttribute(
            doc.run().runId(),
            ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
            diagnosedOwner);
        if (internal) {
          gate = PipelineGates.STAGE_INTERNAL_FAILURE;
          choiceIds = List.of(diagnosedOwner);
        } else if (canCausalReopen(doc, diagnosedOwner, cause, evidence)
            || isCurrentUnapprovedOwner(doc, diagnosedOwner)) {
          gate = PipelineGates.STAGE_REVISE;
        } else {
          gate = PipelineGates.STAGE_RETRY;
        }
      } else {
        putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
        gate = PipelineGates.STAGE_INTERNAL_FAILURE;
        choiceIds = List.of();
      }
    } else {
      body =
          failureNarrative
              .narrate(
                  doc.run().runId(),
                  locale,
                  stage.stageId(),
                  outcomeClass,
                  evidence,
                  findings,
                  followUp)
              .orElse(evidence);
    }
    String haltIdentity = ToolCallFingerprints.failureSignature(evidence);
    RecoveryAttemptKey parkKey =
        recoveryLedger.key(stage.stageId(), cause, artifactIdentity, doc.transitions());
    boolean repairsExhausted =
        !internal && !recoveryLedger.mayRepair(doc.transitions(), parkKey, InputOrigin.TRUSTED);
    boolean escalated =
        !internal
            && (repairsExhausted
                || repeatedHaltCount(doc, stage.stageId(), haltIdentity) + 1
                    >= repeatedFailureThreshold);
    if (escalated) {
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_REPEATED,
          REPEATED_RECOVERY_SUMMARY,
          terminalRecoveryDetails(
              findings.isBlank() ? evidence : findings,
              evidence,
              doc.run().runId(),
              PROGRESS_NONE),
          null,
          emitted);
    }
    if (PipelineGates.STAGE_RETRY.equals(gate)) {
      if (outcomeClass == StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE) {
        gate = PipelineGates.RECOVERY_RETRY_TECHNICAL;
      }
    }
    // Both paths strip markers out of the body before tagging, so model-authored text that happens
    // to spell a marker cannot move the wait to a gate the executor did not choose.
    String prompt;
    if (PipelineGates.STAGE_INTERNAL_FAILURE.equals(gate)) {
      prompt = PipelineGates.tagInternalFailure(body, choiceIds);
    } else {
      prompt =
          PipelineGates.OWNER_CHOICE.equals(gate)
              ? PipelineGates.tagOwnerChoice(body, choiceIds)
              : PipelineGates.retag(gate, body);
    }
    if (PipelineGates.isContextualRecoveryGate(gate)) {
      prompt = PipelineGates.tagRecoveryDetails(prompt, evidence, retryDelayMs);
    }
    String durablePrompt = PipelineGates.tagHaltIdentity(prompt, haltIdentity);
    List<StageSnapshot> stages =
        refs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        stages,
        durablePrompt,
        ProductPipelineRunSupport.haltEvidence(
            attributesByRun.get(doc.run().runId()), null));
    emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
    return new StageExecutionResult(
        new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
  }

  private static boolean usesStructuredContractRecovery(
      ProfileStage stage, StageOutcomeClass outcomeClass, RecoveryCause cause) {
    if (stage == null || outcomeClass != StageOutcomeClass.CONTRACT_FAILURE) {
      return false;
    }
    if ("design-planning".equals(stage.stageId())) {
      return true;
    }
    return "design-input".equals(stage.stageId())
        && cause != null
        && cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE;
  }

  private StageExecutionResult recoverValidationFailure(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryCause cause,
      String findings,
      String evidenceText,
      List<PipelineSignal> emitted) {
    String runId = doc.run().runId();
    RunManifest manifest = manifestsByRun.get(runId);
    String locale = manifest == null ? "en" : manifest.responseLocale();
    Revision approvedBriefRevision = artifactStore.latest(runId, Kind.REQUIREMENT_BRIEF).orElse(null);
    Reference approvedBriefRef =
        approvedBriefRevision == null ? null : approvedBriefRevision.reference();
    Revision approvedSemanticRevision =
        artifactStore.latest(runId, Kind.CHAIN_SEMANTIC_REVISION).orElse(null);
    Reference approvedSemanticRef =
        approvedSemanticRevision == null ? null : approvedSemanticRevision.reference();
    List<Reference> rejectedRefs = rejectedArtifactRefs(runId, refs);
    String failureId = UUID.randomUUID().toString();
    if (rejectedRefs.isEmpty() && "design-input".equals(stage.stageId())) {
      rejectedRefs =
          List.of(new Reference(Kind.CHAIN_SEMANTIC_REVISION, failureId, "rejected-capture"));
    }
    if (rejectedRefs.isEmpty() && "design-planning".equals(stage.stageId())) {
      rejectedRefs =
          List.of(new Reference(Kind.DESIGN_PLAN_REPORT, failureId, "rejected-plan"));
    }
    List<SemanticFinding> semanticFindings =
        semanticFindingsForRejectedGraph(runId, rejectedRefs, stage.stageId(), failureId, findings, evidenceText);
    RecoveryEvidence draftEvidence =
        new RecoveryEvidence(
            1,
            failureId,
            cause.causeCode().name(),
            stage.stageId(),
            approvedBriefRef,
            approvedSemanticRef,
            rejectedRefs,
            semanticFindings,
            null,
            List.of());
    RequirementBrief approvedBrief =
        approvedBriefRevision == null
            ? null
            : artifactStore.payload(approvedBriefRevision, RequirementBrief.class);
    Object rejectedPayload = rejectedArtifact(runId, rejectedRefs);
    RecoveryContext context =
        new RecoveryContext(
            draftEvidence, approvedBrief, rejectedPayload, locale);
    AcceptedRecovery acceptedRecovery = acceptedRecoveryDecision(runId, context);
    RecoveryEvidence recoveryEvidence = acceptedRecovery.evidence();
    RecoveryDecision accepted = acceptedRecovery.decision();
    if (accepted == null) {
      if ("design-input".equals(stage.stageId()) || "design-planning".equals(stage.stageId())) {
        String question = findings.isBlank() ? evidenceText : findings;
        Reference captureFault =
            recoveryEvidence.rejectedArtifactRefs().isEmpty()
                ? null
                : recoveryEvidence.rejectedArtifactRefs().getFirst();
        accepted =
            new RecoveryDecision(
                RecoveryCauseClass.DERIVATION_DEFECT,
                captureFault,
                List.of(recoveryEvidence.failureId()),
                RecoveryAction.ASK_USER,
                List.of(),
                question,
                question);
      } else {
        accepted =
            new RecoveryDecision(
                RecoveryCauseClass.UNCLASSIFIED,
                null,
                List.of(recoveryEvidence.failureId()),
                RecoveryAction.PARK,
                List.of(),
                "",
                findings.isBlank() ? evidenceText : findings);
      }
    }

    boolean identicalRejection = false;
    List<Reference> priorAttemptRefs = List.of();
    if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT) {
      String briefIdentity = briefRevisionIdentity(recoveryEvidence);
      RecoveryAttemptKey key =
          recoveryLedger.key(stage.stageId(), cause, briefIdentity, doc.transitions());
      identicalRejection =
          contextualRegenerationAttempted(doc)
              || recoveryLedger.repairsUsed(doc.transitions(), key, InputOrigin.TRUSTED) > 0;
      if (!identicalRejection) {
        String observingIdentity =
            RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
        RecoveryAttemptKey observingKey =
            recoveryLedger.key(stage.stageId(), cause, observingIdentity, doc.transitions());
        identicalRejection =
            !recoveryLedger.mayRepair(doc.transitions(), observingKey, InputOrigin.TRUSTED);
      }
      if (identicalRejection) {
        priorAttemptRefs = priorRecoveryEvidenceRefs(runId);
      }
    }
    RecoveryEvidence recoveryEvidenceWithPrior =
        priorAttemptRefs.isEmpty()
            ? recoveryEvidence
            : new RecoveryEvidence(
                recoveryEvidence.schemaVersion(),
                recoveryEvidence.failureId(),
                recoveryEvidence.observedCauseCode(),
                recoveryEvidence.observingStageId(),
                recoveryEvidence.approvedBriefRef(),
                recoveryEvidence.approvedSemanticRef(),
                recoveryEvidence.rejectedArtifactRefs(),
                recoveryEvidence.findings(),
                recoveryEvidence.technicalFailure(),
                priorAttemptRefs);
    List<Reference> evidenceInputs = new ArrayList<>();
    if (approvedBriefRef != null) {
      evidenceInputs.add(approvedBriefRef);
    }
    if (approvedSemanticRef != null) {
      evidenceInputs.add(approvedSemanticRef);
    }
    rejectedRefs.stream()
        .filter(ref -> !evidenceInputs.contains(ref))
        .filter(ref -> artifactStore.get(runId, ref).isPresent())
        .forEach(evidenceInputs::add);
    priorAttemptRefs.stream()
        .filter(ref -> !evidenceInputs.contains(ref))
        .filter(ref -> artifactStore.get(runId, ref).isPresent())
        .forEach(evidenceInputs::add);
    Revision storedEvidence =
        artifactStore.append(
            new AppendCommand(
                runId,
                Kind.RECOVERY_EVIDENCE,
                "1",
                "product-pipeline-runtime",
                "1",
                recoveryEvidenceWithPrior,
                evidenceInputs,
                null,
                provenance(runId, stage.stageId(), stage.capabilityId())));
    putRunAttribute(
        runId,
        ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
        storedEvidence.contentHash());

    StageDecision mapped =
        RecoveryExecutor.execute(
            accepted,
            recoveryEvidenceWithPrior,
            doc,
            stage,
            catalogHasBeenWritten(runId),
            identicalRejection);
    if (identicalRejection) {
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_REPEATED,
          REPEATED_RECOVERY_SUMMARY,
          terminalRecoveryDetails(
              findings.isBlank() ? evidenceText : findings,
              evidenceText,
              runId,
              PROGRESS_NONE),
          null,
          emitted);
    }
    if (mapped instanceof StageDecision.ReopenProducer reopen) {
      if (accepted.action() == RecoveryAction.REVISE_BRIEF) {
        putRunAttributeObject(
            runId,
            ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR,
            accepted.proposedBriefChanges());
        putRunAttribute(
            runId,
            ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
            reopen.producerStageId());
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REVISE_BRIEF,
            recoveryWaitBody(accepted, findings, evidenceText),
            evidenceText,
            null,
            emitted);
      }
      if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT
          && "design-planning".equals(reopen.producerStageId())) {
        putRunAttribute(
            runId,
            ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
            reopen.producerStageId());
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REBUILD_PLAN,
            recoveryWaitBody(accepted, findings, evidenceText),
            evidenceText,
            null,
            emitted);
      }
      if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT) {
        recordRegenerateAttempt(doc, stage, refs, recoveryEvidenceWithPrior, cause);
      }
      emitted.add(
          new PipelineSignal.Progress(
              reopen.producerStageId(), "Reopening producer from recovery evidence"));
      return new StageExecutionResult(mapped, emitted);
    }
    if (mapped instanceof StageDecision.Retry) {
      if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT) {
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REGENERATE_EXECUTION,
            recoveryWaitBody(accepted, findings, evidenceText),
            evidenceText,
            null,
            emitted);
      }
      emitted.add(new PipelineSignal.Progress(stage.stageId(), "Retrying from recovery evidence"));
      return new StageExecutionResult(mapped, emitted);
    }

    if (accepted.action() == RecoveryAction.PARK) {
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_UNCLASSIFIED,
          UNCLASSIFIED_RECOVERY_SUMMARY,
          terminalRecoveryDetails(
              findings.isBlank() ? evidenceText : findings,
              evidenceText,
              runId,
              PROGRESS_HALTED),
          null,
          emitted);
    }

    StageDecision.WaitForInput wait = (StageDecision.WaitForInput) mapped;
    List<StageSnapshot> stages =
        refs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        stages,
        wait.prompt(),
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(runId), null));
    emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), wait.prompt()));
    return new StageExecutionResult(wait, emitted);
  }

  private static String recoveryWaitBody(
      RecoveryDecision accepted, String findings, String evidenceText) {
    String body = accepted == null ? "" : accepted.userSummary();
    if (body != null && !body.isBlank()) {
      return body;
    }
    return findings == null || findings.isBlank() ? evidenceText : findings;
  }

  private static String terminalRecoveryDetails(
      String diagnostic, String evidence, String runId, String progress) {
    String body = diagnostic == null || diagnostic.isBlank() ? evidence : diagnostic;
    if (body == null) {
      body = "";
    }
    String identity = ToolCallFingerprints.failureSignature(evidence);
    StringBuilder details = new StringBuilder(body);
    if (runId != null && !runId.isBlank() && !details.toString().contains("runId=")) {
      details.append(" (runId=").append(runId).append(")");
    }
    if (identity != null && !identity.isBlank()) {
      details.append(" identity=").append(identity);
    }
    if (progress != null && !progress.isBlank()) {
      details.append(" progress=").append(progress);
    }
    return details.toString();
  }

  private StageExecutionResult waitContextualRecovery(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      String gate,
      String body,
      String evidenceText,
      Long retryDelayMs,
      List<PipelineSignal> emitted) {
    String prompt =
        PipelineGates.tagRecoveryDetails(
            PipelineGates.retag(gate, body), evidenceText, retryDelayMs);
    String durablePrompt =
        PipelineGates.tagHaltIdentity(
            prompt, ToolCallFingerprints.failureSignature(evidenceText));
    List<StageSnapshot> stages =
        refs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        stages,
        durablePrompt,
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null));
    emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
    return new StageExecutionResult(
        new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
  }

  private record AcceptedRecovery(RecoveryDecision decision, RecoveryEvidence evidence) {}

  private AcceptedRecovery acceptedRecoveryDecision(String runId, RecoveryContext context) {
    Optional<RecoveryDecision> first = failureNarrative.recover(runId, context);
    RecoveryDecisionValidator.Result firstValidation =
        first
            .map(decision -> RecoveryDecisionValidator.validate(decision, context))
            .orElseGet(
                () ->
                    new RecoveryDecisionValidator.Result(
                        false, List.of("Missing recovery decision.")));
    if (first.isPresent() && firstValidation.accepted()) {
      return new AcceptedRecovery(first.orElseThrow(), context.evidence());
    }

    RecoveryEvidence afterFirst =
        evidenceWithDecisionFindings(context.evidence(), firstValidation.findings());
    RecoveryContext correctedContext =
        new RecoveryContext(
            afterFirst,
            context.approvedBrief(),
            context.rejectedArtifact(),
            context.responseLocale());
    Optional<RecoveryDecision> second = failureNarrative.recover(runId, correctedContext);
    if (second.isEmpty()) {
      return new AcceptedRecovery(null, first.isEmpty() ? context.evidence() : afterFirst);
    }
    RecoveryDecisionValidator.Result secondValidation =
        RecoveryDecisionValidator.validate(second.orElseThrow(), correctedContext);
    if (secondValidation.accepted()) {
      return new AcceptedRecovery(second.orElseThrow(), afterFirst);
    }
    RecoveryEvidence afterBoth =
        evidenceWithDecisionFindings(afterFirst, secondValidation.findings());
    return new AcceptedRecovery(null, afterBoth);
  }

  private RecoveryValidationDeps recoveryValidationDeps() {
    RecoveryValidationDeps deps = recoveryValidationDeps;
    if (deps == null) {
      synchronized (this) {
        deps = recoveryValidationDeps;
        if (deps == null) {
          deps = new RecoveryValidationDeps();
          recoveryValidationDeps = deps;
        }
      }
    }
    return deps;
  }

  private List<SemanticFinding> semanticFindingsForRejectedGraph(
      String runId,
      List<Reference> rejectedRefs,
      String observingStageId,
      String failureId,
      String findings,
      String evidenceText) {
    ChainPlanGraph graph = resolveRejectedGraph(runId, rejectedRefs);
    if (graph != null) {
      RecoveryValidationDeps deps = recoveryValidationDeps();
      List<SemanticFinding> graphFindings =
          RecoveryEvidenceFactory.findingsFromChainPlanGraph(
              graph,
              observingStageId,
              deps.objectMapper,
              deps.schemaRefResolver,
              deps.schemaService,
              deps.schemaResourceLoader);
      if (!graphFindings.isEmpty()) {
        return graphFindings;
      }
    }
    return List.of(fallbackSemanticFinding(failureId, findings, evidenceText));
  }

  private static SemanticFinding fallbackSemanticFinding(
      String failureId, String findings, String evidenceText) {
    String prose = findings.isBlank() ? evidenceText : findings;
    return new SemanticFinding(
        "UNCLASSIFIED",
        "",
        failureId + "-finding-1",
        "",
        "",
        List.of(),
        List.of(),
        List.of(),
        "",
        Map.of(),
        List.of(),
        prose);
  }

  private ChainPlanGraph resolveRejectedGraph(String runId, List<Reference> rejectedRefs) {
    if (rejectedRefs != null) {
      for (Reference ref : rejectedRefs) {
        if (ref == null || ref.kind() != Kind.CHAIN_PLAN_GRAPH) {
          continue;
        }
        Optional<Revision> revision = artifactStore.get(runId, ref);
        if (revision.isPresent()) {
          return artifactStore.payload(revision.orElseThrow(), ChainPlanGraph.class);
        }
      }
    }
    return artifactStore
        .latest(runId, Kind.CHAIN_PLAN_GRAPH)
        .map(revision -> artifactStore.payload(revision, ChainPlanGraph.class))
        .orElse(null);
  }

  private static RecoveryEvidence evidenceWithDecisionFindings(
      RecoveryEvidence evidence, List<String> validationFindings) {
    List<SemanticFinding> findings = new ArrayList<>(evidence.findings());
    long priorInvalid =
        findings.stream().filter(finding -> "INVALID_RECOVERY_DECISION".equals(finding.code())).count();
    findings.add(
        new SemanticFinding(
            "INVALID_RECOVERY_DECISION",
            "RecoveryDecisionValidator",
            evidence.failureId() + "-invalid-decision-" + (priorInvalid + 1),
            "",
            "",
            List.of(),
            List.of(),
            List.of(),
            "",
            Map.of(),
            List.of(),
            String.join("\n", validationFindings)));
    return new RecoveryEvidence(
        evidence.schemaVersion(),
        evidence.failureId(),
        evidence.observedCauseCode(),
        evidence.observingStageId(),
        evidence.approvedBriefRef(),
        evidence.approvedSemanticRef(),
        evidence.rejectedArtifactRefs(),
        findings,
        evidence.technicalFailure(),
        evidence.priorAttemptRefs());
  }

  private List<Reference> rejectedArtifactRefs(String runId, List<Reference> refs) {
    if (refs != null && !refs.isEmpty()) {
      return List.copyOf(refs);
    }
    for (Kind kind : List.of(Kind.CHAIN_PLAN_GRAPH, Kind.IMPLEMENTATION_PLAN)) {
      Optional<Reference> latest = artifactStore.latest(runId, kind).map(Revision::reference);
      if (latest.isPresent()) {
        return List.of(latest.orElseThrow());
      }
    }
    return List.of();
  }

  private Object rejectedArtifact(String runId, List<Reference> refs) {
    for (Reference ref : refs) {
      Optional<Revision> revision = artifactStore.get(runId, ref);
      if (revision.isPresent()) {
        return artifactStore.payload(revision.orElseThrow(), Object.class);
      }
    }
    return Map.of();
  }

  private static long repeatedHaltCount(
      ProductPipelineRunDocument doc, String stageId, String haltIdentity) {
    return doc.attempts().stream()
        .filter(attempt -> stageId.equals(attempt.stageId()))
        .filter(attempt -> attempt.outcome() == StageStatus.WAITING_FOR_INPUT)
        .filter(attempt -> attempt.failureEvidence() != null)
        .filter(
            attempt ->
                doc.transitions().stream()
                    .filter(transition -> transition.toRevision() == attempt.runRevision())
                    .map(RunTransition::reason)
                    .map(PipelineGates::haltIdentityOf)
                    .flatMap(Optional::stream)
                    .anyMatch(haltIdentity::equals))
        .count();
  }

  /**
   * Structured evidence for the halt. An internal failure carries the run identifier, so the
   * narrative turn has it to quote and the raw-evidence fallback still names it when that turn
   * fails. The runtime supplies the field; the sentence around it stays the model's to write.
   */
  private static String evidenceText(
      StageOutcomeClass outcomeClass, String message, String runId) {
    String text = message == null || message.isBlank() ? outcomeClass.name() : message;
    if (outcomeClass != StageOutcomeClass.INTERNAL_FAILURE || runId == null || runId.isBlank()) {
      return text;
    }
    return text + " (runId=" + runId + ")";
  }

  private String followUpText(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return "";
    }
    Object value = attributes.get(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR);
    return value instanceof String text ? text : "";
  }

  private static List<OwnerCandidate> ownerCandidates(
      ProductPipelineProfile profile, String failedStageId) {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(profile, failedStageId);
    List<OwnerCandidate> deeper = OwnerCandidateSet.deepen(profile, first);
    return deeper.size() > first.size() ? deeper : first;
  }

  private boolean catalogHasBeenWritten(String runId) {
    return artifactStore.latest(runId, Kind.MATERIALIZATION_RESULT).isPresent()
        || artifactStore.latest(runId, Kind.CATALOG_CHAIN_SNAPSHOT).isPresent();
  }

  private void recordRegenerateAttempt(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryEvidence evidence,
      RecoveryCause cause) {
    String briefIdentity = briefRevisionIdentity(evidence);
    RecoveryAttemptKey key =
        recoveryLedger.key(stage.stageId(), cause, briefIdentity, doc.transitions());
    recordProducerRepairAttempt(
        doc, stage, refs, recoveryLedger.recordRepair(key, briefIdentity));
  }

  private static String briefRevisionIdentity(RecoveryEvidence evidence) {
    if (evidence == null || evidence.approvedBriefRef() == null) {
      return "";
    }
    return evidence.approvedBriefRef().contentHash();
  }

  private static boolean contextualRegenerationAttempted(ProductPipelineRunDocument doc) {
    List<RunTransition> transitions = doc.transitions();
    for (int index = transitions.size() - 1; index > 0; index--) {
      String reason = transitions.get(index).reason();
      if (reason == null) {
        continue;
      }
      String previousGate =
          PipelineGates.gateOf(transitions.get(index - 1).reason()).orElse("");
      if (PipelineGates.RECOVERY_REGENERATE_EXECUTION.equals(previousGate)
          && reason.startsWith(PRODUCER_REPAIR_REASON_PREFIX)) {
        return true;
      }
      if (PipelineGates.RECOVERY_REBUILD_PLAN.equals(previousGate)
          && (reason.startsWith(PRODUCER_REPAIR_REASON_PREFIX)
              || reason.startsWith(RecoveryAttemptLedger.AUTHOR_REOPEN_REASON_PREFIX))) {
        return true;
      }
    }
    return false;
  }

  private List<Reference> priorRecoveryEvidenceRefs(String runId) {
    return artifactStore.history(runId, Kind.RECOVERY_EVIDENCE).stream()
        .map(Revision::reference)
        .toList();
  }

  private void recordProducerRepairAttempt(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      String reason) {
    List<StageSnapshot> repairing =
        refs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, stage.stageId(), refs, StageStatus.RUNNING);
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        repairing,
        reason,
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null),
        StageStatus.FAILED);
  }

  private boolean canCausalReopen(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause, String evidence) {
    if (owner == null || owner.isBlank() || owner.equals(doc.run().currentStageId())) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    boolean approved =
        doc.run().stages().stream()
            .filter(stage -> owner.equals(stage.stageId()))
            .findFirst()
            .map(
                stage ->
                    stage.approvedArtifactId() != null && !stage.approvedArtifactId().isBlank())
            .orElse(false);
    if (!approved) {
      return false;
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    return recoveryLedger.mayReopen(
        doc.transitions(),
        key,
        InputOrigin.TRUSTED,
        RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC,
        ToolCallFingerprints.failureSignature(evidence));
  }

  private static boolean isCurrentUnapprovedOwner(ProductPipelineRunDocument doc, String owner) {
    if (owner == null || owner.isBlank() || !owner.equals(doc.run().currentStageId())) {
      return false;
    }
    return doc.run().stages().stream()
        .filter(stage -> owner.equals(stage.stageId()))
        .findFirst()
        .map(stage -> stage.approvedArtifactId() == null || stage.approvedArtifactId().isBlank())
        .orElse(false);
  }

  private void putRunAttribute(String runId, String key, String value) {
    attributesByRun
        .computeIfAbsent(runId, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(key, value == null ? "" : value);
  }

  private void putRunAttributeObject(String runId, String key, Object value) {
    if (value == null) {
      return;
    }
    attributesByRun
        .computeIfAbsent(runId, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(key, value);
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
      } else if (ref.kind() == Kind.IDS_DOCUMENT && !attributes.containsKey("idsDocument")) {
        attributes.put("idsDocument", artifactStore.payload(revision.get(), IdsDocument.class));
      } else if (ref.kind() == Kind.CHAIN_SEMANTIC_REVISION
          && !attributes.containsKey("chainSemanticRevision")) {
        attributes.put(
            "chainSemanticRevision",
            artifactStore.payload(revision.get(), ChainSemanticRevision.class));
      }
    }
    attributesByRun.put(runId, attributes);
    return attributes;
  }

  /**
   * Restart and live execute both re-check the approved semantic pin with the 2-arg verifier. The
   * 3-arg form would load a newer contract and reject a valid restart.
   */
  private void verifyPinnedSemanticRevision(
      String runId, ProfileStage stage, List<Reference> inputs) {
    boolean consumesSemantic =
        stage.consumes().stream()
            .anyMatch(ref -> ref != null && "chain-semantic-revision".equals(ref.type()));
    if (!consumesSemantic) {
      return;
    }
    ChainSemanticRevision live = null;
    ApprovalRecordV2 semanticApproval = null;
    for (Reference ref : inputs) {
      if (ref == null || ref.kind() == null) {
        continue;
      }
      if (ref.kind() == Kind.CHAIN_SEMANTIC_REVISION) {
        live =
            artifactStore
                .get(runId, ref)
                .map(stored -> artifactStore.payload(stored, ChainSemanticRevision.class))
                .orElse(null);
      }
      if (ref.kind() == Kind.APPROVAL_RECORD) {
        Optional<Revision> stored = artifactStore.get(runId, ref);
        if (stored.isEmpty() || !"2".equals(stored.get().schemaVersion())) {
          continue;
        }
        ApprovalRecordV2 approval =
            artifactStore.payload(stored.get(), ApprovalRecordV2.class);
        if (approval != null
            && Kind.CHAIN_SEMANTIC_REVISION.name().equals(approval.subjectArtifactKind())) {
          semanticApproval = approval;
        }
      }
    }
    if (live != null && semanticApproval != null) {
      verifyApproval(semanticApproval, live);
    }
  }

  /**
   * Hands a repair turn the artifacts its halted attempt produced, so the retry sees the plan or
   * the graph its complaint is about and not only the complaint. {@link #haltRecoverable} already
   * writes those refs onto the stage's own journal snapshot, which a restart restores, so this
   * reads them back rather than keeping a second copy.
   *
   * <p>The refs travel in one run attribute and never in {@code committed}, so
   * {@link #resolveDeclaredInputs} and {@link #findCommitted} cannot see them, no downstream stage
   * can satisfy a declared input with them, and the halted stage stays unapproved. The attribute is
   * rewritten on every execution and removed when there is nothing to publish, so one stage's
   * halted output cannot follow the run forward into the next stage.
   */
  private Map<String, Object> publishHaltedAttemptOutputs(
      String runId,
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      Map<String, Object> attributes) {
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(doc, stage.stageId(), attributes);
    List<Reference> refs =
        StageRepairEvidence.haltRecorded(attributes)
            ? haltedAttemptOutputs(doc, stage.stageId())
            : List.of();
    if (refs.isEmpty()) {
      attributes.remove(StageRepairEvidence.PRIOR_OUTPUT_REFS_ATTR);
    } else {
      attributes.put(StageRepairEvidence.PRIOR_OUTPUT_REFS_ATTR, refs);
    }
    attributesByRun.put(runId, attributes);
    return attributes;
  }

  /**
   * The outputs a stage recorded on an attempt that never committed them. A succeeded or approved
   * snapshot yields nothing: those refs are committed work, reachable through
   * {@link #committedInputs} like any other input. After a causal reopen the live snapshot is
   * cleared; the journal still holds the rejected attempt, and a repair turn of that same stage
   * reads those refs as evidence.
   */
  private static List<Reference> haltedAttemptOutputs(
      ProductPipelineRunDocument doc, String stageId) {
    List<Reference> fromSnapshot =
        doc.run().stages().stream()
            .filter(snapshot -> snapshot.stageId().equals(stageId))
            .filter(snapshot -> snapshot.status() != StageStatus.SUCCEEDED)
            .filter(snapshot -> snapshot.approvedArtifactId() == null)
            .findFirst()
            .map(StageSnapshot::outputRefs)
            .orElse(List.of());
    if (!fromSnapshot.isEmpty()) {
      return fromSnapshot;
    }
    return lastRejectedAttemptOutputs(doc, stageId);
  }

  private static List<Reference> lastRejectedAttemptOutputs(
      ProductPipelineRunDocument doc, String stageId) {
    return doc.attempts().stream()
        .filter(attempt -> stageId.equals(attempt.stageId()))
        .filter(attempt -> attempt.outcome() != StageStatus.SUCCEEDED)
        .filter(attempt -> attempt.outputs() != null && !attempt.outputs().isEmpty())
        .reduce((first, second) -> second)
        .map(StageAttempt::outputs)
        .orElse(List.of());
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
    return skip.evaluate(new SkipPolicy.SkipEvaluationContext(draft));
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
      // The capability protocol, not the model reply: no author input changes how many signals a
      // capability emits.
      return StageOutcome.of(
          StageOutcomeClass.INTERNAL_FAILURE,
          "capability must emit exactly one Completed signal, got " + completed.size());
    }
    return completed.get(0);
  }

  private CandidateResolution resolveCandidateResolution(
      ProfileStage stage, List<ArtifactCandidate> candidates) {
    if (stage.approval() == null) {
      return CandidateResolution.contractFailure("candidate outcome requires approval policy");
    }
    if (candidates == null || candidates.isEmpty()) {
      return CandidateResolution.contractFailure("candidate outcome emitted no artifacts");
    }
    List<ArtifactTypeRef> allowedTypes = candidateResolutionTypes(stage);
    List<ResolvedCandidate> resolved = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      if (candidate == null || candidate.kind() == null) {
        return CandidateResolution.contractFailure("candidate artifact kind is required");
      }
      List<ArtifactTypeRef> matches =
          allowedTypes.stream().filter(typeRef -> typeRef.matches(candidate.kind())).toList();
      // The three mismatches below are between the capability and the profile that declared its
      // candidate set. Neither is anything the author wrote, so they halt as internal failures.
      if (matches.isEmpty()) {
        return CandidateResolution.internalFailure(
            "unknown candidate kind " + candidate.kind().name());
      }
      if (matches.size() > 1) {
        return CandidateResolution.internalFailure(
            "duplicate candidate-set declarations for kind " + candidate.kind().name());
      }
      ArtifactTypeRef resolvedType = matches.get(0);
      if (resolvedType.schemaVersion() <= 0) {
        return CandidateResolution.internalFailure(
            "undeclared schema version for kind " + candidate.kind().name());
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
        return CandidateResolution.contractFailure(
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
      return CandidateResolution.contractFailure(
          "approval target kind "
              + stage.approval().artifact().type()
              + " must occur exactly once, but occurred "
              + approvableCount);
    }
    return CandidateResolution.resolved(resolved);
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

  private static String artifactEnvelopeSchema(Kind kind, ArtifactTypeRef typeRef) {
    if (kind == Kind.CHAIN_SEMANTIC_REVISION) {
      return ChainSemanticRevision.CURRENT_SCHEMA_VERSION;
    }
    return String.valueOf(typeRef.schemaVersion());
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
                  artifactEnvelopeSchema(kind, candidate.typeRef()),
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
    commitStatus(doc, nextStatus, stageStatus, stages, reason, failureEvidence, stageStatus);
  }

  /**
   * Persists an attempt outcome independently from the current stage snapshot. A retried
   * capability failure leaves the run and stage runnable but must still be visible in the journal.
   */
  private void commitStatus(
      ProductPipelineRunDocument doc,
      RunStatus nextStatus,
      StageStatus stageStatus,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence,
      StageStatus attemptOutcome) {
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
                attemptOutcome,
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

  private void recordRetryableFailure(ProductPipelineRunDocument doc, String evidence) {
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        doc.run().stages(),
        evidence == null || evidence.isBlank() ? "retryable technical failure" : evidence,
        evidence,
        StageStatus.FAILED);
  }

  /**
   * Failed streams that are not technical retries still need one durable failure attempt before
   * their halt card. Technical failures use {@link #recordRetryableFailure} in the outcome matrix
   * so they are never counted twice.
   */
  private void recordNonRetryableEscapedFailure(ProductPipelineRunDocument doc, Throwable failure) {
    if (TransientFailures.isTransient(failure) || ToolArgumentsFailures.isToolArgumentsFailure(failure)) {
      return;
    }
    String evidence = failureMessage(failure);
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        doc.run().stages(),
        evidence,
        ProductPipelineRunSupport.nonTechnicalFailureEvidence(evidence),
        StageStatus.FAILED);
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
    if (isBriefRepairApproval(runId, stageId)) {
      return ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT;
    }
    RunManifest manifest = manifestsByRun.get(runId);
    String responseLocale = manifest == null ? "en" : manifest.responseLocale();
    return approvalPrompts.stageApprovalPrompt(stageId, responseLocale, languageReferenceFor(runId));
  }

  private boolean isBriefRepairApproval(String runId, String stageId) {
    if (stageId == null || !stageId.contains("analysis")) {
      return false;
    }
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return false;
    }
    Object error = attributes.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
    return error instanceof String text && !text.isBlank();
  }

  private String languageReferenceFor(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return "";
    }
    Object brief = attributes.get("requirementBrief");
    if (brief instanceof RequirementBrief requirementBrief) {
      return MappingGapWait.languageReference(requirementBrief);
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

  /**
   * Either the candidates a CANDIDATE outcome resolved to, or the failure that stopped them
   * resolving together with the class it halts under. {@code failureClass} is null exactly when
   * {@code failure} is.
   */
  private record CandidateResolution(
      List<ResolvedCandidate> resolvedCandidates,
      StageOutcomeClass failureClass,
      String failure) {

    private CandidateResolution {
      resolvedCandidates =
          resolvedCandidates == null ? List.of() : List.copyOf(resolvedCandidates);
    }

    static CandidateResolution resolved(List<ResolvedCandidate> candidates) {
      return new CandidateResolution(candidates, null, null);
    }

    /** The capability produced a candidate set the stage contract rejects. */
    static CandidateResolution contractFailure(String failure) {
      return new CandidateResolution(List.of(), StageOutcomeClass.CONTRACT_FAILURE, failure);
    }

    /** The capability and the profile disagree about what this stage produces. */
    static CandidateResolution internalFailure(String failure) {
      return new CandidateResolution(List.of(), StageOutcomeClass.INTERNAL_FAILURE, failure);
    }
  }
}
