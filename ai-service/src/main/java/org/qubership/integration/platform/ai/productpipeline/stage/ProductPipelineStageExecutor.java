package org.qubership.integration.platform.ai.productpipeline.stage;

import io.smallrye.mutiny.Uni;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonPointer;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
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
import org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative.RecoveryResult;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative.RecoveryStatus;
import org.qubership.integration.platform.ai.productpipeline.create.MappingRepairCaptureValidator;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidate;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerDiagnosis;
import org.qubership.integration.platform.ai.productpipeline.create.ProducerOwnedRecovery;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
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
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptKey;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry;
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
  private org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;
  private RequirementDraftStore requirementDrafts;
  private ProductPipelineRunSupport clarificationSource;
  private org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore captureFeedback;

  public void bindClarificationSource(ProductPipelineRunSupport source) {
    this.clarificationSource = source;
  }

  public void withNativeRequirementCapture(RequirementDraftStore drafts,
      org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore feedback) {
    this.requirementDrafts = drafts;
    this.captureFeedback = feedback;
  }

  public void withConversations(
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.conversations = conversations;
  }

  private org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl recoveryControl;

  public void withRecoveryControl(
      org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl control) {
    this.recoveryControl = control;
  }

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
  private final RecoveryOutcomeTelemetry recoveryTelemetry;
  private final MappingRepairCaptureValidator mappingRepairCaptureValidator;
  private final ObjectMapper objectMapper;
  private final SchemaResourceLoader schemaResourceLoader;
  private final SchemaRefResolver schemaRefResolver;
  private final DeterministicElementSchemaService schemaService;
  private ProviderRetrySleeper providerRetrySleeper = Thread::sleep;

  @FunctionalInterface
  interface ProviderRetrySleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  void withProviderRetrySleeper(ProviderRetrySleeper sleeper) {
    this.providerRetrySleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public static final int MAX_SEMANTIC_REPAIRS = 2;
  public static final String PRODUCER_REPAIR_REASON_PREFIX = "producer-repair:";

  static final String INTERNAL_RECOVERY_SUMMARY =
      "Creation is paused because the service could not complete this step. Your inputs and completed work are saved.";
  static final String REPEATED_RECOVERY_SUMMARY =
      "Automatic repair is paused because the same rejection remains. Your inputs and completed work are saved.";
  static final String UNCLASSIFIED_RECOVERY_SUMMARY =
      "Creation is paused because no safe correction could be established. Your inputs and completed work are saved.";
  static final String MISSING_GRAPH_PATCH_SUMMARY =
      "The service has not produced a validated chain graph. Your inputs and completed work are saved.";
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
      ApprovalPrompts approvalPrompts,
      FailureNarrative failureNarrative,
      int repeatedFailureThreshold,
      RecoveryAttemptLedger recoveryLedger,
      RecoveryOutcomeTelemetry recoveryTelemetry,
      MappingRepairCaptureValidator mappingRepairCaptureValidator,
      ObjectMapper objectMapper,
      SchemaResourceLoader schemaResourceLoader,
      SchemaRefResolver schemaRefResolver,
      DeterministicElementSchemaService schemaService) {
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
    this.recoveryTelemetry =
        recoveryTelemetry == null ? new RecoveryOutcomeTelemetry() : recoveryTelemetry;
    this.mappingRepairCaptureValidator = mappingRepairCaptureValidator;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.schemaResourceLoader = Objects.requireNonNull(schemaResourceLoader, "schemaResourceLoader");
    this.schemaRefResolver = Objects.requireNonNull(schemaRefResolver, "schemaRefResolver");
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
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
              if (doc.run().status() != RunStatus.RUNNING) {
                return Uni.createFrom()
                    .item(replaySettledDecision(doc, expectedStageId, expected));
              }
              if (expected.status() == StageStatus.SUCCEEDED) {
                return Uni.createFrom()
                    .item(
                        new StageExecutionResult(
                            new StageDecision.Continue(expectedStageId), List.of()));
              }
              return executeRunningStage(runId, doc);
            })
        .map(result -> enforceResourcePause(runId, result))
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

  private org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget llmBudget;

  public void withLlmBudget(org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget budget) {
    this.llmBudget = budget;
  }

  private StageExecutionResult enforceResourcePause(String runId, StageExecutionResult result) {
    String refusal = llmBudget == null ? null : llmBudget.state(runId).refusal();
    if (refusal == null || result.decision() instanceof StageDecision.Complete
        || result.decision() instanceof StageDecision.Fail) { return result; }
    if (result.decision() instanceof StageDecision.WaitForInput wait
        && PipelineGates.strip(wait.prompt()).startsWith(refusal)) { return result; }
    ProductPipelineRunDocument doc = requireRun(runId);
    var signals = result.signals().stream().filter(signal -> signal instanceof PipelineSignal.SkillProgress
        || signal instanceof PipelineSignal.Progress || signal instanceof PipelineSignal.Message).toList();
    return handleOutcome(runId, currentStage(doc),
        StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, refusal), signals);
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
    if (llmBudget != null) { llmBudget.linkConversation(runId, doc.run().conversationId()); }
    ProductPipelineProfile profile = profilesByRun.get(runId);
    ProfileStage stage = currentStage(doc);
    if (llmBudget != null && llmBudget.state(runId).refusal() != null) {
      settlePendingRecoveryAttempt(runId, stage, llmBudget.state(runId).requests());
      return Uni.createFrom().item(handleOutcome(runId, stage,
          StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, "Model resources require another allowance."),
          List.of()));
    }
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
                    executeSkip(runId, stage, attributes, committed);
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
    if ("requirement-analysis".equals(stage.capabilityId())) {
      var latest = doc.attempts().stream().filter(attempt -> stage.stageId().equals(attempt.stageId()))
          .reduce((first, second) -> second);
      if (latest.filter(attempt -> attempt.outcome() == StageStatus.RUNNING && attempt.finishedAt() == null).isPresent()) {
        return Uni.createFrom().item(handleOutcome(runId, stage,
            StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                "The previous analysis attempt was interrupted. Resume against its selected draft."), List.of()));
      }
    }
    String attemptId = UUID.randomUUID().toString();
    if ("requirement-analysis".equals(stage.capabilityId())) {
      long revision = doc.run().runRevision();
      runStore.commit(revision, new org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit(
          runId, revision, RunStatus.RUNNING, stage.stageId(), doc.run().stages(),
          new StageAttempt(attemptId, stage.stageId(), revision + 1, StageStatus.RUNNING,
              clock.instant(), null, inputs, null),
          new RunTransition(revision, revision + 1, RunStatus.RUNNING, RunStatus.RUNNING,
              stage.stageId(), clock.instant(), "analysis attempt started")));
    }
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
    if (recoveryControl != null) recoveryControl.stageStarted(context);
    putRunAttribute(runId, ProductPipelineRunSupport.RECOVERY_INVOCATION_ATTR,
        recoveryJson(new ProducerInvocation(attemptId, stage.stageId(), inputs, nativeRequirementBaseline(doc, stage))));
    long requestsBefore = llmBudget == null ? -1L : llmBudget.state(runId).requests();
    return io.smallrye.mutiny.Multi.createFrom().deferred(() -> capability.execute(context))
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
              recordNonRetryableEscapedFailure(requireRun(runId), failure);
              return List.<CapabilitySignal>of(new CapabilitySignal.Completed(outcomeOf(failure)));
            })
        .map(signals -> {
          settlePendingRecoveryAttempt(runId, stage, requestsBefore);
          return handleCapabilitySignals(runId, stage, signals);
        });
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
    return enforceResourcePause(runId, handleOutcome(runId, currentStage(doc), outcomeOf(failure), List.of()));
  }

  /**
   * Classifies a throwable that ended a capability without an outcome. A throwable nothing
   * recognized is an internal failure, not a contract the author can satisfy: no wording of the
   * requirements makes it go away. A capability that recognizes its own failure completes with a
   * better message instead of reaching here.
   */
  private static StageOutcome outcomeOf(Throwable failure) {
    var capture = StageOutcome.captureFailure(failure);
    if (capture.isPresent()) return capture.get();
    if (org.qubership.integration.platform.ai.llm.ratelimit.LlmRequestBudget.isOverflow(failure)) {
      return StageOutcome.technicalFailure(failure);
    }
    if (TransientFailures.isTransient(failure)) {
      return StageOutcome.of(
          StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, failureMessage(failure));
    }
    if (ToolArgumentsFailures.isToolArgumentsFailure(failure)) {
      return StageOutcome.of(
          StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, ToolArgumentsFailures.message(failure),
          new RecoveryCause(RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED,
              List.of(new PlanValidationFinding("INVALID_TOOL_ARGUMENTS",
                  ToolArgumentsFailures.DIAGNOSTIC, true)), ""));
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
    StageOutcome outcome = requireSingleCompleted(signals);
    String activeSkill = "";
    String failedSkill = "";
    for (CapabilitySignal signal : signals) {
      if (signal instanceof CapabilitySignal.SkillProgress progress) {
        if ("running".equals(progress.status())) {
          activeSkill = progress.skillId();
          failedSkill = "";
        } else if (activeSkill.equals(progress.skillId())) {
          failedSkill = "error".equals(progress.status()) ? progress.skillId() : "";
        }
      }
    }
    if (outcome.outcomeClass() == StageOutcomeClass.SUCCEEDED
        || outcome.outcomeClass() == StageOutcomeClass.CANDIDATE) {
      failedSkill = "";
    }
    putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR, failedSkill);
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
    return handleOutcome(runId, stage, outcome, live);
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
      String runId, ProfileStage stage, Map<String, Object> attributes, List<Reference> committed) {
    RequirementDraft draft =
        attributes.get("approvedDraft") instanceof RequirementDraft requirementDraft
            ? requirementDraft
            : null;
    List<Reference> inputs = committed.stream().filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT)
        .reduce((first, second) -> second).map(List::of).orElse(List.of());
    if (draft == null || inputs.isEmpty()) {
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
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, inputs)),
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
    String resourceRefusal = llmBudget == null ? null : llmBudget.state(runId).refusal();
    if (resourceRefusal != null) {
      String reason = resourceRefusal;
      List<Reference> refs = appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
      return resourcePause(doc, stage, refs, reason, emitted);
    }
    return switch (outcome.outcomeClass()) {
      case NEEDS_INPUT -> {
        if (outcome.recoveryCause() != null && java.util.Set.of(
            RecoveryCauseCode.CONTRACT_SHAPE, RecoveryCauseCode.GRAPH_STRUCTURE,
            RecoveryCauseCode.UNKNOWN_PROPERTY, RecoveryCauseCode.MISSING_REQUIRED_PROPERTY,
            RecoveryCauseCode.MAPPING_CONTRACT, RecoveryCauseCode.MISSING_BRIEF_FACTS, RecoveryCauseCode.BRIEF_CONTRACT)
            .contains(outcome.recoveryCause().causeCode())) {
          yield haltRecoverable(doc, stage,
              appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates())),
              StageOutcomeClass.CONTRACT_FAILURE, outcome.message(), outcome.candidates(), emitted,
              true, true, outcome.recoveryCause(), null);
        }
        Map<String, Object> settled = new java.util.HashMap<>(attributesByRun.getOrDefault(runId, Map.of()));
        boolean readyRepair = "requirement-discovery".equals(stage.capabilityId())
            && ProductPipelineRunSupport.settleReadyDiscoveryRepair(stage.stageId(), outcome, settled);
        commitStatus(
            doc,
            RunStatus.WAITING_FOR_INPUT,
            StageStatus.WAITING_FOR_INPUT,
            doc.run().stages(),
            outcome.message(),
            readyRepair ? ProductPipelineRunSupport.haltEvidence(settled, null, outcome.message()) : null);
        if (readyRepair) {
          attributesByRun.put(runId, settled);
        }
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
        StageExecutionResult rejectedRepair =
            rejectInvalidMappingRepair(doc, stage, resolution.resolvedCandidates(), emitted);
        if (rejectedRepair != null) {
          yield rejectedRepair;
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
            boolean preparing = ProductPipelineRunSupport.preparingReplacement(
                profilesByRun.get(runId), attributesByRun.get(runId), stage.stageId());
            updated.add(
                new StageSnapshot(
                    stage.stageId(),
                    StageStatus.WAITING_FOR_APPROVAL,
                    preparing ? snapshot.outputRefs() : refs,
                    preparing ? snapshot.approvedArtifactId() : null,
                    refs,
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
        StageExecutionResult rejectedRepair = rejectInvalidMappingRepair(
            doc, stage, resolveProducedCandidates(stage, outcome.candidates()), emitted);
        if (rejectedRepair != null) yield rejectedRepair;
        technicalRetriesByStage.remove(stageRetryKey(runId, stage.stageId()));
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        List<StageSnapshot> updated =
            markStageOutputs(doc, stage.stageId(), refs, StageStatus.SUCCEEDED);
        ProductPipelineProfile profile = profilesByRun.get(runId);
        if (profile.terminal().stageId().equals(stage.stageId())) {
          RunStatus terminalStatus = terminalStatus(profile);
          commitStatus(doc, terminalStatus, StageStatus.SUCCEEDED, updated, outcome.message());
          if (terminalStatus == RunStatus.CHAIN_MATERIALIZED) {
            recoveryTelemetry.recordSuccess(runId);
          }
          emitted.add(new PipelineSignal.Completed(terminalStatus));
          yield new StageExecutionResult(
              new StageDecision.Complete(stage.stageId(), terminalStatus), emitted);
        }
        commitStatus(doc, RunStatus.RUNNING, StageStatus.SUCCEEDED, updated, outcome.message());
        yield new StageExecutionResult(new StageDecision.Continue(stage.stageId()), emitted);
      }
      case RETRYABLE_TECHNICAL_FAILURE -> {
        String key = stageRetryKey(runId, stage.stageId());
        int used = ProductPipelineRunSupport.consecutiveTechnicalRetries(
            runId, doc.attempts(), doc.transitions()).getOrDefault(key, 0);
        recordRetryableFailure(doc, outcome.message());
        doc = requireRun(doc.run().runId());
        int max = stage.retry().maxTechnicalRetries();
        if (outcome.recoveryCause() != null && outcome.recoveryCause().findings().stream()
            .anyMatch(finding -> "LLM_CONTEXT_OVERFLOW".equals(finding.code()))) {
          max = 1;
        }
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
            new StageDecision.Retry(stage.stageId(), Duration.ofMillis(Math.max(delayMs, 0L)), true),
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

  private StageExecutionResult rejectInvalidMappingRepair(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<ResolvedCandidate> candidates,
      List<PipelineSignal> emitted) {
    if (mappingRepairCaptureValidator == null || candidates == null) {
      return null;
    }
    RequirementBrief brief = requirementBriefCandidate(candidates);
    if (brief == null) {
      return null;
    }
    Map<String, Object> attributes = attributesByRun.getOrDefault(doc.run().runId(), Map.of());
    Object evidenceRef = attributes.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR);
    String evidenceHash = evidenceRef instanceof String text ? text : "";
    var mappingEvidence = mappingRepairEvidenceRevision(doc.run().runId());
    if (mappingEvidence.isPresent()) evidenceHash = mappingEvidence.get().contentHash();
    if (evidenceHash.isBlank() && isActiveMappingRepair(attributes)) {
      return haltRecoverable(
          doc,
          stage,
          List.of(),
          StageOutcomeClass.VALIDATION_FAILURE,
          "Mapping repair evidence is unavailable.",
          List.of(),
          emitted,
          true,
          true,
          RecoveryCause.mappingContract(List.of()),
          null);
    }
    MappingRepairCaptureValidator.Result validation =
        mappingRepairCaptureValidator.validate(
            doc.run().runId(),
            doc.run().conversationId(),
            evidenceHash,
            brief);
    if (validation.status() != MappingRepairCaptureValidator.Result.Status.BLOCKED
        && validation.status() != MappingRepairCaptureValidator.Result.Status.UNRESOLVED) {
      return null;
    }
    return haltRecoverable(
        doc,
        stage,
        List.of(),
        StageOutcomeClass.VALIDATION_FAILURE,
        validation.message(),
        List.of(),
        emitted,
        true,
        true,
        RecoveryCause.mappingContract(validation.findings()),
        null);
  }

  private static boolean isActiveMappingRepair(Map<String, Object> attributes) {
    Object cause = attributes.get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR);
    return RecoveryCauseCode.MAPPING_CONTRACT
        .name()
        .equals(cause instanceof String text ? text : "");
  }

  private static RequirementBrief requirementBriefCandidate(List<ResolvedCandidate> candidates) {
    for (ResolvedCandidate resolved : candidates) {
      ArtifactCandidate candidate = resolved == null ? null : resolved.candidate();
      if (candidate != null
          && candidate.kind() == Kind.REQUIREMENT_BRIEF
          && candidate.payload() instanceof RequirementBrief brief) {
        return brief;
      }
      if (candidate != null && candidate.kind() == Kind.REQUIREMENT_DRAFT
          && candidate.payload() instanceof RequirementDraft draft) {
        return org.qubership.integration.platform.ai.plan.RequirementBriefProjector.project(
            new RequirementBrief("", List.of(), List.of(), List.of(), List.of(), "")
                .withFacts(draft.facts()).withFlow(draft.flow()).withCatalogBindings(draft.catalogBindings())
                .withMappingIntents(org.qubership.integration.platform.ai.plan.RequirementBriefProjector.authoredMappings(draft)));
      }
    }
    return null;
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
    RecoveryCause budgetCause = recoveryBudgetCause(cause, evidence);
    String mappingBoundaries = cause.mappingBoundaries();
    if (!mappingBoundaries.isBlank()) {
      evidence += "\nMapping boundaries:\n" + mappingBoundaries;
    }
    String findings = FailureNarrative.findingsText(candidates);
    if (findings.isBlank()) {
      findings = cause.formattedFindings();
    }
    RunManifest manifest = manifestsByRun.get(doc.run().runId());
    String locale = manifest == null ? "en" : manifest.responseLocale();
    String followUp = followUpText(doc.run().runId());
    String body;
    String gate = PipelineGates.STAGE_RETRY;
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
    putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
        ProductPipelineRunSupport.recoveryCauseJson(budgetCause));
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
    if (outcomeClass == StageOutcomeClass.INTERNAL_FAILURE) {
      String diagnostic = findings.isBlank() ? evidence : findings;
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_INTERNAL,
          INTERNAL_RECOVERY_SUMMARY,
          terminalRecoveryDetails(diagnostic, evidence, doc.run().runId(), PROGRESS_HALTED),
          null,
          emitted);
    }
    Optional<ReportedDraftDefect> reportedDraftOwner = producerRepairAllowed
        ? reportedDraftOwner(doc, stage, refs, cause) : Optional.empty();
    if (reportedDraftOwner.isEmpty() && usesStructuredContractRecovery(stage, outcomeClass, cause)) {
      putRunAttribute(
          doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
      return recoverValidationFailure(
          doc, stage, refs, outcomeClass, cause, findings, evidence, emitted, producerRepairAllowed);
    }
    if (!nativeCaptureRepairAllowed(doc, stage, budgetCause)) {
      return waitContextualRecovery(doc, stage, refs, PipelineGates.RECOVERY_REPEATED,
          "The capture validator has not permitted another correction. Progress is saved.", evidence, null, emitted);
    }
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    List<OwnerCandidate> closed =
        mappingOwnerCandidates(
            cause, profile, stage.stageId(), ownerCandidates(profile, stage.stageId()));
    Optional<String> consumedBriefProducer =
        compiledBriefProducerStageId(doc.run().runId(), cause);
    String artifactIdentity =
        RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
    RecoveryAttemptKey observingKey =
        recoveryLedger.key(stage.stageId(), budgetCause, artifactIdentity, doc.transitions());
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
                    Optional.empty(),
                    consumedBriefProducer, ProducerOwnedRecovery.requiresPolicyConsultation(
                        committedBrief(doc), cause)))
            : new ProducerOwnedRecovery.Route(ProducerOwnedRecovery.Action.PARK, "");
    Optional<Revision> mappingDraft = mappingDraftRevision(doc.run().runId(), cause);
    if (producerRepairAllowed && mappingDraft.isPresent()) {
      String owner = requirementDraftOwner(doc.run().runId(), profile, mappingDraft.get()).orElse("");
      if (!owner.isBlank()) {
        recovery = new ProducerOwnedRecovery.Route(
            catalogHasBeenWritten(doc.run().runId()) ? ProducerOwnedRecovery.Action.PARK
                : owner.equals(stage.stageId()) ? ProducerOwnedRecovery.Action.REPAIR_CURRENT
                    : ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, owner);
      }
    }
    if (producerRepairAllowed && reportedDraftOwner.isPresent()) {
      var defect = reportedDraftOwner.get();
      recovery = new ProducerOwnedRecovery.Route(
          catalogHasBeenWritten(doc.run().runId()) ? ProducerOwnedRecovery.Action.PARK
              : ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, defect.owner());
      RecoveryCause draftCause = new RecoveryCause(cause.causeCode(), defect.findings(), cause.requestedFact(), cause.noProgress(), cause.captureFailure());
      putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
          ProductPipelineRunSupport.recoveryCauseJson(draftCause));
      putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR,
          draftCause.formattedFindings());
    }
    putRunAttribute(
        doc.run().runId(),
        ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
        recovery.producerStageId());
    if (!cause.findings().isEmpty()
        && (cause.causeCode() == RecoveryCauseCode.MAPPING_CONTRACT || reportedDraftOwner.isPresent())) {
      persistRequirementContractEvidence(doc, stage, refs, cause, recovery.producerStageId());
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.REPAIR_CURRENT) {
      String ownerArtifact =
          RecoveryAttemptLedger.inputArtifactIdentity(doc, recovery.producerStageId());
      RecoveryAttemptKey key =
          recoveryLedger.key(
              recovery.producerStageId(), budgetCause, ownerArtifact, doc.transitions());
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
    if (recovery.action() == ProducerOwnedRecovery.Action.REOPEN_UPSTREAM
        && "materialization".equals(stage.stageId())
        && "design-execution".equals(recovery.producerStageId())
        && cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE
        && isReopenableOwner(doc, recovery.producerStageId(), cause)
        && !catalogHasBeenWritten(doc.run().runId())
        && recoveryLedger.mayRepair(doc.transitions(), recoveryLedger.key(recovery.producerStageId(), budgetCause,
            RecoveryAttemptLedger.inputArtifactIdentity(doc, recovery.producerStageId()), doc.transitions()), InputOrigin.TRUSTED)) {
      return waitContextualRecovery(doc, stage, refs, PipelineGates.RECOVERY_REGENERATE_EXECUTION,
          "The generated chain still failed validation before publication. Retry creation regenerates it from"
              + " the approved plan. Your requirements and plan are saved.",
          terminalRecoveryDetails(findings, evidence, doc.run().runId(), PROGRESS_NONE), null, emitted);
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.REOPEN_UPSTREAM
        && cause.isBindingIdentityMismatch()) {
      return waitGuardedRecovery(
          doc,
          stage,
          refs,
          diagnoseAutomaticReopenRefusal(doc, recovery.producerStageId(), cause, evidence),
          cause,
          evidence,
          emitted);
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.PARK
        && cause.isBindingIdentityMismatch()) {
      HaltRecoveryGuard guard =
          catalogHasBeenWritten(doc.run().runId())
              ? HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN
              : diagnoseAutomaticReopenRefusal(
                  doc, recovery.producerStageId(), cause, evidence);
      return waitGuardedRecovery(doc, stage, refs, guard, cause, evidence, emitted);
    }
    if (offersManualBriefEdit(doc, stage, recovery, cause, evidence)) {
      return waitEditableBriefRecovery(
          doc, stage, refs, cause, evidence, recovery.producerStageId(), emitted);
    }
    if (cause.causeCode() == RecoveryCauseCode.MAPPING_CONTRACT) {
      return waitGuardedRecovery(
          doc,
          stage,
          refs,
          diagnoseAutomaticReopenRefusal(doc, recovery.producerStageId(), cause, evidence),
          cause,
          evidence,
          emitted);
    }
    if (recovery.action() == ProducerOwnedRecovery.Action.ASK_CLARIFICATION
        && cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      return recoverValidationFailure(doc, stage, refs, outcomeClass, cause, findings, evidence, emitted, producerRepairAllowed);
    }
    if (outcomeClass == StageOutcomeClass.VALIDATION_FAILURE
        && cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      putRunAttribute(
          doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
      return recoverValidationFailure(
          doc, stage, refs, outcomeClass, cause, findings, evidence, emitted, producerRepairAllowed);
    }
    if (diagnoseOwner) {
      OwnerDiagnosis diagnosis =
          failureNarrative.diagnose(
              doc.run().runId(),
              locale,
              stage.stageId(),
              outcomeClass,
              evidence,
              findings,
              closed,
              followUp,
              cause);
      body = diagnosis.cardBody(evidence);
      if (diagnosis.ambiguous() || diagnosis.owner().isEmpty()) {
        putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "");
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_UNCLASSIFIED,
            unclassifiedRecoverySummary(
                findings.isBlank() ? evidence : findings),
            terminalRecoveryDetails(
                findings.isBlank() ? evidence : findings,
                evidence,
                doc.run().runId(),
                PROGRESS_HALTED),
            null,
            emitted);
      }
      diagnosedOwner = diagnosis.owner().orElseThrow();
      putRunAttribute(
          doc.run().runId(),
          ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
          diagnosedOwner);
      if (canCausalReopen(doc, diagnosedOwner, cause, evidence)
          || isCurrentUnapprovedOwner(doc, diagnosedOwner)) {
        gate = PipelineGates.STAGE_REVISE;
      } else {
        gate = PipelineGates.STAGE_RETRY;
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
        recoveryLedger.key(stage.stageId(), budgetCause, artifactIdentity, doc.transitions());
    boolean repairsExhausted =
        !internal && !recoveryLedger.mayRepair(doc.transitions(), parkKey, InputOrigin.TRUSTED);
    boolean escalated =
        !internal
            && (repairsExhausted
                || repeatedHaltCount(doc, stage.stageId(), haltIdentity) + 1
                    >= repeatedFailureThreshold);
    if (escalated) {
      if (offersManualBriefEdit(doc, stage, cause)) {
        return waitEditableBriefRecovery(
            doc, stage, refs, cause, evidence, briefOwnerStageId(doc, stage), emitted);
      }
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
    if (PipelineGates.STAGE_RETRY.equals(gate)
        && outcomeClass == StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE) {
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_RETRY_TECHNICAL,
          body,
          evidence,
          retryDelayMs,
          emitted);
    }
    // Strip markers out of the body before tagging, so model-authored text that happens to spell a
    // marker cannot move the wait to a gate the executor did not choose.
    String prompt = PipelineGates.retag(gate, body);
    if (PipelineGates.isContextualRecoveryGate(gate)) {
      prompt = PipelineGates.tagRecoveryDetails(prompt, evidence, retryDelayMs);
    }
    String durablePrompt = PipelineGates.tagHaltIdentity(prompt, haltIdentity);
    List<StageSnapshot> stages =
        markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
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
    if (cause != null && cause.findings().stream()
        .anyMatch(finding -> "MISSING_REQUIRED_SCRIPT_LITERAL".equals(finding.code()))) return true;
    if (stage != null && cause != null && cause.causeCode() == RecoveryCauseCode.SECURITY_POLICY
        && (outcomeClass == StageOutcomeClass.VALIDATION_FAILURE || outcomeClass == StageOutcomeClass.CONTRACT_FAILURE)) {
      return true;
    }
    if (stage == null || outcomeClass != StageOutcomeClass.CONTRACT_FAILURE) {
      return false;
    }
    if (cause != null && (cause.causeCode() == RecoveryCauseCode.MISSING_BRIEF_FACTS
        || cause.causeCode() == RecoveryCauseCode.BRIEF_CONTRACT)
        && "design-input".equals(stage.stageId())) {
      return true;
    }
    if ("design-planning".equals(stage.stageId())) {
      return true;
    }
    return ("design-input".equals(stage.stageId()) || "design-execution".equals(stage.stageId())
        || "requirement-discovery".equals(stage.stageId()) || "requirement-analysis".equals(stage.stageId()))
        && cause != null
        && cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE;
  }

  private static RecoveryDecision captureRegenerateDecision(
      RecoveryEvidence evidence, String summary) {
    Reference fault =
        evidence.rejectedArtifactRefs().isEmpty()
            ? null
            : evidence.rejectedArtifactRefs().getFirst();
    String body = summary == null ? "" : summary;
    return new RecoveryDecision(
        RecoveryCauseClass.DERIVATION_DEFECT,
        fault,
        List.of(evidence.failureId()),
        RecoveryAction.REGENERATE_ARTIFACT,
        List.of(),
        "",
        body);
  }

  static String unclassifiedRecoverySummary(String details) {
    if (details == null || details.isBlank()) {
      return UNCLASSIFIED_RECOVERY_SUMMARY;
    }
    String upper = details.toUpperCase(Locale.ROOT);
    String lower = details.toLowerCase(Locale.ROOT);
    if (upper.contains("GRAPH_PATCH_ARTIFACT") && lower.contains("missing producer")) {
      return MISSING_GRAPH_PATCH_SUMMARY;
    }
    if (lower.contains("skill did not complete")
        && (lower.contains("did not capture a graph patch")
            || lower.contains("did not capture a script body repair patch")
            || upper.contains("GRAPH_PATCH"))) {
      return MISSING_GRAPH_PATCH_SUMMARY;
    }
    return UNCLASSIFIED_RECOVERY_SUMMARY;
  }

  private StageExecutionResult recoverValidationFailure(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      StageOutcomeClass outcomeClass,
      RecoveryCause cause,
      String findings,
      String evidenceText,
      List<PipelineSignal> emitted,
      boolean producerRepairAllowed) {
    String runId = doc.run().runId();
    if (catalogHasBeenWritten(runId)) {
      return waitGuardedRecovery(doc, stage, refs, HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN,
          cause, evidenceText, emitted);
    }
    RecoveryCause budgetCause = recoveryBudgetCause(cause, evidenceText);
    RunManifest manifest = manifestsByRun.get(runId);
    String locale = manifest == null ? "en" : manifest.responseLocale();
    Revision approvedBriefRevision = committedArtifactRevision(doc, Kind.REQUIREMENT_BRIEF);
    Reference approvedBriefRef =
        approvedBriefRevision == null ? null : approvedBriefRevision.reference();
    Revision approvedSemanticRevision = committedArtifactRevision(doc, Kind.CHAIN_SEMANTIC_REVISION);
    Reference approvedSemanticRef =
        approvedSemanticRevision == null ? null : approvedSemanticRevision.reference();
    List<Reference> rejectedRefs = rejectedArtifactRefs(refs);
    ProducerInvocation invocation = producerInvocation(runId);
    if (cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE && invocation != null) {
      var nativeFindings = cause.findings();
      var rejectedInputs = invocation.inputs().stream()
          .filter(ref -> nativeFindings.stream().anyMatch(finding ->
              ref.artifactId().equals(finding.originRecordId())))
          .filter(ref -> artifactStore.get(runId, ref).isPresent()).toList();
      if (!rejectedInputs.isEmpty()) {
        rejectedRefs = rejectedInputs;
      }
    }
    String failureId = CanonicalPayloadHash.sha256Hex(Map.of(
        "invocation", invocation == null ? "" : new ProducerInvocationIdentity(
            invocation.attemptId(), invocation.stageId(), invocation.inputs()),
        "stage", stage.stageId(), "rejected", rejectedRefs,
        "inputs", RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId()),
        "defect", recoveryLedger.key(stage.stageId(), budgetCause,
            RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId()), doc.transitions()).evidenceIdentity()));
    if (rejectedRefs.isEmpty() && cause.causeCode() != RecoveryCauseCode.BRIEF_CONTRACT
        && "design-input".equals(stage.stageId())) {
      rejectedRefs =
          List.of(new Reference(Kind.CHAIN_SEMANTIC_REVISION, failureId, "rejected-capture"));
    }
    if (rejectedRefs.isEmpty() && "design-planning".equals(stage.stageId())) {
      rejectedRefs =
          List.of(new Reference(Kind.DESIGN_PLAN_REPORT, failureId, "rejected-plan"));
    }
    if (rejectedRefs.isEmpty() && cause.causeCode() != RecoveryCauseCode.BRIEF_CONTRACT) {
      Kind rejectedKind = switch (stage.stageId()) {
        case "requirement-discovery" -> Kind.REQUIREMENT_DRAFT;
        case "requirement-analysis" -> Kind.REQUIREMENT_BRIEF;
        default -> Kind.CHAIN_PLAN_GRAPH;
      };
      rejectedRefs = List.of(new Reference(rejectedKind, failureId, "rejected-output"));
    }
    List<SemanticFinding> semanticFindings =
        semanticFindingsFor(
            runId, rejectedRefs, stage.stageId(), failureId, findings, evidenceText, cause);
    List<RepairHistory.Attempt> priorAttempts = repairHistory(runId, stage.stageId(),
        invocation == null ? List.of() : invocation.inputs(), authorInputs(doc).stream().map(UserInput::inputId).toList());
    List<Reference> priorAttemptRefs = priorAttempts.stream().map(RepairHistory.Attempt::evidenceRef).toList();
    RecoveryEvidence draftEvidence =
        new RecoveryEvidence(
            1,
            failureId,
            cause.causeCode().name(),
            stage.stageId(),
            invocation != null && stage.stageId().equals(invocation.stageId()) ? invocation.stageId() : "",
            approvedBriefRef,
            approvedSemanticRef,
            rejectedRefs,
            semanticFindings,
            null,
            priorAttemptRefs, List.of(),
            invocation == null ? List.of() : invocation.inputs(),
            authorInputs(doc).stream().map(UserInput::inputId).toList(),
            isCurrentCleanRebuild(runId, stage.stageId()), cause.captureFailure());
    RequirementBrief approvedBrief =
        approvedBriefRevision == null
            ? null
            : artifactStore.payload(approvedBriefRevision, RequirementBrief.class);
    Object rejectedPayload = rejectedArtifact(runId, rejectedRefs);
    Revision approvedPlanRevision = committedArtifactRevision(doc, Kind.IMPLEMENTATION_PLAN);
    RecoveryContext context =
        new RecoveryContext(
            draftEvidence, approvedBrief, rejectedPayload, locale, authorInputs(doc),
            approvedPlanRevision == null ? null : approvedPlanRevision.reference(),
            approvedPlanRevision == null ? null : artifactStore.payload(approvedPlanRevision, Object.class), priorAttempts);
    boolean invocationProved = invocation != null && stage.stageId().equals(invocation.stageId());
    boolean pinnedInputsAvailable = invocationProved && invocation.inputs().stream()
        .allMatch(ref -> artifactStore.get(runId, ref).isPresent());
    boolean unresolvedChoice = ProducerOwnedRecovery.requiresPolicyConsultation(approvedBrief, cause);
    boolean rejectedInput = invocationProved && rejectedRefs.stream().anyMatch(invocation.inputs()::contains);
    boolean currentRepairEligible = !rejectedInput && ProducerOwnedRecovery.permitsCurrentOutputRepair(outcomeClass, cause,
        invocationProved, pinnedInputsAvailable, unresolvedChoice, catalogHasBeenWritten(runId));
    String resourceRefusal = llmBudget == null ? null : llmBudget.refusalForNextRequest(runId);
    if (resourceRefusal != null) {
      return resourcePause(doc, stage, refs, resourceRefusal, emitted);
    }
    // A fresh native discovery refusal can spend its first existing correction before advisory diagnosis.
    boolean nativeFirstCorrection = producerRepairAllowed && currentRepairEligible
        && firstNativeDiscoveryCorrection(doc, stage, outcomeClass, cause, budgetCause, invocation, draftEvidence);
    boolean projectionRebuild = cause.captureFailure() != null && cause.captureFailure().feedback() != null
        && cause.captureFailure().feedback().ladderStage() == RequirementRecoveryLadder.Stage.PROJECTION_REBUILD;
    // Input contract checks identify brief defects before capture; rejected output stays with its producer.
    AcceptedRecovery acceptedRecovery = projectionRebuild
        ? new AcceptedRecovery(captureRegenerateDecision(draftEvidence,
            "Capture the requirements again from the author evidence and the semantic baseline."), draftEvidence)
        : nativeFirstCorrection || "design-input".equals(stage.stageId())
            && cause.causeCode() != RecoveryCauseCode.MISSING_BRIEF_FACTS
            && cause.causeCode() != RecoveryCauseCode.BRIEF_CONTRACT
            ? new AcceptedRecovery(
                captureRegenerateDecision(draftEvidence, findings.isBlank() ? evidenceText : findings),
                draftEvidence)
            : acceptedRecoveryDecision(runId, context);
    RecoveryEvidence recoveryEvidence = acceptedRecovery.evidence();
    RecoveryDecision accepted = acceptedRecovery.decision();
    resourceRefusal = llmBudget == null ? null : llmBudget.refusalForNextRequest(runId);
    if (resourceRefusal != null) {
      return resourcePause(doc, stage, refs, resourceRefusal, emitted);
    }
    if (!acceptedRecovery.diagnosisStatus().permitsProducerFallback()) {
      String detail = "Recovery diagnosis: " + acceptedRecovery.diagnosisStatus() + ". "
          + recoveryEvidence.findings().stream().filter(finding -> "INVALID_RECOVERY_DECISION".equals(finding.code()))
              .map(SemanticFinding::rawValidatorJson).collect(java.util.stream.Collectors.joining(" "));
      return waitContextualRecovery(doc, stage, refs,
          acceptedRecovery.diagnosisStatus() == RecoveryStatus.PERMANENT_PROVIDER_FAILURE
              ? PipelineGates.RECOVERY_ENVIRONMENT : PipelineGates.RECOVERY_RETRY_TECHNICAL,
          "The model provider could not complete diagnosis. Progress is saved.",
          evidenceText + "\n" + detail, null, emitted);
    }
    if (acceptedRecovery.sourceQuestion() != null) {
      var question = acceptedRecovery.sourceQuestion();
      cause = new RecoveryCause(RecoveryCauseCode.MISSING_BRIEF_FACTS, List.of(new PlanValidationFinding(
          RecoveryDecisionValidator.SOURCE_RECONCILIATION_REQUIRED,
          accepted.userSummary(), true, MappingValidationDetails.empty(), question.interactionId(),
          question.propertyKey())), question.propertyKey());
      putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, cause.causeCode().name());
      putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
          ProductPipelineRunSupport.recoveryCauseJson(cause));
      putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, cause.formattedFindings());
      putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_REQUESTED_FACT_ATTR, question.propertyKey());
    }
    boolean currentProducerFallback = currentRepairEligible && (accepted == null
        || accepted.action() == RecoveryAction.PARK);
    if (accepted != null && accepted.action() == RecoveryAction.REGENERATE_ARTIFACT
        && accepted.faultArtifactRef() != null && artifactStore.get(runId, accepted.faultArtifactRef()).isEmpty()) {
      currentProducerFallback = currentRepairEligible;
      if (!currentRepairEligible) { accepted = null; }
    }
    if (currentProducerFallback) {
      accepted = captureRegenerateDecision(recoveryEvidence, findings.isBlank() ? evidenceText : findings);
    } else if (accepted == null) {
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
    boolean noProgress = cause.noProgress() || recurringRejectedOutput(draftEvidence, rejectedPayload, priorAttempts);
    boolean targetedCreditSpent = false;
    RecoveryAttemptKey recoveryKey = null;
    if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT
        || accepted.action() == RecoveryAction.REVISE_BRIEF) {
      String briefIdentity = RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
      RecoveryAttemptKey key =
          recoveryLedger.key(stage.stageId(), budgetCause, briefIdentity, doc.transitions());
      recoveryKey = key;
      targetedCreditSpent = !recoveryLedger.mayRepair(doc.transitions(), key, InputOrigin.TRUSTED);
    }
    recoveryEvidence = evidenceForFaultProducer(runId, recoveryEvidence, accepted);
    RecoveryEvidence recoveryEvidenceWithPrior = recoveryEvidence;
    boolean changeStrategy = targetedCreditSpent || noProgress;
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

    if (!nativeCaptureRepairAllowed(doc, stage, budgetCause)) {
      return waitContextualRecovery(doc, stage, refs, PipelineGates.RECOVERY_REPEATED,
          "The capture validator has not permitted another correction. Progress is saved.", evidenceText, null, emitted);
    }

    Reference selectedFault = accepted.faultArtifactRef();
    boolean currentRepairTarget = currentRepairEligible && accepted.action() == RecoveryAction.REGENERATE_ARTIFACT
        && (currentProducerFallback || selectedFault != null && artifactStore.get(runId, selectedFault)
            .filter(candidate -> candidate.provenance() != null
                && stage.stageId().equals(candidate.provenance().stageId())).isPresent());
    if (noProgress && !targetedCreditSpent && currentRepairTarget
        && supportsCleanProducerRebuild(stage.stageId())
        && !recoveryLedger.mayCleanRebuild(doc.transitions(), recoveryKey)) {
      changeStrategy = false;
    }
    StageDecision mapped =
        currentProducerFallback && !changeStrategy
            ? new StageDecision.Retry(stage.stageId(), Duration.ZERO)
            : RecoveryExecutor.execute(
            accepted,
            recoveryEvidenceWithPrior,
            doc,
            stage,
            catalogHasBeenWritten(runId),
            changeStrategy);
    if (changeStrategy
        && currentRepairTarget
        && supportsCleanProducerRebuild(stage.stageId())
        && !catalogHasBeenWritten(runId)
        && recoveryLedger.mayCleanRebuild(doc.transitions(), recoveryKey)) {
      putRunAttribute(
          runId,
          cleanRebuildEvidenceAttribute(stage.stageId()),
          storedEvidence.contentHash());
      recordProducerRepairAttempt(
          doc,
          stage,
          refs,
          recoveryLedger.recordCleanRebuild(
              recoveryKey, RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId())));
      emitted.add(
          new PipelineSignal.Progress(
              stage.stageId(), "Regenerating the rejected output from saved inputs in a clean model turn"));
      StageDecision retry =
          mapped instanceof StageDecision.Retry
              ? mapped
              : new StageDecision.Retry(
                  stage.stageId(),
                  Duration.ofMillis(
                      stage.retry() == null ? 0L : stage.retry().defaultDelayMs()));
      return new StageExecutionResult(retry, emitted);
    }
    if (changeStrategy) {
      if (currentRepairTarget && supportsCleanProducerRebuild(stage.stageId())
          && !recoveryLedger.perRunCeilingReached(doc.transitions())) {
        return waitContextualRecovery(doc, stage, refs, PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
            (noProgress ? "The latest correction repeated rejected output. Progress is saved. Continue repair grants up to two targeted corrections"
                : "Automatic corrections for this defect are spent. Continue repair grants up to two targeted corrections")
                + " and two clean regenerations in this run. Existing request, token, time, and run limits still apply.",
            terminalRecoveryDetails(findings, evidenceText, runId, PROGRESS_NONE)
                + " Targeted corrections spent: " + recoveryLedger.repairsUsed(doc.transitions(), recoveryKey, InputOrigin.TRUSTED)
                + ". Clean regenerations spent: " + recoveryLedger.cleanRebuildsUsed(doc.transitions(), recoveryKey) + "."
                + HaltRecoveryGuard.remainingLine(recoveryLedger.remaining(doc.transitions(), recoveryKey, InputOrigin.TRUSTED)),
            null, emitted);
      }
      if (offersManualBriefEdit(doc, stage, cause)) {
        return waitEditableBriefRecovery(
            doc, stage, refs, cause, evidenceText, briefOwnerStageId(doc, stage), emitted);
      }
      if (supportsCleanProducerRebuild(stage.stageId())) {
        putRunAttribute(
            runId,
            ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
            stage.stageId());
      }
      if ("design-planning".equals(stage.stageId())) {
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REBUILD_PLAN,
            recoveryWaitBody(accepted, findings, evidenceText),
            terminalRecoveryDetails(
                findings.isBlank() ? evidenceText : findings,
                evidenceText,
                runId,
                PROGRESS_NONE),
            null,
            emitted);
      }
      if ("design-input".equals(stage.stageId())) {
        return waitContextualRecovery(
            doc,
            stage,
            refs,
            PipelineGates.RECOVERY_REBUILD_DESIGN,
            recoveryWaitBody(accepted, findings, evidenceText),
            terminalRecoveryDetails(
                findings.isBlank() ? evidenceText : findings,
                evidenceText,
                runId,
                PROGRESS_NONE),
            null,
            emitted);
      }
      return waitGuardedRecovery(doc, stage, refs, HaltRecoveryGuard.MAX_SEMANTIC_REPAIRS,
          budgetCause, evidenceText, emitted);
    }
    if (mapped instanceof StageDecision.ReopenProducer reopen) {
      if (accepted.action() == RecoveryAction.REVISE_BRIEF) {
        boolean sourceCorrection = acceptedRecovery.sourceQuestion() != null || !accepted.proposedBriefChanges().isEmpty()
            && accepted.proposedBriefChanges().stream().allMatch(change -> !change.authorDecisionRequired())
            && context.authorInputs().stream().anyMatch(input -> acceptedRecovery.decision() != null
                && acceptedRecovery.decision().evidenceRefs().contains(input.inputId()));
        if (sourceCorrection) {
          RecoveryCause sourceCause = new RecoveryCause(RecoveryCauseCode.BRIEF_CONTRACT,
              cause.findings(), cause.requestedFact());
          var draftOwner = reportedDraftOwner(doc, stage, refs, sourceCause);
          if (draftOwner.isPresent()) {
            sourceCause = new RecoveryCause(RecoveryCauseCode.BRIEF_CONTRACT,
                draftOwner.get().findings(), cause.requestedFact());
            cause = sourceCause;
            putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, cause.causeCode().name());
            putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
                ProductPipelineRunSupport.recoveryCauseJson(cause));
            putRunAttribute(runId, ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, cause.formattedFindings());
            reopen = new StageDecision.ReopenProducer(stage.stageId(), draftOwner.get().owner(), reopen.authorNote());
          }
        }
        if (cause.causeCode() == RecoveryCauseCode.BRIEF_CONTRACT || acceptedRecovery.sourceQuestion() != null) {
          Revision draft = committedArtifactRevision(doc, Kind.REQUIREMENT_DRAFT);
          if (draft != null && draft.provenance() != null
              && ProductPipelineRunSupport.isCommittedRequirementDraftOwner(doc, draft.provenance().stageId(),
                  profilesByRun.get(runId), artifactStore)) {
            var source = artifactStore.payload(draft, org.qubership.integration.platform.ai.plan.RequirementDraft.class);
            if (acceptedRecovery.sourceQuestion() != null || !org.qubership.integration.platform.ai.plan.RequirementCaptureEditor
                .validateElementOrder(source.flow(), source.facts()).isEmpty()) {
              reopen = new StageDecision.ReopenProducer(stage.stageId(), draft.provenance().stageId(), reopen.authorNote());
            }
          }
        }
        putRunAttributeObject(
            runId,
            ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR,
            accepted.proposedBriefChanges());
        putRunAttribute(
            runId,
            ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR,
            reopen.producerStageId());
        if (sourceCorrection && canCausalReopen(doc, reopen.producerStageId(), cause, evidenceText)) {
          recordRegenerateAttempt(doc, stage, refs, recoveryEvidenceWithPrior, budgetCause, reopen.producerStageId());
          emitted.add(new PipelineSignal.Progress(reopen.producerStageId(), "Restoring requirements from saved author input"));
          return new StageExecutionResult(reopen, emitted);
        }
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
        recordRegenerateAttempt(doc, stage, refs, recoveryEvidenceWithPrior, budgetCause, reopen.producerStageId());
      }
      emitted.add(
          new PipelineSignal.Progress(
              reopen.producerStageId(), "Reopening producer from recovery evidence"));
      return new StageExecutionResult(mapped, emitted);
    }
    if (mapped instanceof StageDecision.Retry) {
      if (accepted.action() == RecoveryAction.REGENERATE_ARTIFACT) {
        recordRegenerateAttempt(doc, stage, refs, recoveryEvidenceWithPrior, budgetCause);
        emitted.add(new PipelineSignal.Progress(stage.stageId(), "Regenerating rejected artifact"));
        return new StageExecutionResult(mapped, emitted);
      }
      emitted.add(new PipelineSignal.Progress(stage.stageId(), "Retrying from recovery evidence"));
      return new StageExecutionResult(mapped, emitted);
    }

    if (accepted.action() == RecoveryAction.PARK) {
      if (offersManualBriefEdit(doc, stage, cause)) {
        return waitEditableBriefRecovery(
            doc, stage, refs, cause, evidenceText, briefOwnerStageId(doc, stage), emitted);
      }
      return waitContextualRecovery(
          doc,
          stage,
          refs,
          PipelineGates.RECOVERY_UNCLASSIFIED,
          unclassifiedRecoverySummary(
              findings.isBlank() ? evidenceText : findings),
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
        markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
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

  private boolean nativeCaptureRepairAllowed(
      ProductPipelineRunDocument doc, ProfileStage stage, RecoveryCause cause) {
    if (cause.captureFailure() == null) return true;
    if (!ProducerOwnedRecovery.permitsNativeCaptureRepair(cause)) return false;
    var key = recoveryLedger.key(stage.stageId(), cause,
        RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId()), doc.transitions());
    // Native capture permits at most one outer correction, even when the stage has other credits.
    return recoveryLedger.repairsUsed(doc.transitions(), key, InputOrigin.TRUSTED)
        + recoveryLedger.cleanRebuildsUsed(doc.transitions(), key) < 1;
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
        markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        stages,
        durablePrompt,
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null, evidenceText));
    emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
    String rawEvidence = stageErrorContext(doc.run().runId());
    if (PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(gate) || rawEvidence == null || rawEvidence.isBlank()) {
      rawEvidence = evidenceText;
    }
    recoveryTelemetry.recordPresented(
        doc.run().runId(),
        gate,
        ToolCallFingerprints.failureSignature(rawEvidence),
        evidenceText);
    return new StageExecutionResult(
        new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
  }

  private record NativeRequirementBaseline(Reference rejectedCapture, Reference feedback) {}

  // Provenance metadata must not change persisted diagnosis identities.
  private record ProducerInvocationIdentity(String attemptId, String stageId, List<Reference> inputs) {}

  private record ProducerInvocation(String attemptId, String stageId, List<Reference> inputs,
      NativeRequirementBaseline requirementBaseline) {}

  private NativeRequirementBaseline nativeRequirementBaseline(ProductPipelineRunDocument doc, ProfileStage stage) {
    if (!"requirement-discovery".equals(stage.capabilityId()) || requirementDrafts == null || captureFeedback == null) {
      return null;
    }
    String chat = doc.run().conversationId();
    var rejection = captureFeedback.lastRequirementRejection(chat).orElse(null);
    boolean hidden = rejection != null && hidesRejectedCandidate(rejection.feedback().ladderStage());
    return new NativeRequirementBaseline(hidden ? null
        : requirementDrafts.rejectedCaptureRevision(chat).map(Revision::reference).orElse(null),
        captureFeedback.lastRequirementFeedbackReference(chat).orElse(null));
  }

  private static boolean hidesRejectedCandidate(RequirementRecoveryLadder.Stage stage) {
    return stage == RequirementRecoveryLadder.Stage.PROJECTION_REBUILD
        || stage == RequirementRecoveryLadder.Stage.ASK_AUTHOR
        || stage == RequirementRecoveryLadder.Stage.INTERNAL_FAILURE;
  }

  private boolean firstNativeDiscoveryCorrection(ProductPipelineRunDocument doc, ProfileStage stage,
      StageOutcomeClass outcome, RecoveryCause cause, RecoveryCause budgetCause, ProducerInvocation invocation,
      RecoveryEvidence evidence) {
    if (!"requirement-discovery".equals(stage.capabilityId()) || outcome != StageOutcomeClass.CONTRACT_FAILURE
        || cause.causeCode() != RecoveryCauseCode.CONTRACT_SHAPE || cause.noProgress()
        || invocation == null || invocation.requirementBaseline() == null
        || requirementDrafts == null || captureFeedback == null || conversations == null || cause.captureFailure() == null) {
      return false;
    }
    if (hidesRejectedCandidate(cause.captureFailure().feedback() == null
        ? null : cause.captureFailure().feedback().ladderStage())) {
      return false;
    }
    if (!noDiagnosisDispatched(doc, evidence.failureId())) return false;
    String inputIdentity = RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
    var key = recoveryLedger.key(stage.stageId(), budgetCause, inputIdentity, doc.transitions());
    if (!recoveryLedger.mayRepair(doc.transitions(), key, InputOrigin.TRUSTED)
        || recoveryLedger.repairsUsed(doc.transitions(), key, InputOrigin.TRUSTED) != 0
        || recoveryLedger.cleanRebuildsUsed(doc.transitions(), key) != 0
        || key.findingKeys().stream().anyMatch(member ->
            recoveryLedger.repairsUsed(doc.transitions(), member, InputOrigin.TRUSTED) != 0
                || recoveryLedger.cleanRebuildsUsed(doc.transitions(), member) != 0)) return false;
    var profile = profilesByRun.get(doc.run().runId());
    var route = ProducerOwnedRecovery.route(new ProducerOwnedRecovery.Request(stage.stageId(), outcome, cause,
        mappingOwnerCandidates(cause, profile, stage.stageId(), ownerCandidates(profile, stage.stageId())),
        catalogHasBeenWritten(doc.run().runId()), 0, recoveryLedger.limits().maxSemanticRepairs(), Optional.empty(),
        compiledBriefProducerStageId(doc.run().runId(), cause), false));
    if (route.action() != ProducerOwnedRecovery.Action.REPAIR_CURRENT || !stage.stageId().equals(route.producerStageId())) {
      return false;
    }
    String chat = doc.run().conversationId();
    var rejection = captureFeedback.lastRequirementRejection(chat).orElse(null);
    if (rejection != null && hidesRejectedCandidate(rejection.feedback().ladderStage())) {
      return false;
    }
    var rejected = requirementDrafts.rejectedCaptureRevision(chat).orElse(null);
    var failure = cause.captureFailure();
    if (rejected == null || rejection == null || !rejected.reference().equals(failure.rejectedCandidate())
        || rejected.reference().equals(invocation.requirementBaseline().rejectedCapture())
        || !rejection.feedback().equals(failure.feedback())) return false;
    var feedback = rejection.feedback();
    if (feedback.failureClass() != org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.CORRECTABLE
        || !feedback.outerAllowed() || feedback.noProgress() || feedback.evidenceReference() == null
        || feedback.evidenceReference().equals(invocation.requirementBaseline().feedback())
        || !captureFeedback.lastRequirementFeedbackReference(chat).filter(feedback.evidenceReference()::equals).isPresent()) return false;
    var savedFeedback = artifactStore.get(rejected.compilationId(), feedback.evidenceReference()).orElse(null);
    if (savedFeedback == null) return false;
    var persisted = artifactStore.payload(savedFeedback,
        org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore.RequirementFeedback.class).rejection();
    if (persisted == null || !feedback.equals(persisted.feedback().withEvidenceReference(savedFeedback.reference()))
        || !persisted.issues().equals(feedback.issues())) return false;
    var attempt = artifactStore.payload(rejected, RequirementDraftStore.RequirementCaptureAttempt.class);
    var authority = requirementDrafts.writeAuthority(chat);
    return !attempt.accepted() && !attempt.issues().isEmpty()
        && Objects.equals(attempt.sourceGeneration(), authority.sourceGeneration())
        && attempt.sourceTurnId() != null
        && attempt.sourceTurnId().equals(authority.sourceTurnId())
        && Objects.equals(attempt.acceptedDraftReference(), authority.base())
        && Objects.equals(attempt.acceptedDraftReference(), failure.acceptedBase())
        && attempt.issues().stream().map(issue ->
            new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
                issue.code(), issue.path(), issue.message(), issue.entityId())).toList().equals(feedback.issues());
  }

  private ProducerInvocation producerInvocation(String runId) {
    Object value = attributesByRun.getOrDefault(runId, Map.of())
        .get(ProductPipelineRunSupport.RECOVERY_INVOCATION_ATTR);
    return value instanceof String json && !json.isBlank() ? readRecoveryJson(json, ProducerInvocation.class) : null;
  }

  record AcceptedRecovery(
      RecoveryDecision decision, RecoveryEvidence evidence, boolean rejectedAuthorQuestion,
      RecoveryStatus diagnosisStatus,
      org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence sourceQuestion,
      Instant retryAt) {
    AcceptedRecovery(RecoveryDecision decision, RecoveryEvidence evidence, boolean rejectedAuthorQuestion,
        RecoveryStatus diagnosisStatus,
        org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence sourceQuestion) {
      this(decision, evidence, rejectedAuthorQuestion, diagnosisStatus, sourceQuestion, null);
    }

    private AcceptedRecovery(RecoveryDecision decision, RecoveryEvidence evidence, boolean rejectedAuthorQuestion,
        RecoveryStatus diagnosisStatus) {
      this(decision, evidence, rejectedAuthorQuestion, diagnosisStatus, null);
    }

    private AcceptedRecovery(RecoveryDecision decision, RecoveryEvidence evidence) {
      this(decision, evidence, false, RecoveryStatus.READY);
    }
  }

  private record DiagnosisRequest(String identity, int calls, AcceptedRecovery result) {}

  private boolean noDiagnosisDispatched(ProductPipelineRunDocument doc, String identity) {
    var states = new ArrayList<Object>();
    states.add(attributesByRun.getOrDefault(doc.run().runId(), Map.of())
        .get(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR));
    doc.attempts().stream().map(StageAttempt::failureEvidence).map(ProductPipelineRunSupport::readHaltEvidence)
        .map(evidence -> evidence.get(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR)).forEach(states::add);
    for (Object state : states) {
      if (state == null || state instanceof String text && text.isBlank()) continue;
      if (!(state instanceof String json)) return false;
      try {
        var request = readRecoveryJson(json, DiagnosisRequest.class);
        if (request == null || request.identity() == null || request.identity().isBlank() || request.calls() < 0
            || request.calls() > 0 && (identity.equals(request.identity()) || request.result() == null
                || request.result().diagnosisStatus() == null || !request.result().diagnosisStatus().permitsProducerFallback())) {
          return false;
        }
      } catch (IllegalStateException invalidState) {
        return false;
      }
    }
    return true;
  }

  private DiagnosisRequest diagnosisRequest(String runId, String identity) {
    Object saved = attributesByRun.getOrDefault(runId, Map.of())
        .get(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR);
    DiagnosisRequest request = saved instanceof String json && !json.isBlank()
        ? readRecoveryJson(json, DiagnosisRequest.class) : null;
    if (request != null && !identity.equals(request.identity())) { request = null; }
    for (StageAttempt attempt : requireRun(runId).attempts()) {
      Object state = ProductPipelineRunSupport.readHaltEvidence(attempt.failureEvidence())
          .get(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR);
      if (state instanceof String json && !json.isBlank()) {
        DiagnosisRequest recorded = readRecoveryJson(json, DiagnosisRequest.class);
        if (identity.equals(recorded.identity()) && (request == null || recorded.calls() >= request.calls())) {
          request = recorded;
        }
      }
    }
    return request;
  }

  AcceptedRecovery acceptedRecoveryDecision(String runId, RecoveryContext context) {
    String identity = context.evidence().failureId();
    DiagnosisRequest request = diagnosisRequest(runId, identity);
    if (request == null) { request = new DiagnosisRequest(identity, 0, null); }
    AcceptedRecovery prior = request.result();
    if (prior != null && (prior.decision() != null || request.calls() >= 2
        || prior.diagnosisStatus() != RecoveryStatus.INVALID_REPLY
            && prior.diagnosisStatus() != RecoveryStatus.TRANSIENT_PROVIDER_FAILURE)) {
      return prior;
    }
    RecoveryEvidence evidence = prior == null ? context.evidence() : prior.evidence();
    boolean rejectedQuestion = prior != null && prior.rejectedAuthorQuestion();
    int calls = request.calls();
    AcceptedRecovery pendingRetry = prior;
    while (calls < 2) {
      if (pendingRetry != null && pendingRetry.diagnosisStatus() == RecoveryStatus.TRANSIENT_PROVIDER_FAILURE
          && pendingRetry.retryAt() != null && !awaitProviderRetry(pendingRetry.retryAt())) {
        return pendingRetry;
      }
      pendingRetry = null;
      String refusal = llmBudget == null ? null : llmBudget.refusalForNextRequest(runId);
      if (refusal != null) {
        return saveDiagnosis(runId, identity, calls, new AcceptedRecovery(null,
            evidenceWithDecisionFindings(evidence, List.of(refusal)), rejectedQuestion, RecoveryStatus.RESOURCE_REFUSAL));
      }
      RecoveryContext nextContext = new RecoveryContext(evidence, context.approvedBrief(),
          context.rejectedArtifact(), context.responseLocale(), context.authorInputs(),
          context.approvedPlanRef(), context.approvedPlan(), context.priorAttempts());
      saveDiagnosis(runId, identity, ++calls,
          new AcceptedRecovery(null, evidence, rejectedQuestion, RecoveryStatus.INVALID_REPLY));
      RecoveryResult reply = failureNarrative.diagnoseRecovery(runId, nextContext);
      if (reply.status() != RecoveryStatus.READY) {
        if (reply.retryAt() == null) {
          evidence = evidenceWithDecisionFindings(evidence, List.of(reply.detail() == null ? reply.status().name() : reply.detail()));
        }
        AcceptedRecovery failed = new AcceptedRecovery(null, evidence, rejectedQuestion, reply.status(), null,
            reply.retryAt());
        saveDiagnosis(runId, identity, calls, failed);
        if (calls >= 2 || reply.status() != RecoveryStatus.INVALID_REPLY
            && reply.status() != RecoveryStatus.TRANSIENT_PROVIDER_FAILURE) { return failed; }
        pendingRetry = failed;
        continue;
      }
      RecoveryDecision rawDecision = reply.decision();
      RecoveryDecision decision = presentedRecoveryDecision(rawDecision);
      RecoveryDecisionValidator.Result validation = RecoveryDecisionValidator.validate(decision, nextContext);
      if (validation.requiresSourceReconciliation()) {
        var question = decision.authorQuestionEvidence();
        var sourceRefs = new ArrayList<String>();
        sourceRefs.add(evidence.failureId());
        context.authorInputs().forEach(input -> sourceRefs.add(input.inputId()));
        var reconciliation = presentedRecoveryDecision(new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT,
            evidence.approvedBriefRef(), sourceRefs, RecoveryAction.REVISE_BRIEF, List.of(), "",
            "Check the saved author messages for " + question.propertyKey() + " on " + question.interactionId()
                + ". Preserve later corrections and confirmed choices. Ask only if the source leaves the value unresolved."));
        return saveDiagnosis(runId, identity, calls,
            new AcceptedRecovery(reconciliation, evidenceWithDecisionPresentation(evidence, rawDecision, decision),
                true, RecoveryStatus.READY, question));
      }
      if (validation.accepted()) {
        return saveDiagnosis(runId, identity, calls,
            new AcceptedRecovery(decision, evidenceWithDecisionPresentation(evidence, rawDecision, decision),
                rejectedQuestion, RecoveryStatus.READY));
      }
      rejectedQuestion |= decision != null && decision.action() == RecoveryAction.ASK_USER;
      evidence = evidenceWithDecisionFindings(evidence, validation.findings(), rawDecision);
      saveDiagnosis(runId, identity, calls,
          new AcceptedRecovery(null, evidence, rejectedQuestion, RecoveryStatus.INVALID_REPLY));
    }
    return new AcceptedRecovery(null, evidence, rejectedQuestion, RecoveryStatus.INVALID_REPLY);
  }

  private static RecoveryDecision presentedRecoveryDecision(RecoveryDecision decision) {
    if (decision == null) return null;
    String summary = decision.action() == null ? "" : switch (decision.action()) {
      case REVISE_BRIEF ->
          "The requirements need reconciliation with the saved messages. Confirmed choices will be preserved.";
      case REGENERATE_ARTIFACT ->
          "The generated result needs correction. Recovery will continue with the saved requirements.";
      case RETRY_OPERATION -> "The operation is temporarily unavailable. A retry will preserve the current progress.";
      case ASK_USER -> "An unresolved question needs clarification before continuing. The current progress is saved.";
      case PARK -> "Recovery is paused for review. The current progress is saved.";
    };
    return new RecoveryDecision(decision.causeClass(), decision.faultArtifactRef(), decision.evidenceRefs(),
        decision.action(), decision.proposedBriefChanges(), decision.question(), summary, decision.authorQuestionEvidence());
  }

  private RecoveryEvidence evidenceWithDecisionPresentation(
      RecoveryEvidence evidence, RecoveryDecision rawDecision, RecoveryDecision presentedDecision) {
    var findings = new ArrayList<>(evidence.findings());
    findings.add(new SemanticFinding("RECOVERY_PRESENTATION", "", evidence.failureId() + "-presentation", "", "",
        List.of(), List.of(), List.of(), "", Map.of(), List.of(),
        recoveryJson(Map.of("status", "PRESENTATION_ONLY", "rawDecision", rawDecision,
            "presentedDecision", presentedDecision))));
    return evidenceWithFindings(evidence, findings);
  }

  private boolean awaitProviderRetry(Instant retryAt) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Instant now = clock.instant();
        if (!now.isBefore(retryAt)) return true;
        providerRetrySleeper.sleep(Duration.between(now, retryAt));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  private AcceptedRecovery saveDiagnosis(String runId, String identity, int calls, AcceptedRecovery result) {
    putRunAttribute(runId, ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR,
        recoveryJson(new DiagnosisRequest(identity, calls, result)));
    return result;
  }

  private String recoveryJson(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Cannot save recovery request state", failure);
    }
  }

  private <T> T readRecoveryJson(String json, Class<T> type) {
    try { return objectMapper.readValue(json, type); }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Cannot read recovery request state", failure);
    }
  }

  private StageExecutionResult resourcePause(ProductPipelineRunDocument doc, ProfileStage stage,
      List<Reference> refs, String reason, List<PipelineSignal> emitted) {
    return waitContextualRecovery(doc, stage, refs, PipelineGates.RECOVERY_RETRY_TECHNICAL,
        reason + " Progress is saved. Retry creation authorizes one additional model allowance.",
        "LLM_RESOURCE_LIMIT: " + reason, null, emitted);
  }

  private Optional<String> compiledBriefProducerStageId(String runId, RecoveryCause cause) {
    Reference compiledBriefRef = compiledBriefReference(cause);
    if (compiledBriefRef == null) {
      return Optional.empty();
    }
    return artifactStore
        .get(runId, compiledBriefRef)
        .map(Revision::provenance)
        .filter(provenance -> provenance.stageId() != null && !provenance.stageId().isBlank())
        .map(ArtifactProvenance::stageId);
  }

  private record ReportedDraftDefect(String owner, List<PlanValidationFinding> findings) {}

  private Optional<ReportedDraftDefect> reportedDraftOwner(ProductPipelineRunDocument doc, ProfileStage stage,
      List<Reference> refs, RecoveryCause cause) {
    if (cause.causeCode() != RecoveryCauseCode.BRIEF_CONTRACT
        && cause.causeCode() != RecoveryCauseCode.MISSING_BRIEF_FACTS) {
      return Optional.empty();
    }
    Revision draftRevision = committedArtifactRevision(doc, Kind.REQUIREMENT_DRAFT);
    if (draftRevision == null) return Optional.empty();
    Revision briefRevision = rejectedArtifactRefs(refs).stream()
        .filter(ref -> ref.kind() == Kind.REQUIREMENT_BRIEF)
        .map(ref -> rejectedArtifactRevision(doc.run().runId(), ref).orElse(null))
        .filter(Objects::nonNull).findFirst()
        .orElseGet(() -> committedArtifactRevision(doc, Kind.REQUIREMENT_BRIEF));
    if (briefRevision == null) {
      // Before a brief exists, a native draft pointer identifies the committed analysis input.
      if (stage.consumes().stream().noneMatch(type -> type.matches(Kind.REQUIREMENT_DRAFT))
          || stage.produces().stream().noneMatch(type -> type.matches(Kind.REQUIREMENT_BRIEF))) {
        return Optional.empty();
      }
    }
    RequirementBrief brief = briefRevision == null ? null : artifactStore.payload(briefRevision, RequirementBrief.class);
    if (brief != null && !draftRevision.artifactId().equals(brief.approvedDraftReference())) return Optional.empty();
    RequirementDraft draft = artifactStore.payload(draftRevision, RequirementDraft.class);
    List<PlanValidationFinding> draftFindings = new ArrayList<>();
    for (PlanValidationFinding finding : cause.findings()) {
      String path = RequirementDraftStore.requirementRepairPath(draft, finding);
      if (path == null) continue;
      if (brief == null) {
        draftFindings.add(finding);
        continue;
      }
      JsonPointer pointer = JsonPointer.compile(path);
      String family = pointer.getMatchingProperty();
      String id = pointer.tail().getMatchingProperty();
      JsonPointer field = pointer.tail().tail();
      JsonNode source;
      JsonNode derived;
      if ("facts".equals(family)) {
        source = requirementField(draft.facts(), "sourceFactId", id, field);
        derived = requirementField(brief.facts(), "sourceFactId", id, field);
      } else if ("interactions".equals(family)) {
        source = requirementField(draft.flow().interactions(), "interactionId", id, field);
        derived = requirementField(brief.flow().interactions(), "interactionId", id, field);
      } else {
        continue;
      }
      // A brief-only defect must not authorize changes to the accepted draft.
      if (!source.isMissingNode() && source.equals(derived)) {
        draftFindings.add(finding);
      }
    }
    return draftFindings.isEmpty() ? Optional.empty()
        : requirementDraftOwner(doc.run().runId(), profilesByRun.get(doc.run().runId()), draftRevision)
            .filter(owner -> !owner.equals(stage.stageId()))
            .map(owner -> new ReportedDraftDefect(owner, List.copyOf(draftFindings)));
  }

  private JsonNode requirementField(List<?> records, String idField, String id, JsonPointer field) {
    JsonNode tree = objectMapper.valueToTree(records);
    for (JsonNode record : tree) {
      if (record.path(idField).asText().equals(id)) return record.at(field);
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private Optional<Revision> mappingDraftRevision(String runId, RecoveryCause cause) {
    if (cause == null || cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      return Optional.empty();
    }
    Reference briefRef = compiledBriefReference(cause);
    if (briefRef == null) {
      briefRef = mappingRepairEvidence(runId).map(RecoveryEvidence::approvedBriefRef).orElse(null);
    }
    if (briefRef == null) {
      return Optional.empty();
    }
    RequirementBrief brief = artifactStore.get(runId, briefRef)
        .map(revision -> artifactStore.payload(revision, RequirementBrief.class)).orElse(null);
    if (brief == null) {
      return Optional.empty();
    }
    return artifactStore.history(runId, Kind.REQUIREMENT_DRAFT).stream()
        .filter(revision -> revision.artifactId().equals(brief.approvedDraftReference()))
        .filter(revision -> revision.provenance() != null
            && runId.equals(revision.provenance().runId()))
        .filter(revision -> {
          RequirementDraft draft = artifactStore.payload(revision, RequirementDraft.class);
          return draft.authoredDraft() != null && draft.authoredDraft().facts().stream()
              .anyMatch(fact -> fact.fieldMapping() != null);
        }).findFirst();
  }

  private Optional<RecoveryEvidence> mappingRepairEvidence(String runId) {
    return mappingRepairEvidenceRevision(runId)
        .map(revision -> artifactStore.payload(revision, RecoveryEvidence.class));
  }

  private Optional<Revision> mappingRepairEvidenceRevision(String runId) {
    Object hash = attributesByRun.getOrDefault(runId, Map.of())
        .get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR);
    if (!(hash instanceof String value) || value.isBlank()) return Optional.empty();
    Map<String, Revision> revisions = new HashMap<>();
    artifactStore.history(runId, Kind.RECOVERY_EVIDENCE).forEach(revision ->
        revisions.putIfAbsent(revision.contentHash(), revision));
    var pending = new java.util.ArrayDeque<String>();
    var visited = new java.util.HashSet<String>();
    pending.add(value);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!visited.add(next)) continue;
      Revision revision = revisions.get(next);
      if (revision == null) continue;
      var evidence = artifactStore.payload(revision, RecoveryEvidence.class);
      if ("MAPPING_CONTRACT".equals(evidence.observedCauseCode())) return Optional.of(revision);
      evidence.priorAttemptRefs().stream().filter(ref -> ref.kind() == Kind.RECOVERY_EVIDENCE)
          .forEach(ref -> pending.addLast(ref.contentHash()));
    }
    return Optional.empty();
  }

  private Optional<String> requirementDraftOwner(String runId, ProductPipelineProfile profile, Revision selected) {
    if (profile == null) {
      return Optional.empty();
    }
    var pending = new java.util.ArrayDeque<Revision>();
    var visited = new java.util.HashSet<String>();
    pending.add(selected);
    while (!pending.isEmpty()) {
      Revision revision = pending.removeFirst();
      if (!visited.add(revision.artifactId())) {
        continue;
      }
      if (revision.provenance() != null && runId.equals(revision.provenance().runId())) {
        String owner = revision.provenance().stageId();
        if (profile.stages().stream().anyMatch(stage -> owner.equals(stage.stageId())
            && "requirement-discovery".equals(stage.capabilityId()))) {
          return Optional.of(owner);
        }
      }
      revision.inputs().stream().filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT)
          .forEach(ref -> artifactStore.get(runId, ref).ifPresent(pending::addLast));
    }
    return Optional.empty();
  }

  private static Reference compiledBriefReference(RecoveryCause cause) {
    if (cause == null || cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      return null;
    }
    for (PlanValidationFinding finding : cause.findings()) {
      if (finding == null) {
        continue;
      }
      String artifactId = finding.mappingDetails().consumedBriefArtifactId();
      String contentHash = finding.mappingDetails().consumedBriefContentHash();
      if (!artifactId.isBlank() && !contentHash.isBlank()) {
        return new Reference(Kind.REQUIREMENT_BRIEF, artifactId, contentHash);
      }
    }
    return null;
  }

  /**
   * Stores the observing stage and requirement producer on the durable recovery record. Schema-side identity
   * stays in the finding JSON; those sides live under conversation compilation, not this run.
   */
  private void persistRequirementContractEvidence(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryCause cause,
      String producerStageId) {
    String runId = doc.run().runId();
    String failureId = UUID.randomUUID().toString();
    Reference approvedBriefRef = compiledBriefReference(cause);
    if (approvedBriefRef == null && cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      Revision brief = committedArtifactRevision(doc, Kind.REQUIREMENT_BRIEF);
      approvedBriefRef = brief == null ? null : brief.reference();
    }
    List<Reference> rejectedRefs = rejectedArtifactRefs(refs);
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            failureId,
            cause.causeCode().name(),
            stage.stageId(),
            producerStageId,
            approvedBriefRef,
            null,
            rejectedRefs,
            semanticFindingsFor(runId, rejectedRefs, stage.stageId(), failureId, "", "", cause),
            null, repairHistory(runId, producerStageId).stream().map(RepairHistory.Attempt::evidenceRef).toList(),
            List.of(), currentProducerInputs(runId, producerStageId),
            authorInputs(doc).stream().map(UserInput::inputId).toList(), false);
    List<Reference> evidenceInputs = new ArrayList<>();
    if (approvedBriefRef != null) {
      evidenceInputs.add(approvedBriefRef);
    }
    Revision draft = committedArtifactRevision(doc, Kind.REQUIREMENT_DRAFT);
    if (draft != null) evidenceInputs.add(draft.reference());
    rejectedRefs.stream()
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
                evidence,
                evidenceInputs,
                null,
                provenance(runId, stage.stageId(), stage.capabilityId())));
    putRunAttribute(
        runId,
        ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
        storedEvidence.contentHash());
  }

  private List<SemanticFinding> semanticFindingsFor(
      String runId,
      List<Reference> rejectedRefs,
      String observingStageId,
      String failureId,
      String findings,
      String evidenceText,
      RecoveryCause cause) {
    List<SemanticFinding> mapped = mappingSemanticFindings(cause, failureId);
    if (!mapped.isEmpty()) {
      return mapped;
    }
    List<SemanticFinding> projected = new ArrayList<>();
    if (cause != null) {
      int index = 1;
      for (PlanValidationFinding finding : cause.findings()) {
        projected.add(new SemanticFinding(finding.code(), finding.message(), failureId + "-typed-" + index++,
            finding.originRecordId(), "", List.of(), List.of(), List.of(), "", Map.of(), List.of(),
            serializedFindingJson(objectMapper, finding)));
      }
    }
    projected.addAll(semanticFindingsForRejectedGraph(runId, rejectedRefs, observingStageId));
    return projected.isEmpty() ? List.of(fallbackSemanticFinding(failureId, findings, evidenceText)) : List.copyOf(projected);
  }

  private List<SemanticFinding> mappingSemanticFindings(RecoveryCause cause, String failureId) {
    if (cause == null || cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      return List.of();
    }
    ObjectMapper mapper = objectMapper;
    List<SemanticFinding> projected = new ArrayList<>();
    int index = 1;
    for (PlanValidationFinding finding : cause.findings()) {
      if (finding == null) {
        continue;
      }
      String rawJson = serializedFindingJson(mapper, finding);
      String location =
          finding.mappingDetails() == null ? "" : finding.mappingDetails().mappingIntentId();
      String targetPath =
          finding.mappingDetails() == null ? "" : finding.mappingDetails().targetPath();
      projected.add(
          new SemanticFinding(
              finding.code(),
              finding.message(),
              failureId + "-mapping-" + index,
              location,
              targetPath,
              List.of(),
              List.of(),
              List.of(),
              "",
              Map.of(),
              List.of(),
              rawJson));
      index++;
    }
    return List.copyOf(projected);
  }

  /** Diagnostic JSON for restore. Never store the human message when serialization fails. */
  private static String serializedFindingJson(ObjectMapper mapper, PlanValidationFinding finding) {
    try {
      String json = mapper.writeValueAsString(finding);
      if (json != null && json.strip().startsWith("{")) {
        return json;
      }
    } catch (Exception ignored) {
      // Fall through to a valid empty object.
    }
    return "{}";
  }

  private List<SemanticFinding> semanticFindingsForRejectedGraph(
      String runId,
      List<Reference> rejectedRefs,
      String observingStageId) {
    ChainPlanGraph graph = resolveRejectedGraph(runId, rejectedRefs);
    if (graph != null) {
      List<SemanticFinding> graphFindings =
          RecoveryEvidenceFactory.findingsFromChainPlanGraph(
              graph,
              observingStageId,
              objectMapper,
              schemaRefResolver,
              schemaService,
              schemaResourceLoader);
      if (!graphFindings.isEmpty()) {
        return graphFindings;
      }
    }
    return List.of();
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
        Optional<Revision> revision = rejectedArtifactRevision(runId, ref);
        if (revision.isPresent()) {
          return artifactStore.payload(revision.orElseThrow(), ChainPlanGraph.class);
        }
      }
    }
    return null;
  }

  private RecoveryEvidence evidenceWithDecisionFindings(
      RecoveryEvidence evidence, List<String> validationFindings) {
    return evidenceWithDecisionFindings(evidence, validationFindings, null);
  }

  private RecoveryEvidence evidenceWithDecisionFindings(
      RecoveryEvidence evidence, List<String> validationFindings, RecoveryDecision rejectedDecision) {
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
            rejectedDecision == null ? String.join("\n", validationFindings)
                : recoveryJson(Map.of("status", "REJECTED", "rejectedDecision", rejectedDecision,
                    "validationFindings", validationFindings))));
    return evidenceWithFindings(evidence, findings);
  }

  private static RecoveryEvidence evidenceWithFindings(RecoveryEvidence evidence, List<SemanticFinding> findings) {
    return new RecoveryEvidence(
        evidence.schemaVersion(),
        evidence.failureId(),
        evidence.observedCauseCode(),
        evidence.observingStageId(),
        evidence.producerStageId(),
        evidence.approvedBriefRef(),
        evidence.approvedSemanticRef(),
        evidence.rejectedArtifactRefs(),
        findings,
        evidence.technicalFailure(),
        evidence.priorAttemptRefs(), evidence.documentRecordRefs(), evidence.producerInputs(),
        evidence.authorInputIds(), evidence.cleanRebuild(), evidence.captureFailure());
  }

  private static List<Reference> rejectedArtifactRefs(List<Reference> refs) {
    return refs == null ? List.of() : List.copyOf(refs);
  }

  private Object rejectedArtifact(String runId, List<Reference> refs) {
    for (Reference ref : refs) {
      Optional<Revision> revision = rejectedArtifactRevision(runId, ref);
      if (revision.isPresent()) {
        return ref.kind() == Kind.CHAIN_SEMANTIC_REVISION
            ? artifactStore.payload(revision.orElseThrow(), ChainSemanticRevision.class)
            : artifactStore.payload(revision.orElseThrow(), Object.class);
      }
    }
    return null;
  }

  private Optional<Revision> rejectedArtifactRevision(String runId, Reference ref) {
    var invocation = producerInvocation(runId);
    return artifactStore.get(runId, ref).filter(revision -> ref.kind() != Kind.CHAIN_PLAN_GRAPH
        || invocation != null && StageRepairEvidence.matchesCompileInputs(revision, invocation.inputs()));
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
   * Gives an unstructured failure a stable ledger identity without using its wording to choose a
   * recovery action. Typed findings and requested facts remain the primary identity when present.
   */
  private static RecoveryCause recoveryBudgetCause(RecoveryCause cause, String evidence) {
    RecoveryCause typed =
        cause == null ? RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER) : cause;
    if (!typed.findings().isEmpty() || !typed.requestedFact().isBlank()) {
      return typed;
    }
    String signature = ToolCallFingerprints.failureSignature(evidence == null ? "" : evidence);
    return new RecoveryCause(
        typed.causeCode(),
        List.of(new PlanValidationFinding("FAILURE_SIGNATURE", signature, true)),
        "", typed.noProgress(), typed.captureFailure());
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

  private static List<OwnerCandidate> mappingOwnerCandidates(
      RecoveryCause cause,
      ProductPipelineProfile profile,
      String failedStageId,
      List<OwnerCandidate> candidates) {
    if (cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT) {
      return candidates;
    }
    String briefProducer =
        OwnerCandidateSet.briefProducerStageId(profile, failedStageId).orElse("");
    if (briefProducer.isBlank()
        || OwnerCandidateSet.containsStage(candidates, briefProducer)) {
      return candidates;
    }
    List<OwnerCandidate> expanded = new ArrayList<>(candidates);
    expanded.add(new OwnerCandidate(briefProducer, "requirement-brief"));
    return List.copyOf(expanded);
  }

  private boolean catalogHasBeenWritten(String runId) {
    return artifactStore.hasCatalogWriteEvidence(runId);
  }

  private void recordRegenerateAttempt(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryEvidence evidence,
      RecoveryCause cause) {
    recordRegenerateAttempt(doc, stage, refs, evidence, cause, stage.stageId());
  }

  private void recordRegenerateAttempt(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryEvidence evidence,
      RecoveryCause cause,
      String producerStageId) {
    String briefIdentity = RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
    RecoveryAttemptKey key =
        recoveryLedger.key(stage.stageId(), cause, briefIdentity, doc.transitions());
    recordProducerRepairAttempt(
        doc, stage, refs, recoveryLedger.recordRepair(key, briefIdentity), producerStageId);
  }

  private RecoveryEvidence evidenceForFaultProducer(String runId, RecoveryEvidence evidence, RecoveryDecision decision) {
    if (decision == null || decision.faultArtifactRef() == null) { return evidence; }
    var fault = artifactStore.get(runId, decision.faultArtifactRef()).orElse(null);
    if (fault == null || fault.provenance() == null
        || fault.provenance().stageId().equals(evidence.producerStageId())) { return evidence; }
    var producerInputs = currentProducerInputs(runId, fault.provenance().stageId());
    if (producerInputs.isEmpty() || !producerInputs.containsAll(fault.inputs())) { return evidence; }
    return new RecoveryEvidence(evidence.schemaVersion(), evidence.failureId(), evidence.observedCauseCode(),
        evidence.observingStageId(), fault.provenance().stageId(), evidence.approvedBriefRef(),
        evidence.approvedSemanticRef(), evidence.rejectedArtifactRefs(), evidence.findings(), evidence.technicalFailure(),
        evidence.priorAttemptRefs(), evidence.documentRecordRefs(), producerInputs,
        evidence.authorInputIds(), evidence.cleanRebuild(), evidence.captureFailure());
  }

  private List<RepairHistory.Attempt> repairHistory(String runId, String producer) {
    return repairHistory(runId, producer, currentProducerInputs(runId, producer),
        authorInputs(requireRun(runId)).stream().map(UserInput::inputId).toList());
  }

  private List<Reference> currentProducerInputs(String runId, String producer) {
    var doc = requireRun(runId);
    var profile = profilesByRun.get(runId);
    var stage = profile == null ? null : stageById(profile, producer);
    return stage == null ? List.of() : resolveDeclaredInputs(profile, stage, committedInputs(doc)).inputs();
  }

  private List<RepairHistory.Attempt> repairHistory(String runId, String producer,
      List<Reference> inputs, List<String> authorIds) {
    return artifactStore.history(runId, Kind.RECOVERY_EVIDENCE).stream()
        .filter(revision -> {
          var evidence = artifactStore.payload(revision, RecoveryEvidence.class);
          return (producer.equals(evidence.producerStageId())
              || evidence.producerStageId().isBlank() && producer.equals(evidence.observingStageId()))
              && inputs.equals(evidence.producerInputs()) && authorIds.equals(evidence.authorInputIds());
        }).map(revision -> {
          var evidence = artifactStore.payload(revision, RecoveryEvidence.class);
          Object proposal = evidence.observingStageId().equals(producer)
              ? rejectedArtifact(runId, evidence.rejectedArtifactRefs()) : null;
          return new RepairHistory.Attempt(revision.reference(), evidence.failureId(), producer,
              evidence.producerInputs(), evidence.authorInputIds(), evidence.rejectedArtifactRefs(),
              objectMapper.valueToTree(proposal), evidence.findings(), evidence.cleanRebuild());
        }).toList();
  }

  private boolean isCurrentCleanRebuild(String runId, String stageId) {
    var attrs = attributesByRun.getOrDefault(runId, Map.of());
    Object evidence = attrs.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR);
    return evidence instanceof String hash && !hash.isBlank()
        && hash.equals(attrs.get(cleanRebuildEvidenceAttribute(stageId)));
  }

  private boolean recurringRejectedOutput(RecoveryEvidence current, Object output,
      List<RepairHistory.Attempt> history) {
    if (output == null || current.producerInputs().isEmpty()) { return false; }
    var proposal = objectMapper.valueToTree(output);
    return history.stream().filter(attempt -> current.producerInputs().equals(attempt.inputs()))
        .filter(attempt -> proposal.equals(attempt.proposal()))
        .filter(attempt -> sameFindingCoverage(current.findings(), attempt.findings()))
        .anyMatch(attempt -> attempt.authorInputIds().equals(current.authorInputIds()));
  }

  private static boolean sameFindingCoverage(List<SemanticFinding> first, List<SemanticFinding> second) {
    return findingCoverage(first).equals(findingCoverage(second));
  }

  private static List<String> findingCoverage(List<SemanticFinding> findings) {
    return findings.stream().filter(finding -> !"INVALID_RECOVERY_DECISION".equals(finding.code()))
        .map(finding -> finding.code() + "\u0000" + finding.nodeId() + "\u0000" + finding.violatedRule()
        + "\u0000" + finding.missingKeys() + "\u0000" + finding.unexpectedKeys()
        + "\u0000" + finding.selectedOneOf()).sorted().toList();
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
    recordProducerRepairAttempt(doc, stage, refs, reason, stage.stageId());
  }

  private void recordProducerRepairAttempt(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      String reason,
      String producerStageId) {
    putRunAttribute(doc.run().runId(), ProductPipelineRunSupport.RECOVERY_PENDING_ATTEMPT_ATTR,
        recoveryJson(new PendingRecoveryAttempt(producerStageId, doc.run().runRevision() + 1)));
    List<StageSnapshot> repairing =
        markStageOutputs(doc, stage.stageId(), refs, StageStatus.RUNNING);
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        repairing,
        reason,
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null),
        StageStatus.FAILED);
  }

  private record PendingRecoveryAttempt(String stageId, long revision) {}

  private void settlePendingRecoveryAttempt(String runId, ProfileStage stage, long requestsBefore) {
    var attributes = attributesByRun.get(runId);
    Object saved = attributes == null ? null : attributes.get(ProductPipelineRunSupport.RECOVERY_PENDING_ATTEMPT_ATTR);
    var pending = saved instanceof String json && !json.isBlank()
        ? readRecoveryJson(json, PendingRecoveryAttempt.class) : null;
    if (pending == null || !stage.stageId().equals(pending.stageId())) { return; }
    attributes.remove(ProductPipelineRunSupport.RECOVERY_PENDING_ATTEMPT_ATTR);
    var doc = requireRun(runId);
    String revision = Long.toString(pending.revision());
    if (doc.transitions().stream().anyMatch(transition ->
        (RecoveryAttemptLedger.SETTLED_ATTEMPT_REASON_PREFIX + revision).equals(transition.reason())
            || (RecoveryAttemptLedger.UNSENT_ATTEMPT_REASON_PREFIX + revision).equals(transition.reason()))) { return; }
    boolean unsent = llmBudget != null && llmBudget.state(runId).refusal() != null
        && llmBudget.state(runId).requests() == requestsBefore;
    commitStatus(doc, RunStatus.RUNNING, StageStatus.RUNNING, doc.run().stages(),
        (unsent ? RecoveryAttemptLedger.UNSENT_ATTEMPT_REASON_PREFIX
            : RecoveryAttemptLedger.SETTLED_ATTEMPT_REASON_PREFIX) + revision,
        ProductPipelineRunSupport.haltEvidence(attributes, null));
  }

  private boolean canCausalReopen(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause, String evidence) {
    if (owner == null || owner.isBlank() || owner.equals(doc.run().currentStageId())) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    if (!isReopenableOwner(doc, owner, cause)) {
      return false;
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, recoveryBudgetCause(cause, evidence), artifact, doc.transitions());
    return recoveryLedger.mayReopen(
        doc.transitions(),
        key,
        InputOrigin.TRUSTED,
        RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC,
        ToolCallFingerprints.failureSignature(evidence));
  }

  private boolean isReopenableOwner(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause) {
    StageSnapshot snapshot =
        doc.run().stages().stream()
            .filter(stage -> owner.equals(stage.stageId()))
            .findFirst()
            .orElse(null);
    if (snapshot == null) {
      return false;
    }
    if (snapshot.approvedArtifactId() != null && !snapshot.approvedArtifactId().isBlank()) {
      return true;
    }
    boolean succeededWithOutputs =
        snapshot.status() == StageStatus.SUCCEEDED
            && snapshot.outputRefs() != null
            && !snapshot.outputRefs().isEmpty();
    return cause != null
        && succeededWithOutputs
        && (cause.isBindingIdentityMismatch()
            || ((cause.causeCode() == RecoveryCauseCode.MISSING_BRIEF_FACTS
                || cause.causeCode() == RecoveryCauseCode.BRIEF_CONTRACT)
                && ProductPipelineRunSupport.isCommittedRequirementDraftOwner(
                    doc, owner, profilesByRun.get(doc.run().runId()), artifactStore))
            || (cause.causeCode() == RecoveryCauseCode.MAPPING_CONTRACT
                && mappingDraftRevision(doc.run().runId(), cause)
                    .flatMap(revision -> requirementDraftOwner(doc.run().runId(),
                        profilesByRun.get(doc.run().runId()), revision)).filter(owner::equals).isPresent())
            || (cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE
                && "materialization".equals(doc.run().currentStageId())
                && "design-execution".equals(owner))
            || (cause.causeCode() == RecoveryCauseCode.GRAPH_STRUCTURE
                && "design-execution".equals(doc.run().currentStageId())
                && "design-input".equals(owner))
            || ProductPipelineRunSupport.isCommittedContractInputOwner(doc, owner, cause,
                profilesByRun.get(doc.run().runId()), artifactStore));
  }

  private HaltRecoveryGuard diagnoseAutomaticReopenRefusal(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause, String evidence) {
    if (catalogHasBeenWritten(doc.run().runId())) {
      return HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN;
    }
    if (owner == null
        || owner.isBlank()
        || owner.equals(doc.run().currentStageId())
        || !isReopenableOwner(doc, owner, cause)) {
      return HaltRecoveryGuard.BLANK_OR_UNAPPROVED_OWNER;
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, recoveryBudgetCause(cause, evidence), artifact, doc.transitions());
    String legacy = ToolCallFingerprints.failureSignature(evidence == null ? "" : evidence);
    if (recoveryLedger.ownerAlreadyReopened(doc.transitions(), key, legacy)) {
      return HaltRecoveryGuard.OWNER_ALREADY_REOPENED;
    }
    return HaltRecoveryGuard.MAX_CAUSAL_REOPENS;
  }

  /**
   * Known pre-write brief defect after automatic reopen is spent. Edit requirements stays
   * available; identity-mismatch G3 stops and post-write parks do not take this path. A mapping
   * capture reject on the brief producer itself still offers Edit requirements.
   */
  private boolean offersManualBriefEdit(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      ProducerOwnedRecovery.Route recovery,
      RecoveryCause cause,
      String evidence) {
    if (!offersManualBriefEdit(doc, stage, cause)) {
      return false;
    }
    String owner = recovery == null ? "" : recovery.producerStageId();
    if (owner.isBlank()) {
      return false;
    }
    if (owner.equals(stage.stageId())
        && (cause == null || cause.causeCode() != RecoveryCauseCode.MAPPING_CONTRACT)) {
      return false;
    }
    ProducerOwnedRecovery.Action action =
        recovery == null ? ProducerOwnedRecovery.Action.PARK : recovery.action();
    if (action == ProducerOwnedRecovery.Action.REOPEN_UPSTREAM) {
      return !canCausalReopen(doc, owner, cause, evidence);
    }
    return action == ProducerOwnedRecovery.Action.PARK;
  }

  private boolean offersManualBriefEdit(
      ProductPipelineRunDocument doc, ProfileStage stage, RecoveryCause cause) {
    if (cause == null || !cause.isKnownBriefDefect()) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    if (recoveryLedger.perRunCeilingReached(doc.transitions())) {
      return false;
    }
    String owner = briefOwnerStageId(doc, stage);
    if (!owner.isBlank() && !owner.equals(stage.stageId())) {
      return true;
    }
    return cause.causeCode() == RecoveryCauseCode.MAPPING_CONTRACT
        && stageProducesRequirementBrief(stage);
  }

  private static boolean stageProducesRequirementBrief(ProfileStage stage) {
    if (stage == null) {
      return false;
    }
    for (ArtifactTypeRef produced : stage.produces()) {
      if (produced != null && produced.matches(Kind.REQUIREMENT_BRIEF)) {
        return true;
      }
    }
    return false;
  }

  private String briefOwnerStageId(ProductPipelineRunDocument doc, ProfileStage stage) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    String failed = stage == null ? "" : stage.stageId();
    return OwnerCandidateSet.selectOwner(
            "",
            ownerCandidates(profile, failed),
            failed,
            RecoveryCause.of(RecoveryCauseCode.MISSING_BRIEF_FACTS),
            "")
        .owner()
        .orElse("");
  }

  private StageExecutionResult waitEditableBriefRecovery(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      RecoveryCause cause,
      String evidence,
      String ownerStageId,
      List<PipelineSignal> emitted) {
    String owner = ownerStageId == null ? "" : ownerStageId;
    if (owner.isBlank()) {
      owner = briefOwnerStageId(doc, stage);
    }
    if (!owner.isBlank()) {
      putRunAttribute(
          doc.run().runId(), ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, owner);
    }
    String findings = cause == null ? "" : cause.formattedFindings();
    String diagnostic = findings.isBlank() ? evidence : findings;
    return waitContextualRecovery(
        doc,
        stage,
        refs,
        PipelineGates.RECOVERY_REVISE_BRIEF,
        exhaustedBriefRecoverySummary(cause),
        terminalRecoveryDetails(diagnostic, evidence, doc.run().runId(), PROGRESS_NONE),
        null,
        emitted);
  }

  static String exhaustedBriefRecoverySummary(RecoveryCause cause) {
    List<String> fields = unresolvedBriefFields(cause);
    if (fields.isEmpty()) {
      return "The previous correction did not resolve the listed fields.";
    }
    return "The previous correction did not resolve " + joinListedFields(fields) + ".";
  }

  private static List<String> unresolvedBriefFields(RecoveryCause cause) {
    if (cause == null || cause.findings().isEmpty()) {
      return List.of();
    }
    List<String> fields = new ArrayList<>();
    for (PlanValidationFinding finding : cause.findings()) {
      if (finding == null) {
        continue;
      }
      String targetPath = finding.mappingDetails().targetPath().trim();
      if (!targetPath.isBlank()) {
        fields.add(targetPath);
        continue;
      }
      String message = finding.message() == null ? "" : finding.message().trim();
      if (message.startsWith("$.")) {
        fields.add(message);
      }
    }
    return List.copyOf(fields);
  }

  private static String joinListedFields(List<String> fields) {
    if (fields.isEmpty()) {
      return "";
    }
    if (fields.size() == 1) {
      return fields.getFirst();
    }
    if (fields.size() == 2) {
      return fields.get(0) + " and " + fields.get(1);
    }
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < fields.size(); index++) {
      if (index > 0) {
        text.append(index == fields.size() - 1 ? ", and " : ", ");
      }
      text.append(fields.get(index));
    }
    return text.toString();
  }

  private StageExecutionResult waitGuardedRecovery(
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      List<Reference> refs,
      HaltRecoveryGuard guard,
      RecoveryCause cause,
      String evidence,
      List<PipelineSignal> emitted) {
    HaltRecoveryGuard named = guard == null ? HaltRecoveryGuard.MAX_CAUSAL_REOPENS : guard;
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, stage.stageId());
    RecoveryAttemptKey key =
        recoveryLedger.key(stage.stageId(), cause, artifact, doc.transitions());
    String body =
        named.cardSentence()
            + HaltRecoveryGuard.remainingLine(
                recoveryLedger.remaining(doc.transitions(), key, InputOrigin.TRUSTED));
    String details = terminalRecoveryDetails(evidence, evidence, doc.run().runId(), PROGRESS_NONE);
    String prompt =
        PipelineGates.tagGuard(
            PipelineGates.tagRecoveryDetails(
                PipelineGates.retag(PipelineGates.RECOVERY_REPEATED, body), details, null),
            named.name());
    String durablePrompt =
        PipelineGates.tagHaltIdentity(
            prompt, ToolCallFingerprints.failureSignature(evidence));
    List<StageSnapshot> stages =
        markStageOutputs(doc, stage.stageId(), refs, StageStatus.WAITING_FOR_INPUT);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        stages,
        durablePrompt,
        ProductPipelineRunSupport.haltEvidence(attributesByRun.get(doc.run().runId()), null));
    emitted.add(new PipelineSignal.WaitingForInput(stage.stageId(), prompt));
    recoveryTelemetry.recordPresented(doc.run().runId(), PipelineGates.RECOVERY_REPEATED,
        ToolCallFingerprints.failureSignature(evidence), details);
    return new StageExecutionResult(
        new StageDecision.WaitForInput(stage.stageId(), prompt), emitted);
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
      } else if (ref.kind() == Kind.USER_INPUT) {
        UserInput input = artifactStore.payload(revision.get(), UserInput.class);
        MappingGapPassThroughConfirmation.parse(input.text())
            .ifPresent(confirmation -> attributes.put("mappingGapPassThrough", confirmation));
      } else if (ref.kind() == Kind.IDS_DOCUMENT) {
        attributes.put("idsDocument", artifactStore.payload(revision.get(), IdsDocument.class));
      } else if (ref.kind() == Kind.CHAIN_SEMANTIC_REVISION) {
        attributes.put(
            "chainSemanticRevision",
            artifactStore.payload(revision.get(), ChainSemanticRevision.class));
      }
    }
    putLatestRequirementBrief(runId, attributes);
    attributesByRun.put(runId, attributes);
    attributes.remove("mappingRepairFindings");
    attributes.remove("missingMappingTargets");
    attributes.remove("mappingRepairPaths");
    if ("true".equals(attributes.get("mappingRepairAutomatic")) && mappingRepairEvidence(runId).isPresent()) {
      mappingDraftRevision(runId, RecoveryCause.mappingContract(List.of())).ifPresent(revision -> {
        RequirementDraft draft = artifactStore.payload(revision, RequirementDraft.class);
        attributes.put("requirementCorrectionDraftReference", revision.reference());
        attributes.put("requirementCorrectionDraft", draft);
        Map<String, Set<String>> editable = new HashMap<>();
        List<MappingValidationDetails> missingTargets = new ArrayList<>();
        boolean sourceAvailable = !authorInputs(requireRun(runId)).isEmpty();
        attributes.put("mappingRepairFindings", mappingRepairEvidence(runId).orElseThrow().findings().stream()
            .map(org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding::rawValidatorJson)
            .collect(java.util.stream.Collectors.joining("\n")));
        mappingRepairEvidence(runId).ifPresent(evidence -> evidence.findings().forEach(finding -> {
          try {
            var nativeFinding = objectMapper
                .readValue(finding.rawValidatorJson(), PlanValidationFinding.class);
            var details = nativeFinding.mappingDetails();
            if ("MAPPING_MISSING_REQUIRED_TARGET".equals(nativeFinding.code()) && details.isPresent()) {
              missingTargets.add(details);
              return;
            }
            String path = switch (nativeFinding.code()) {
              case "MAPPING_INVALID_SOURCE" -> "sourcePath";
              case "MAPPING_UNKNOWN_TARGET" -> "targetPath";
              default -> null;
            };
            if (path == null) { return; }
            draft.authoredDraft().facts().stream().filter(fact -> fact.fieldMapping() != null)
                .filter(fact -> {
                  var mapping = fact.fieldMapping();
                  var rule = new MappingIntentRule(mapping.sourcePath(), mapping.targetPath(), mapping.expression());
                  return details.sourceRef().equals(mapping.sourceInteractionId())
                      && details.targetRef().equals(mapping.targetInteractionId())
                      && details.sourcePath().equals(MappingContract.canonicalPath(rule.sourcePath()))
                      && details.targetPath().equals(MappingContract.canonicalPath(rule.targetPath()))
                      && details.expression().equals(java.util.Objects.toString(rule.expression(), ""));
                })
                .forEach(fact -> editable.computeIfAbsent(fact.sourceFactId(), ignored -> new java.util.HashSet<>())
                    .addAll(sourceAvailable ? Set.of(path, "expression") : Set.of(path)));
          } catch (Exception ignored) {
            // Text-only evidence cannot authorize an automatic field correction.
          }
        }));
        attributes.put("mappingRepairPaths", editable.entrySet().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> Set.copyOf(entry.getValue()))));
        attributes.put("missingMappingTargets", List.copyOf(missingTargets));
      });
    }
    return attributes;
  }

  private RequirementBrief committedBrief(ProductPipelineRunDocument doc) {
    Revision revision = committedArtifactRevision(doc, Kind.REQUIREMENT_BRIEF);
    return revision == null ? null : artifactStore.payload(revision, RequirementBrief.class);
  }

  private void putLatestRequirementBrief(String runId, Map<String, Object> attributes) {
    Reference brief = ProductPipelineRunSupport.selectedRequirementBriefReference(requireRun(runId));
    Revision revision = brief == null ? null : artifactStore.get(runId, brief).orElseThrow();
    attributes.remove("requirementBrief");
    attributes.remove("requirementBriefContentHash");
    if (revision != null) {
      attributes.put("requirementBrief", artifactStore.payload(revision, RequirementBrief.class));
      attributes.put("requirementBriefContentHash", revision.contentHash());
    }
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
    var history = repairHistory(runId, stage.stageId());
    if (history.isEmpty()) {
      attributes.remove(StageRepairEvidence.REPAIR_HISTORY_ATTR);
    } else {
      attributes.put(StageRepairEvidence.REPAIR_HISTORY_ATTR,
          RepairHistory.project(history, objectMapper, RepairHistory.CHAR_BUDGET).toString());
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
    if (profile == null || !profile.usesDeclaredStageInputs()) {
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
    var invocation = producerInvocation(runId);
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
                  committedPayload(kind, candidate.candidate().payload()),
                  candidate.candidate().inputs().isEmpty() && invocation != null
                      && stage.stageId().equals(invocation.stageId())
                      ? invocation.inputs() : candidate.candidate().inputs(),
                  null,
                  provenance(runId, stage.stageId(), stage.capabilityId())));
      refs.add(revision.reference());
    }
    return List.copyOf(refs);
  }

  private static Object committedPayload(Kind kind, Object payload) {
    if (kind == Kind.REQUIREMENT_BRIEF && payload instanceof RequirementBrief brief) {
      return RequirementBriefProjector.canonicalizeMappingIntents(brief);
    }
    return payload;
  }

  private Revision committedArtifactRevision(ProductPipelineRunDocument doc, Kind kind) {
    return committedInputs(doc).stream().filter(ref -> ref.kind() == kind)
        .reduce((first, second) -> second)
        .map(ref -> artifactStore.get(doc.run().runId(), ref).orElseThrow(() ->
            new IllegalStateException("Committed recovery artifact is unavailable: " + ref.artifactId())))
        .orElse(null);
  }

  private List<UserInput> authorInputs(ProductPipelineRunDocument doc) {
    if (conversations != null) {
      return conversations.archive().read(doc.run().conversationId()).activeTurns().stream()
          .filter(turn -> turn.message().role()
              == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
              && "READY".equals(turn.status()))
          .map(turn -> new UserInput(turn.id(), "conversation-source", turn.message().content(),
              turn.message().createdAt())).toList();
    }
    return artifactStore.history(doc.run().runId(), Kind.USER_INPUT).stream()
        .map(revision -> artifactStore.payload(revision, UserInput.class))
        .filter(input -> doc.transitions().stream().anyMatch(transition -> transition.commandId() != null
            && input.inputId().equals("user-input-" + transition.commandId())))
        .toList();
  }

  private List<Reference> committedInputs(ProductPipelineRunDocument doc) {
    List<Reference> refs = new ArrayList<>();
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    Set<String> later = profile == null ? Set.of()
        : new java.util.HashSet<>(profile.stages().stream().map(ProfileStage::stageId)
            .dropWhile(id -> !id.equals(doc.run().currentStageId())).skip(1).toList());
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.status() == StageStatus.PENDING || later.contains(snapshot.stageId())) { continue; }
      if (snapshot.stageId().equals(doc.run().currentStageId()) && snapshot.status() != StageStatus.SUCCEEDED
          && ProductPipelineRunSupport.preparingReplacement(profile, attributesByRun.get(doc.run().runId()),
              snapshot.stageId())) {
        continue;
      }
      if (snapshot.status() == StageStatus.SUCCEEDED && ProductPipelineRunSupport.preparingReplacement(
          profile, attributesByRun.get(doc.run().runId()), snapshot.stageId())
          && !snapshot.candidateReferences().isEmpty()) {
        refs.addAll(snapshot.candidateReferences());
      } else if (snapshot.approvedArtifactId() != null) {
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
    Reference sourceBrief = ProductPipelineRunSupport.selectedRequirementBriefReference(doc);
    if (sourceBrief != null && (refs.stream().anyMatch(ref -> ref.kind() == Kind.REQUIREMENT_BRIEF)
        || !"requirement-discovery".equals(doc.run().currentStageId()))) {
      refs.removeIf(ref -> ref.kind() == Kind.REQUIREMENT_BRIEF);
      refs.add(sourceBrief);
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
    long expected = doc.run().runRevision();
    ClarificationTransition clarification = nextStatus == RunStatus.WAITING_FOR_INPUT && clarificationSource != null
        ? clarificationSource.requirementClarification(doc, expected + 1L, reason)
        : null;
    runStore.commit(
        expected,
        LogicalCommit.statusChange(
            doc.run(),
            stageStatus,
            stages,
            new StageAttempt(
                UUID.randomUUID().toString(),
                doc.run().currentStageId(),
                expected + 1L,
                attemptOutcome,
                clock.instant(),
                clock.instant(),
                stages.stream()
                    .filter(s -> s.stageId().equals(doc.run().currentStageId()))
                    .findFirst()
                    .map(snapshot -> ProductPipelineRunSupport.preparingReplacement(
                        profilesByRun.get(doc.run().runId()), attributesByRun.get(doc.run().runId()), snapshot.stageId())
                        ? snapshot.candidateReferences() : snapshot.outputRefs())
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
                null,
                clarification)));
    if (attemptOutcome == StageStatus.SUCCEEDED) {
      ProductPipelineRunSupport.settleActiveRepair(doc.run().currentStageId(), attributesByRun.get(doc.run().runId()));
    }
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

  private List<StageSnapshot> markStageOutputs(
      ProductPipelineRunDocument doc, String stageId, List<Reference> refs, StageStatus status) {
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.stageId().equals(stageId)) {
        updated.add(
            new StageSnapshot(
                stageId,
                status,
                ProductPipelineRunSupport.preparingReplacement(profilesByRun.get(doc.run().runId()),
                    attributesByRun.get(doc.run().runId()), stageId) ? snapshot.outputRefs() : refs,
                snapshot.approvedArtifactId(),
                ProductPipelineRunSupport.preparingReplacement(profilesByRun.get(doc.run().runId()),
                    attributesByRun.get(doc.run().runId()), stageId) ? refs : snapshot.candidateReferences(),
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

  private static boolean supportsCleanProducerRebuild(String stageId) {
    return "requirement-discovery".equals(stageId) || "requirement-analysis".equals(stageId)
        || "design-input".equals(stageId) || "design-planning".equals(stageId) || "design-execution".equals(stageId);
  }

  private static String cleanRebuildEvidenceAttribute(String stageId) {
    if ("design-execution".equals(stageId) || "requirement-discovery".equals(stageId)
        || "requirement-analysis".equals(stageId)) {
      return ProductPipelineRunSupport.PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR;
    }
    return "design-input".equals(stageId)
        ? ProductPipelineRunSupport.DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR
        : ProductPipelineRunSupport.DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR;
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
      return ProductPipelineRunSupport.withAuthorNote(
          attributesByRun.get(runId), ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT);
    }
    RunManifest manifest = manifestsByRun.get(runId);
    String responseLocale = manifest == null ? "en" : manifest.responseLocale();
    return org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget.withRun(runId,
        () -> approvalPrompts.stageApprovalPrompt(stageId, responseLocale, languageReferenceFor(runId)));
  }

  private String stageErrorContext(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return "";
    }
    Object error = attributes.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
    return error instanceof String text ? text : "";
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
    var currentBrief = artifactStore.latest(runId, Kind.REQUIREMENT_BRIEF);
    if (currentBrief.isPresent()) {
      return MappingGapWait.languageReference(artifactStore.payload(currentBrief.get(), RequirementBrief.class));
    }
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
