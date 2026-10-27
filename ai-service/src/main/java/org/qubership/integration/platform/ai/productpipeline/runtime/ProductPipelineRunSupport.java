package org.qubership.integration.platform.ai.productpipeline.runtime;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecord;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.FailureClass;
import org.qubership.integration.platform.ai.productpipeline.artifact.FailureRecord;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.MappingRepairCaptureValidator;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidate;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet;
import org.qubership.integration.platform.ai.productpipeline.create.PauseQuestionResult;
import org.qubership.integration.platform.ai.plan.RequirementClarificationCoordinator;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ImplementationGatePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.plan.BriefMappingReview;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanChatView;
import org.qubership.integration.platform.ai.plan.MappingTurnAdapter;
import org.qubership.integration.platform.ai.plan.MappingTurnApplication;
import org.qubership.integration.platform.ai.plan.MappingTurnProcessor;
import org.qubership.integration.platform.ai.plan.MappingTurnResult;
import org.qubership.integration.platform.ai.plan.MappingTurnTelemetry;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.storage.S3Service;

/**
 * Durable create-chain run journal used by Flow. Records commands, hydrates pins, and applies one
 * stage decision. It does not own stage order or resume a recursive advance loop.
 */
public final class ProductPipelineRunSupport {

  public static final String ADMITTED_INPUT_GATE_ATTR = "admittedInputGate";
  public static final String ADMITTED_INPUT_REVISION_ATTR = "admittedInputRevision";
  /** Offer id the accepted requirement answer returned. Chain-call admission may write only that offer. */
  public static final String ADMITTED_CLARIFICATION_OFFER_ATTR = "admittedClarificationOfferId";

  private static final Logger LOG = Logger.getLogger(ProductPipelineRunSupport.class);

  /**
   * Latest typed message recorded while the run was at a recoverable halt. The next diagnosis
   * turn reads this attribute from the stage context.
   */
  public static final String HALT_FOLLOW_UP_TEXT_ATTR = "haltFollowUpText";

  /** Diagnosed owner stage id from the last validation/domain/contract halt. */
  public static final String DIAGNOSED_OWNER_STAGE_ATTR = "diagnosedOwnerStageId";

  public static final String RECOVERY_INVOCATION_ATTR = "recoveryProducerInvocation";
  public static final String RECOVERY_PENDING_ATTEMPT_ATTR = "recoveryPendingAttempt";
  public static final String RECOVERY_DIAGNOSIS_ATTR = "recoveryDiagnosisRequest";

  /** Raw failure evidence for the next attempt of the current unapproved stage. */
  public static final String STAGE_ERROR_CONTEXT_ATTR = "stageErrorContext";

  /** Immediate stop reason, separate from the producer evidence retained for repair. */
  public static final String CURRENT_HALT_REASON_ATTR = "currentHaltReason";

  /** Plain-language reason the run reopened a producer, shown above the approval that follows. */
  public static final String RECOVERY_AUTHOR_NOTE_ATTR = "recoveryAuthorNote";

  /** Outcome class stored with {@link #STAGE_ERROR_CONTEXT_ATTR}. */
  public static final String STAGE_ERROR_OUTCOME_ATTR = "stageErrorOutcomeClass";

  /** Stage id that produced the halt; kept when a causal reopen moves currentStageId. */
  public static final String STAGE_ERROR_FAILED_STAGE_ATTR = "stageErrorFailedStageId";

  /** Compiler skill identified by the failed attempt's execution signals. */
  public static final String STAGE_ERROR_FAILED_SKILL_ATTR = "stageErrorFailedSkillId";

  /** Formatted validation findings from the halt, when present. */
  public static final String STAGE_ERROR_FINDINGS_ATTR = "stageErrorFindings";

  /** Content hash of the durable recovery evidence for the current validation halt. */
  public static final String RECOVERY_EVIDENCE_REF_ATTR = "recoveryEvidenceRef";

  /** Recovery evidence whose design-input retry must start from a clean model turn. */
  public static final String DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR =
      "designInputCleanRebuildEvidenceRef";

  /** Recovery evidence whose design-planning retry must start from a clean model turn. */
  public static final String DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR =
      "designPlanCleanRebuildEvidenceRef";

  /** Typed brief corrections proposed by recovery for the next requirement-analysis repair turn. */
  public static final String PROPOSED_BRIEF_CHANGES_ATTR = "proposedBriefChanges";

  /**
   * Content hash of the approved brief revision superseded by the latest brief repair approval.
   */
  public static final String SUPERSEDED_BRIEF_CONTENT_HASH_ATTR = "supersededBriefContentHash";

  /**
   * Content hashes of derived compile artifacts invalidated by the latest brief repair approval.
   */
  public static final String SUPERSEDED_ARTIFACT_HASHES_ATTR = "supersededArtifactHashes";

  private static final List<Kind> SUPERSEDED_DERIVED_ARTIFACT_KINDS =
      List.of(
          Kind.IMPLEMENTATION_PLAN,
          Kind.CHAIN_PLAN_GRAPH,
          Kind.CHAIN_SEMANTIC_REVISION,
          Kind.IDS_DOCUMENT,
          Kind.GRAPH_PATCH_ARTIFACT,
          Kind.GRAPH_ASSEMBLY_RESULT,
          Kind.COMPILER_VALIDATION_BUNDLE,
          Kind.DESIGN_PLAN_CONTRACT,
          Kind.DESIGN_EXECUTION_PLAN,
          Kind.ORDERED_GRAPH_PATCHES,
          Kind.MAPPING_ENVELOPE);

  /** Typed {@code RecoveryCauseCode} name stored with the halt. */
  public static final String STAGE_ERROR_CAUSE_CODE_ATTR = "stageErrorCauseCode";

  /** Complete cause used by the recovery ledger, retained in the durable halt evidence. */
  public static final String STAGE_ERROR_CAUSE_JSON_ATTR = "stageErrorCauseJson";
  public static final String PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR = "producerCleanRebuildEvidenceRef";

  /** Catalog-resolution requested fact, when the cause carries one. */
  public static final String STAGE_ERROR_REQUESTED_FACT_ATTR = "stageErrorRequestedFact";

  /**
   * Producer preparing a replacement while accepted outputs remain preserved.
   */
  public static final String RECOVERY_PREPARING_OWNER_ATTR = "recoveryPreparingOwner";
  public static final String ACCEPTED_SOURCE_BRIEF_REF_ATTR = "acceptedSourceBriefRef";

  /** Content hash of the accepted candidate before causal reopen. */
  public static final String PRIOR_CANDIDATE_ATTR = "priorCandidate";

  /**
   * Approval CTA when requirement-analysis reopens with halt evidence and emits a repaired brief.
   */
  public static final String BRIEF_REPAIR_APPROVAL_PROMPT =
      "Approve this updated brief to rebuild the plan, or describe what to change.";

  public static final String CAUSAL_REOPEN_REASON_PREFIX = "causal reopen of ";
  public static final int MAX_CAUSAL_REOPENS = 2;
  private static final String NON_TECHNICAL_FAILURE_EVIDENCE_PREFIX = "\u0000non-technical:";

  /** Cap on the candidate payload handed to the turn that answers a question about it. */
  private static final int MAX_APPROVAL_CANDIDATE_CHARS = 8_000;

  private static final String HALT_FOLLOW_UP_INPUT_PREFIX = "halt-follow-up-";
  private static final ObjectMapper HALT_EVIDENCE_JSON = new ObjectMapper();
  private static final Duration DEFAULT_CACHE_IDLE_TIMEOUT = Duration.ofHours(1);
  private static final String UNUSABLE_BINDING_ANSWER_BODY =
      "That answer does not identify one catalog operation for this interaction. "
          + "Name the catalog service or operation it should use.";

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final StageCapabilityRegistry capabilities;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final CompilerRunPinResolver compilerRunPinResolver;
  private final Clock clock;
  private final ApprovalPrompts approvalPrompts;
  /** Optional; when present, IDS approval also offers a storage download link. */
  private final S3Service s3Service;
  private final Map<String, ProductPipelineProfile> profilesByRun;
  private final Map<String, RunManifest> manifestsByRun;
  private final Map<String, Map<String, Object>> attributesByRun;
  private final Map<String, Integer> technicalRetriesByStage;
  private final ProductPipelineStageExecutor stageExecutor;

  /** Shared with the stage executor; also answers questions typed at a pause this run waits at. */
  private final FailureNarrative failureNarrative;

  private final RecoveryAttemptLedger recoveryLedger;
  private final RecoveryOutcomeTelemetry recoveryTelemetry;
  private final MappingTurnAdapter mappingTurnAdapter;
  private final MappingTurnTelemetry mappingTurnTelemetry;
  private org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;
  private RequirementDraftStore requirementDrafts;

  ProductPipelineRunSupport withConversations(
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.conversations = conversations;
    this.stageExecutor.withConversations(conversations);
    return this;
  }

  public ProductPipelineRunSupport withNativeRequirementCapture(
      RequirementDraftStore drafts,
      org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore feedback) {
    this.requirementDrafts = drafts;
    drafts.useRunJournal(runStore);
    this.stageExecutor.withNativeRequirementCapture(drafts, feedback);
    return this;
  }

  private org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget llmBudget;

  public ProductPipelineRunSupport withLlmBudget(
      org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget budget) {
    this.llmBudget = budget;
    this.stageExecutor.withLlmBudget(budget);
    return this;
  }

  ProductPipelineRunSupport withRecoveryControl(
      org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl control) {
    this.stageExecutor.withRecoveryControl(control);
    return this;
  }


  /** Fluent builder for the optional collaborators; required ones are constructor args. */
  public static Builder builder(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock) {
    return new Builder(runStore, artifactStore, capabilities, clock);
  }

  public static final class Builder {
    private final ProductPipelineRunStore runStore;
    private final ProductPipelineArtifactStore artifactStore;
    private final StageCapabilityRegistry capabilities;
    private final Clock clock;
    private ProductPipelineProfileCatalog profileCatalog;
    private CompilerRunPinResolver compilerRunPinResolver;
    private ApprovalPrompts approvalPrompts;
    private S3Service s3Service;
    private FailureNarrative failureNarrative;
    private Duration cacheIdleTimeout = DEFAULT_CACHE_IDLE_TIMEOUT;
    private int repeatedFailureThreshold = 2;
    private RecoveryAttemptLedger recoveryLedger;
    private RecoveryOutcomeTelemetry recoveryTelemetry;
    private MappingTurnAdapter mappingTurnAdapter;
    private MappingTurnTelemetry mappingTurnTelemetry;
    private MappingRepairCaptureValidator mappingRepairCaptureValidator;
    private ObjectMapper objectMapper;
    private SchemaResourceLoader schemaResourceLoader;
    private SchemaRefResolver schemaRefResolver;
    private DeterministicElementSchemaService schemaService;

    private Builder(
        ProductPipelineRunStore runStore,
        ProductPipelineArtifactStore artifactStore,
        StageCapabilityRegistry capabilities,
        Clock clock) {
      this.runStore = runStore;
      this.artifactStore = artifactStore;
      this.capabilities = capabilities;
      this.clock = clock;
    }

    public Builder profileCatalog(ProductPipelineProfileCatalog profileCatalog) {
      this.profileCatalog = profileCatalog;
      return this;
    }

    public Builder compilerRunPinResolver(CompilerRunPinResolver compilerRunPinResolver) {
      this.compilerRunPinResolver = compilerRunPinResolver;
      return this;
    }

    public Builder approvalPrompts(ApprovalPrompts approvalPrompts) {
      this.approvalPrompts = approvalPrompts;
      return this;
    }

    public Builder s3Service(S3Service s3Service) {
      this.s3Service = s3Service;
      return this;
    }

    public Builder failureNarrative(FailureNarrative failureNarrative) {
      this.failureNarrative = failureNarrative;
      return this;
    }

    public Builder cacheIdleTimeout(Duration cacheIdleTimeout) {
      this.cacheIdleTimeout = cacheIdleTimeout;
      return this;
    }

    public Builder repeatedFailureThreshold(int repeatedFailureThreshold) {
      this.repeatedFailureThreshold = repeatedFailureThreshold;
      return this;
    }

    public Builder recoveryLedger(RecoveryAttemptLedger recoveryLedger) {
      this.recoveryLedger = recoveryLedger;
      return this;
    }

    public Builder recoveryTelemetry(RecoveryOutcomeTelemetry recoveryTelemetry) {
      this.recoveryTelemetry = recoveryTelemetry;
      return this;
    }

    public Builder mappingTurnAdapter(MappingTurnAdapter mappingTurnAdapter) {
      this.mappingTurnAdapter = mappingTurnAdapter;
      return this;
    }

    public Builder mappingTurnTelemetry(MappingTurnTelemetry mappingTurnTelemetry) {
      this.mappingTurnTelemetry = mappingTurnTelemetry;
      return this;
    }

    public Builder mappingRepairCaptureValidator(
        MappingRepairCaptureValidator mappingRepairCaptureValidator) {
      this.mappingRepairCaptureValidator = mappingRepairCaptureValidator;
      return this;
    }

    public Builder recoveryValidation(
        ObjectMapper objectMapper,
        SchemaResourceLoader schemaResourceLoader,
        SchemaRefResolver schemaRefResolver,
        DeterministicElementSchemaService schemaService) {
      this.objectMapper = objectMapper;
      this.schemaResourceLoader = schemaResourceLoader;
      this.schemaRefResolver = schemaRefResolver;
      this.schemaService = schemaService;
      return this;
    }

    public ProductPipelineRunSupport build() {
      return new ProductPipelineRunSupport(
          runStore,
          artifactStore,
          capabilities,
          profileCatalog,
          compilerRunPinResolver,
          clock,
          approvalPrompts,
          s3Service,
          failureNarrative,
          cacheIdleTimeout,
          repeatedFailureThreshold,
          recoveryLedger,
          recoveryTelemetry,
          mappingTurnAdapter,
          mappingTurnTelemetry,
          mappingRepairCaptureValidator,
          objectMapper,
          schemaResourceLoader,
          schemaRefResolver,
          schemaService);
    }
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      ApprovalPrompts approvalPrompts,
      S3Service s3Service,
      FailureNarrative failureNarrative,
      Duration cacheIdleTimeout,
      int repeatedFailureThreshold,
      RecoveryAttemptLedger recoveryLedger,
      RecoveryOutcomeTelemetry recoveryTelemetry,
      MappingTurnAdapter mappingTurnAdapter,
      MappingTurnTelemetry mappingTurnTelemetry,
      MappingRepairCaptureValidator mappingRepairCaptureValidator,
      ObjectMapper objectMapper,
      SchemaResourceLoader schemaResourceLoader,
      SchemaRefResolver schemaRefResolver,
      DeterministicElementSchemaService schemaService) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.profileCatalog = profileCatalog;
    this.compilerRunPinResolver = compilerRunPinResolver;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalPrompts = approvalPrompts == null ? new ApprovalPrompts() : approvalPrompts;
    this.s3Service = s3Service;
    this.profilesByRun = idleCache(cacheIdleTimeout);
    this.manifestsByRun = idleCache(cacheIdleTimeout);
    this.attributesByRun = idleCache(cacheIdleTimeout);
    this.technicalRetriesByStage = idleCache(cacheIdleTimeout);
    this.failureNarrative = failureNarrative == null ? new FailureNarrative() : failureNarrative;
    this.recoveryLedger = recoveryLedger == null ? new RecoveryAttemptLedger() : recoveryLedger;
    this.recoveryTelemetry =
        recoveryTelemetry == null ? new RecoveryOutcomeTelemetry() : recoveryTelemetry;
    this.mappingTurnAdapter = mappingTurnAdapter;
    this.mappingTurnTelemetry = mappingTurnTelemetry;
    this.stageExecutor =
        new ProductPipelineStageExecutor(
            runStore,
            artifactStore,
            this.capabilities,
            clock,
            profilesByRun,
            manifestsByRun,
            attributesByRun,
            technicalRetriesByStage,
            this.approvalPrompts,
            this.failureNarrative,
            repeatedFailureThreshold,
            this.recoveryLedger,
            this.recoveryTelemetry,
            mappingRepairCaptureValidator,
            objectMapper,
            schemaResourceLoader,
            schemaRefResolver,
            schemaService);
    this.stageExecutor.bindClarificationSource(this);
  }

  /** Single-stage execution seam used by Flow. */
  public StageExecutor stageExecutor() {
    return stageExecutor;
  }

  /**
   * Restores this stage's retry count from the run journal. Flow checkpoints can lag behind a
   * committed failure or contain the previous stage's count.
   */
  public int restoreTechnicalRetryCount(String runId, String stageId) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(stageId, "stageId");
    String key = stageRetryKey(runId, stageId);
    ProductPipelineRunDocument doc = requireRun(runId);
    int used = consecutiveTechnicalRetries(runId, doc.attempts(), doc.transitions()).getOrDefault(key, 0);
    if (used <= 0) {
      technicalRetriesByStage.remove(key);
    } else {
      technicalRetriesByStage.put(key, used);
    }
    return used;
  }

  /** Returns the retry policy pinned for the current stage of a run. */
  public RetryPolicy retryPolicy(String runId, String stageId) {
    ProductPipelineProfile profile = profilesByRun.get(runId);
    if (profile == null) {
      throw new IllegalStateException("no profile pinned for run " + runId);
    }
    return profile.stages().stream()
        .filter(stage -> stage.stageId().equals(stageId))
        .findFirst()
        .map(ProfileStage::retry)
        .orElseThrow(() -> new IllegalStateException("no stage " + stageId + " in profile " + profile.profileId()));
  }

  /**
   * Creates the durable run and hydrates process-local pins without advancing. Flow owns the first
   * stage execution for a persisted instance.
   */
  public ProductPipelineRunDocument bootstrap(StartOrResumeCommand command, String flowInstanceId) {
    Objects.requireNonNull(command, "command");
    if (flowInstanceId == null || flowInstanceId.isBlank()) {
      throw new IllegalArgumentException("flowInstanceId is required");
    }
    Optional<ProductPipelineRunDocument> existing =
        runStore.loadByConversation(command.conversationId());
    if (existing.isPresent()) {
      hydrateCaches(existing.get(), command);
      return existing.get();
    }
    profilesByRun.put(command.runId(), command.profile());
    manifestsByRun.put(command.runId(), command.runManifest());
    attributesByRun.put(command.runId(), new ConcurrentHashMap<>());
    verifyCompilerPin(command.runManifest());

    Revision manifestRevision =
        artifactStore.append(
            new AppendCommand(
                command.runId(),
                Kind.RUN_MANIFEST,
                "1",
                "product-pipeline-runtime",
                "1",
                command.runManifest(),
                List.of(),
                null,
                provenance(command.runId(), "bootstrap", null)));

    List<StageSnapshot> stages =
        command.profile().stages().stream()
            .map(
                stage ->
                    new StageSnapshot(stage.stageId(), StageStatus.PENDING, List.of(), null))
            .toList();
    ProfileStage first = command.profile().stages().get(0);
    RunSnapshot snapshot =
        new RunSnapshot(
            command.runId(),
            command.conversationId(),
            1L,
            RunStatus.RUNNING,
            first.stageId(),
            stages,
            manifestRevision.reference(),
            flowInstanceId);
    return runStore.create(snapshot);
  }

  /** Returns restart points backed by the current run's durable approval evidence. */
  public List<RestartCheckpoint> availableRestartCheckpoints(String conversationId) {
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(conversationId)
            .orElseThrow(
                () -> new IllegalArgumentException("unknown conversation: " + conversationId));
    List<RestartCheckpoint> available = new ArrayList<>();
    available.add(RestartCheckpoint.BEGINNING);
    if (hasCurrentApproval(doc, RestartCheckpoint.APPROVED_REQUIREMENTS)) {
      available.add(RestartCheckpoint.APPROVED_REQUIREMENTS);
    }
    if (hasCurrentApproval(doc, RestartCheckpoint.APPROVED_PLAN)) {
      available.add(RestartCheckpoint.APPROVED_PLAN);
    }
    return List.copyOf(available);
  }

  /**
   * Clones a checkpoint into an unbound child run. The caller starts the fresh Flow instance and
   * owns the conversation-pointer CAS.
   */
  public PreparedRestart prepareCheckpointRestart(
      RestartRunCommand command, String flowInstanceId) {
    Objects.requireNonNull(command, "command");
    if (flowInstanceId == null || flowInstanceId.isBlank()) {
      throw new IllegalArgumentException("flowInstanceId is required");
    }
    ProductPipelineRunDocument parent =
        runStore
            .load(command.parentRunId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown parent run: " + command.parentRunId()));
    if (!command.conversationId().equals(parent.run().conversationId())) {
      throw new IllegalArgumentException("parent run belongs to another conversation");
    }
    if (parent.run().runRevision() != command.parentRunRevision()) {
      throw new org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException(
          "expected parent runRevision "
              + command.parentRunRevision()
              + " but was "
              + parent.run().runRevision());
    }
    Optional<ProductPipelineRunDocument> prepared = runStore.load(command.childRunId());
    if (prepared.isPresent()) {
      ProductPipelineRunDocument child = prepared.orElseThrow();
      child.appliedCommand(command.commandId(), command.commandPayloadHash());
      return new PreparedRestart(child, loadRunManifest(child));
    }
    if (command.checkpoint() != RestartCheckpoint.BEGINNING
        && !hasCurrentApproval(parent, command.checkpoint())) {
      throw new IllegalArgumentException(
          "restart checkpoint is not available: " + command.checkpoint());
    }

    RunManifest parentManifest = loadRunManifest(parent);
    RunManifest childManifest =
        new RunManifest(
            command.childRunId(),
            parent.run().runId(),
            List.of(),
            parentManifest.runtimeSelection(),
            parentManifest.profileId(),
            parentManifest.profileVersion(),
            parentManifest.profileDigest(),
            parentManifest.referenceBaselineId(),
            parentManifest.referenceBaselineDigest(),
            parentManifest.dependencyClosure(),
            parentManifest.dependencyClosureDigest(),
            parentManifest.knowledgePackage(),
            parentManifest.languageVersion(),
            parentManifest.artifactSchemaVersions(),
            parentManifest.compilerRunPin(),
            parentManifest.responseLocale());
    Revision childManifestRevision =
        artifactStore.append(
            new AppendCommand(
                command.childRunId(),
                Kind.RUN_MANIFEST,
                "1",
                "product-pipeline-restart",
                "1",
                childManifest,
                List.of(),
                null,
                restartProvenance(command, parentManifest, "bootstrap")));

    Map<String, Revision> sourceRevisions = sourceRevisions(parent.run().runId());
    Set<String> selectedIds = selectedArtifactIds(parent, command.checkpoint(), sourceRevisions);
    parentManifest.sourceReferences().stream()
        .map(Reference::artifactId)
        .forEach(selectedIds::add);
    includeInputClosure(selectedIds, sourceRevisions);
    Map<String, Reference> copied = new LinkedHashMap<>();
    if (parent.run().runManifestRef() != null) {
      copied.put(parent.run().runManifestRef().artifactId(), childManifestRevision.reference());
    }
    sourceRevisions.values().stream()
        .filter(revision -> selectedIds.contains(revision.artifactId()))
        .filter(revision -> revision.kind() != Kind.RUN_MANIFEST)
        .sorted(java.util.Comparator.comparingLong(Revision::sequence))
        .forEach(
            revision ->
                copied.put(
                    revision.artifactId(),
                    copyRestartArtifact(command, parentManifest, revision, copied).reference()));

    List<Reference> childSourceReferences =
        parentManifest.sourceReferences().stream()
            .map(reference -> requireCopied(reference, copied))
            .toList();
    childManifest = copyManifestWithSources(childManifest, childSourceReferences);
    Revision finalManifestRevision =
        artifactStore.append(
            new AppendCommand(
                command.childRunId(),
                Kind.RUN_MANIFEST,
                "1",
                "product-pipeline-restart",
                "1",
                childManifest,
                childSourceReferences,
                childManifestRevision.artifactId(),
                restartProvenance(command, parentManifest, "checkpoint")));

    String resumeStageId = resumeStageId(command.profile(), command.checkpoint());
    List<StageSnapshot> stages =
        restartedStages(parent, command.profile(), command.checkpoint(), resumeStageId, copied);
    RunSnapshot snapshot =
        new RunSnapshot(
            command.childRunId(),
            command.conversationId(),
            1L,
            RunStatus.RUNNING,
            resumeStageId,
            stages,
            finalManifestRevision.reference(),
            flowInstanceId);
    ProductPipelineRunDocument child =
        runStore.createUnbound(
            snapshot, command.commandId(), command.commandPayloadHash());
    hydrateCaches(
        child,
        new StartOrResumeCommand(
            command.conversationId(),
            command.childRunId(),
            command.profile(),
            childManifest));
    verifyCompilerPin(childManifest);
    return new PreparedRestart(child, childManifest);
  }

  private static RunManifest copyManifestWithSources(
      RunManifest manifest, List<Reference> sourceReferences) {
    return new RunManifest(
        manifest.runId(),
        manifest.parentRunId(),
        sourceReferences,
        manifest.runtimeSelection(),
        manifest.profileId(),
        manifest.profileVersion(),
        manifest.profileDigest(),
        manifest.referenceBaselineId(),
        manifest.referenceBaselineDigest(),
        manifest.dependencyClosure(),
        manifest.dependencyClosureDigest(),
        manifest.knowledgePackage(),
        manifest.languageVersion(),
        manifest.artifactSchemaVersions(),
        manifest.compilerRunPin(),
        manifest.responseLocale());
  }

  private RunManifest loadRunManifest(ProductPipelineRunDocument doc) {
    Reference ref = doc.run().runManifestRef();
    return Optional.ofNullable(ref)
        .flatMap(reference -> artifactStore.get(doc.run().runId(), reference))
        .or(() -> artifactStore.latest(doc.run().runId(), Kind.RUN_MANIFEST))
        .map(revision -> artifactStore.payload(revision, RunManifest.class))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "RUN_MANIFEST is missing for " + doc.run().runId()));
  }

  private boolean hasCurrentApproval(
      ProductPipelineRunDocument doc, RestartCheckpoint checkpoint) {
    String stageId = checkpoint.checkpointStageId();
    if (stageId == null) {
      return true;
    }
    StageSnapshot stage =
        doc.run().stages().stream()
            .filter(candidate -> stageId.equals(candidate.stageId()))
            .findFirst()
            .orElse(null);
    if (stage == null
        || stage.status() != StageStatus.SUCCEEDED
        || stage.approvedArtifactId() == null
        || stage.approvedArtifactId().isBlank()) {
      return false;
    }
    Reference approved =
        java.util.stream.Stream.concat(
                stage.outputRefs().stream(), stage.candidateReferences().stream())
            .filter(ref -> stage.approvedArtifactId().equals(ref.artifactId()))
            .findFirst()
            .orElse(stage.approvableReference());
    if (approved == null || !stage.approvedArtifactId().equals(approved.artifactId())) {
      return false;
    }
    return stage.outputRefs().stream()
        .filter(ref -> ref.kind() == Kind.APPROVAL_RECORD)
        .map(ref -> artifactStore.get(doc.run().runId(), ref))
        .flatMap(Optional::stream)
        .anyMatch(revision -> approvalTargets(revision, approved));
  }

  private boolean approvalTargets(Revision revision, Reference expected) {
    if ("2".equals(revision.schemaVersion())) {
      ApprovalRecordV2 record = artifactStore.payload(revision, ApprovalRecordV2.class);
      return expected.equals(record.target())
          && expected.contentHash().equals(record.targetContentHash());
    }
    ApprovalRecord record = artifactStore.payload(revision, ApprovalRecord.class);
    return expected.equals(record.target())
        && expected.contentHash().equals(record.targetContentHash());
  }

  private Map<String, Revision> sourceRevisions(String runId) {
    Map<String, Revision> revisions = new LinkedHashMap<>();
    for (Kind kind : Kind.values()) {
      for (Revision revision : artifactStore.history(runId, kind)) {
        revisions.put(revision.artifactId(), revision);
      }
    }
    return revisions;
  }

  private Set<String> selectedArtifactIds(
      ProductPipelineRunDocument parent,
      RestartCheckpoint checkpoint,
      Map<String, Revision> revisions) {
    Set<String> selected = new java.util.LinkedHashSet<>();
    if (checkpoint == RestartCheckpoint.BEGINNING) {
      revisions.values().stream()
          .filter(revision -> revision.kind() == Kind.USER_INPUT)
          .min(java.util.Comparator.comparingLong(Revision::sequence))
          .map(Revision::artifactId)
          .ifPresent(selected::add);
      return selected;
    }
    for (StageSnapshot stage : parent.run().stages()) {
      addReferenceIds(selected, stage.outputRefs());
      addReferenceIds(selected, stage.candidateReferences());
      if (stage.approvableReference() != null) {
        selected.add(stage.approvableReference().artifactId());
      }
      if (checkpoint.checkpointStageId().equals(stage.stageId())) {
        break;
      }
    }
    return selected;
  }

  private static void addReferenceIds(Set<String> selected, List<Reference> references) {
    references.stream().map(Reference::artifactId).forEach(selected::add);
  }

  private static void includeInputClosure(
      Set<String> selected, Map<String, Revision> revisions) {
    java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(selected);
    while (!pending.isEmpty()) {
      Revision revision = revisions.get(pending.removeFirst());
      if (revision == null) {
        continue;
      }
      for (Reference input : revision.inputs()) {
        if (selected.add(input.artifactId())) {
          pending.addLast(input.artifactId());
        }
      }
    }
  }

  private Revision copyRestartArtifact(
      RestartRunCommand command,
      RunManifest parentManifest,
      Revision source,
      Map<String, Reference> copied) {
    List<Reference> inputs = source.inputs().stream().map(ref -> requireCopied(ref, copied)).toList();
    Object payload = source.payload();
    if (source.kind() == Kind.APPROVAL_RECORD) {
      payload = copyApprovalRecord(source, copied);
    }
    return artifactStore.append(
        new AppendCommand(
            command.childRunId(),
            source.kind(),
            source.schemaVersion(),
            "product-pipeline-restart",
            "1",
            payload,
            inputs,
            null,
            restartProvenance(
                command,
                parentManifest,
                source.provenance() == null ? "checkpoint" : source.provenance().stageId())));
  }

  private Object copyApprovalRecord(Revision source, Map<String, Reference> copied) {
    if ("2".equals(source.schemaVersion())) {
      ApprovalRecordV2 record = artifactStore.payload(source, ApprovalRecordV2.class);
      return new ApprovalRecordV2(
          requireCopied(record.target(), copied),
          record.targetContentHash(),
          record.approvedCandidates().stream().map(ref -> requireCopied(ref, copied)).toList(),
          record.actor(),
          record.comment(),
          record.approvedAt(),
          record.bindingResolutionPolicy(),
          record.bindingResolutionPolicyHash(),
          record.subjectArtifactKind(),
          record.subjectSchemaVersion(),
          record.subjectRevisionId(),
          record.subjectSha256(),
          record.compilerContractVersion(),
          record.compilerContractSha256(),
          record.attachmentKeys(),
          record.specSystemTypes());
    }
    ApprovalRecord record = artifactStore.payload(source, ApprovalRecord.class);
    Reference target = requireCopied(record.target(), copied);
    return new ApprovalRecord(
        target, target.contentHash(), record.actor(), record.comment(), record.approvedAt());
  }

  private static Reference requireCopied(Reference source, Map<String, Reference> copied) {
    Reference mapped = copied.get(source.artifactId());
    if (mapped == null) {
      throw new IllegalStateException(
          "restart artifact input was not copied: " + source.artifactId());
    }
    return mapped;
  }

  private static List<StageSnapshot> restartedStages(
      ProductPipelineRunDocument parent,
      ProductPipelineProfile profile,
      RestartCheckpoint checkpoint,
      String resumeStageId,
      Map<String, Reference> copied) {
    int checkpointIndex =
        checkpoint.checkpointStageId() == null
            ? -1
            : indexOfStage(profile, checkpoint.checkpointStageId());
    List<StageSnapshot> restarted = new ArrayList<>();
    for (int index = 0; index < profile.stages().size(); index++) {
      ProfileStage profileStage = profile.stages().get(index);
      if (index <= checkpointIndex) {
        StageSnapshot source =
            parent.run().stages().stream()
                .filter(stage -> profileStage.stageId().equals(stage.stageId()))
                .findFirst()
                .orElseThrow();
        List<Reference> outputs = copyReferences(source.outputRefs(), copied);
        List<Reference> candidates = copyReferences(source.candidateReferences(), copied);
        Reference approvable =
            source.approvableReference() == null
                ? null
                : requireCopied(source.approvableReference(), copied);
        Reference approved =
            source.approvedArtifactId() == null
                ? null
                : copied.get(source.approvedArtifactId());
        restarted.add(
            new StageSnapshot(
                source.stageId(),
                StageStatus.SUCCEEDED,
                outputs,
                approved == null ? null : approved.artifactId(),
                candidates,
                approvable,
                source.candidateRevision()));
      } else if (profileStage.stageId().equals(resumeStageId)) {
        restarted.add(
            new StageSnapshot(
                profileStage.stageId(), StageStatus.RUNNING, List.of(), null));
      } else {
        restarted.add(
            new StageSnapshot(
                profileStage.stageId(), StageStatus.PENDING, List.of(), null));
      }
    }
    return List.copyOf(restarted);
  }

  private static String resumeStageId(
      ProductPipelineProfile profile, RestartCheckpoint checkpoint) {
    if (checkpoint == RestartCheckpoint.BEGINNING) {
      if (profile.stages().isEmpty()) {
        throw new IllegalArgumentException("profile has no stages");
      }
      return profile.stages().get(0).stageId();
    }
    indexOfStage(profile, checkpoint.resumeStageId());
    return checkpoint.resumeStageId();
  }

  private static int indexOfStage(ProductPipelineProfile profile, String stageId) {
    for (int index = 0; index < profile.stages().size(); index++) {
      if (stageId.equals(profile.stages().get(index).stageId())) {
        return index;
      }
    }
    throw new IllegalArgumentException("profile does not contain stage " + stageId);
  }

  private static List<Reference> copyReferences(
      List<Reference> references, Map<String, Reference> copied) {
    return references.stream()
        .map(reference -> copied.get(reference.artifactId()))
        .filter(Objects::nonNull)
        .toList();
  }

  private static ArtifactProvenance restartProvenance(
      RestartRunCommand command, RunManifest manifest, String stageId) {
    return new ArtifactProvenance(
        command.childRunId(),
        stageId == null || stageId.isBlank() ? "checkpoint" : stageId,
        manifest.profileId(),
        manifest.profileVersion(),
        manifest.profileDigest(),
        "checkpoint-restart",
        "1",
        manifest.dependencyClosureDigest());
  }

  /**
   * Applies Continue, Retry, and Reopen without recursive stage selection. Wait, fail, and complete
   * decisions already committed their evidence in the stage module.
   */
  public Multi<PipelineSignal> applyStageLifecycle(String runId, StageExecutionResult result) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(result, "result");
    if (result.decision() instanceof StageDecision.Retry) {
      return Multi.createFrom().iterable(result.signals());
    }
    return applyStageDecision(runId, result);
  }

  public String currentStageId(String runId) {
    return requireRun(runId).run().currentStageId();
  }

  public RunSnapshot currentRunSnapshot(String runId) {
    return requireRun(runId).run();
  }

  /**
   * Runtime seam for {@link SemanticRecoveryState}: run, stage, gate, stripped prompt, remaining
   * attempts. Card actions are empty; fill them from {@code ChatEvent.actionsForClarify}.
   */
  public SemanticRecoveryState captureSemanticRecoveryState(String runId) {
    ProductPipelineRunDocument doc = requireRun(runId);
    String prompt = latestWaitingForInputPrompt(doc);
    String gateId = PipelineGates.gateOf(prompt).orElse("");
    String stageId = doc.run().currentStageId() == null ? "" : doc.run().currentStageId();
    StageStatus stageStatus =
        doc.run().stages().stream()
            .filter(stage -> stageId.equals(stage.stageId()))
            .map(StageSnapshot::status)
            .findFirst()
            .orElse(StageStatus.PENDING);
    String owner = stringAttribute(runId, DIAGNOSED_OWNER_STAGE_ATTR).orElse(stageId);
    if (owner.isBlank()) {
      owner = stageId;
    }
    RecoveryCause cause = currentRecoveryCause(runId);
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    return SemanticRecoveryState.captureRuntime(
        doc.run().status(),
        stageId,
        stageStatus,
        gateId,
        PipelineGates.strip(prompt),
        recoveryLedger.remaining(doc.transitions(), key, InputOrigin.TRUSTED));
  }

  /** Restores caches and durable waits without selecting or running the next stage. */
  public Multi<PipelineSignal> restoreForExternalWorkflow(StartOrResumeCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc =
                  runStore
                      .loadByConversation(command.conversationId())
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "unknown conversation: " + command.conversationId()));
              hydrateCaches(doc, command);
              verifyCompilerPin(manifestsByRun.get(doc.run().runId()));
              if (isTerminalRunStatus(doc.run().status())) {
                return Multi.createFrom().item(new PipelineSignal.Completed(doc.run().status()));
              }
              if (doc.run().status() == RunStatus.WAITING_FOR_INPUT) {
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.WaitingForInput(
                            doc.run().currentStageId(), latestWaitingForInputPrompt(doc)));
              }
              if (doc.run().status() == RunStatus.WAITING_FOR_APPROVAL) {
                StageSnapshot waitingStage =
                    doc.run().stages().stream()
                        .filter(s -> s.stageId().equals(doc.run().currentStageId()))
                        .findFirst()
                        .orElseThrow();
                Reference candidate =
                    waitingStage.approvableReference() != null
                        ? waitingStage.approvableReference()
                        : waitingStage.outputRefs().stream().findFirst().orElseThrow();
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.WaitingForApproval(
                            doc.run().currentStageId(),
                            candidate,
                            approvalPromptFor(doc.run().runId(), doc.run().currentStageId())));
              }
              if (doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT) {
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.WaitingForImplement(
                            doc.run().currentStageId(),
                            approvedPlanContentHash(doc.run().runId()).orElse("")));
              }
              return Multi.createFrom().empty();
            });
  }

  /** Records input without selecting or running the next stage. */
  public Multi<PipelineSignal> recordInput(AcceptInputCommand input) {
    Objects.requireNonNull(input, "command");
    AcceptInputCommand command = bindRepairOffer(input);
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              ensureDurablePinsLoaded(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return Multi.createFrom().empty();
              }
              if (PipelineGates.CONTINUE_REPAIR_ACTION.equals(PipelineGates.haltCardAction(command.text()))) {
                return acceptRepairAllowance(doc, command);
              }
              if (doc.run().status() != RunStatus.WAITING_FOR_INPUT
                  && doc.run().status() != RunStatus.WAITING_FOR_APPROVAL) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "run is not waiting for input or approval: " + doc.run().status()));
              }
              if (isStopWithReportAction(doc, command.text())) {
                return stopWithReport(doc, command);
              }
              if (isEscalatedAction(doc, command.text(), PipelineGates.DROP_ELEMENT_ACTION)) {
                return dropBlockingElement(doc, command);
              }
              if (PipelineGates.isHaltCardAction(command.text())) {
                return acceptTypedInput(doc, command);
              }
              if (isHaltFollowUp(doc, command.text())) {
                return recordHaltFollowUp(doc, command);
              }
              if (isOwnerChoicePick(doc, command.text())) {
                return recordOwnerChoice(doc, command);
              }
              if (isTypedAtApprovalCard(doc, command.text())) {
                return interpretApprovedMappingTurnOrFallback(
                    doc, command, () -> answerApprovalQuestionOrRefine(doc, command));
              }
              if (isMappingGapWait(doc)) {
                String canonical = canonicalMappingGapAction(command.text());
                if ("pass_through".equals(canonical)
                    || "describe_mappings".equals(canonical)
                    || canonical.isBlank()) {
                  return recordMappingGapInput(doc, command);
                }
                return interpretMappingGapDescribeTurn(doc, command);
              }
              return interpretApprovedMappingTurnOrFallback(
                  doc, command, () -> acceptTypedInput(doc, command));
            });
  }

  /** Transport IDs do not create extra grants for the same immutable offer. */
  public static AcceptInputCommand bindRepairOffer(AcceptInputCommand command) {
    if (!PipelineGates.CONTINUE_REPAIR_ACTION.equals(PipelineGates.haltCardAction(command.text()))) {
      return command;
    }
    return new AcceptInputCommand(command.runId(), command.text(), command.text() + ":accept-input",
        CanonicalPayloadHash.sha256Hex(Map.of("runId", command.runId(), "offer", command.text(),
            "targeted", 2, "clean", 2)), command.origin());
  }

  /** Reads the defect from the offered revision's saved evidence, never from client text. */
  public static String repairOfferCommand(ProductPipelineRunDocument doc) {
    return PipelineGates.continueRepairCommand(doc.run().runId(), doc.run().runRevision(),
        doc.run().currentStageId() + ":" + RecoveryAttemptLedger.evidenceIdentity(repairOfferCause(doc)));
  }

  private static RecoveryCause repairOfferCause(ProductPipelineRunDocument doc) {
    String json = doc.attempts().stream()
        .filter(attempt -> doc.run().currentStageId().equals(attempt.stageId()))
        .map(StageAttempt::failureEvidence).filter(Objects::nonNull)
        .map(ProductPipelineRunSupport::readHaltEvidence)
        .map(evidence -> evidence.get(STAGE_ERROR_CAUSE_JSON_ATTR))
        .filter(String.class::isInstance).map(String.class::cast).filter(text -> !text.isBlank())
        .reduce((first, second) -> second).orElse("");
    try {
      return HALT_EVIDENCE_JSON.readValue(json, RecoveryCause.class);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot bind the repair offer to its persisted defect", e);
    }
  }

  private Multi<PipelineSignal> acceptRepairAllowance(ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String prompt = latestWaitingForInputPrompt(doc);
    if (command.origin() != InputOrigin.TRUSTED
        || doc.run().status() != RunStatus.WAITING_FOR_INPUT
        || !PipelineGates.RECOVERY_REPAIR_ALLOWANCE.equals(PipelineGates.gateOf(prompt).orElse(""))
        || !command.text().equals(repairOfferCommand(doc))) {
      return reemitHaltCard(doc);
    }
    if (catalogHasBeenWritten(command.runId())) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN);
    }
    if (recoveryLedger.perRunCeilingReached(doc.transitions())) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.RUN_RECOVERY_CEILING);
    }
    String resourceRefusal = llmBudget == null ? null : llmBudget.refusalForNextRequest(command.runId());
    if (resourceRefusal != null) {
      String evidence = "LLM_RESOURCE_LIMIT: " + resourceRefusal;
      String identity = ToolCallFingerprints.failureSignature(evidence);
      String paused = PipelineGates.tagHaltIdentity(PipelineGates.tagRecoveryDetails(
          PipelineGates.tag(PipelineGates.RECOVERY_RETRY_TECHNICAL,
              resourceRefusal + " Progress is saved. Retry creation authorizes one additional model allowance."),
          evidence, null), identity);
      commitStatus(doc, RunStatus.WAITING_FOR_INPUT, StageStatus.WAITING_FOR_INPUT, doc.run().stages(),
          paused, haltEvidence(attributesByRun.get(command.runId()), null, evidence),
          command.commandId(), command.commandPayloadHash());
      recoveryTelemetry.recordPresented(command.runId(), PipelineGates.RECOVERY_RETRY_TECHNICAL, identity, evidence);
      return Multi.createFrom().item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), paused));
    }
    String owner = doc.run().currentStageId();
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, repairOfferCause(doc), artifact, doc.transitions());
    Map<String, Object> attributes = attributesByRun.get(command.runId());
    attributes.remove(DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    attributes.remove(DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    attributes.remove(PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    try {
      attributes.put(RECOVERY_PENDING_ATTEMPT_ATTR, HALT_EVIDENCE_JSON.writeValueAsString(
          Map.of("stageId", owner, "revision", doc.run().runRevision() + 1)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot persist the granted correction intent", e);
    }
    commitStatus(doc, RunStatus.RUNNING, StageStatus.RUNNING, doc.run().stages(),
        recoveryLedger.recordGrantWithTargetedAttempt(key, artifact), haltEvidence(attributes, null),
        command.commandId(), command.commandPayloadHash());
    return Multi.createFrom().item(new PipelineSignal.Progress(owner, "Additional repair allowance accepted"));
  }

  /**
   * After approval, mapping changes reopen the source draft producer, or analysis in profiles
   * without one. FLOW_CHANGE also reopens the source producer. Queries, other clarifications,
   * confirmations, and stale results keep the wait and plan. Other typed input follows the refine path.
   */
  private Multi<PipelineSignal> interpretApprovedMappingTurnOrFallback(
      ProductPipelineRunDocument doc,
      AcceptInputCommand command,
      Supplier<Multi<PipelineSignal>> fallback) {
    if (mappingTurnAdapter == null || !isAfterRequirementAnalysis(doc)) {
      return fallback.get();
    }
    return Multi.createFrom()
        .deferred(() -> tryApprovedMappingTurn(doc, command).orElseGet(fallback))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private Optional<Multi<PipelineSignal>> tryApprovedMappingTurn(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    if (mappingTurnAdapter == null || !isAfterRequirementAnalysis(doc)) {
      return Optional.empty();
    }
    RequirementBrief brief = approvedRequirementBrief(command.runId());
    if (brief == null) {
      return Optional.empty();
    }
    MappingTurnApplication application =
        MappingTurnProcessor.process(brief, command.text(), mappingTurnAdapter);
    if (application.applied()) {
      if (hasRequirementDiscovery(command.runId())) {
        return Optional.of(reopenRequirementDraft(doc, command, selectedRequirementBriefReference(doc)));
      }
      BriefMappingReview.MappingChangeImpact impact =
          BriefMappingReview.afterApprovedMappingChange(brief, application.brief(), null);
      if (impact.briefReopened()) {
        return Optional.of(reopenAfterApprovedMappingChange(doc, command, application.brief()));
      }
      return Optional.of(stayAfterMappingTurn(doc, ""));
    }
    MappingTurnResult result = application.result();
    if (result instanceof MappingTurnResult.Clarification clarification
        && "FLOW_CHANGE".equals(clarification.reason())) {
      return Optional.of(reopenRequirementDraft(doc, command, selectedRequirementBriefReference(doc)));
    }
    var structuralRepair = tryStructuralReminder(doc, command, brief, result);
    if (structuralRepair.isPresent()) {
      return structuralRepair;
    }
    if (result instanceof MappingTurnResult.Query
        || result instanceof MappingTurnResult.Clarification
        || result instanceof MappingTurnResult.ConfirmationRequired) {
      return Optional.of(stayAfterMappingTurn(doc, mappingReplyMessage(doc, command, application)));
    }
    return Optional.empty();
  }

  private Optional<Multi<PipelineSignal>> tryStructuralReminder(ProductPipelineRunDocument doc,
      AcceptInputCommand command, RequirementBrief brief, MappingTurnResult result) {
    if (result instanceof MappingTurnResult.StructuralReminder && !catalogHasBeenWritten(command.runId())) {
      var revision = artifactStore.latest(command.runId(), Kind.CHAIN_SEMANTIC_REVISION)
          .map(stored -> artifactStore.payload(stored,
              org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision.class))
          .orElse(null);
      if (revision != null) {
        var errors = org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureAdapter
            .validateElementIntent(brief, revision,
                new ClasspathCompilerContractRepository().require(revision.compilerContractVersion()));
        if (!errors.isEmpty()) {
          var attributes = attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
          attributes.put(STAGE_ERROR_CONTEXT_ATTR, String.join("\n", errors));
          attributes.put(STAGE_ERROR_FAILED_SKILL_ATTR, "design-input");
          attributes.put(STAGE_ERROR_CAUSE_CODE_ATTR, RecoveryCauseCode.GRAPH_STRUCTURE.name());
          attributes.remove(STAGE_ERROR_CAUSE_JSON_ATTR);
          attributes.put(STAGE_ERROR_FINDINGS_ATTR, String.join("\n", errors));
          attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, command.text());
          if (!shouldCausalReopen(doc, "design-input", command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR)) {
            return Optional.of(refuseWithGuard(doc, command, HaltRecoveryGuard.MAX_CAUSAL_REOPENS));
          }
          return Optional.of(causalReopenOwner(doc, command, "design-input"));
        }
      }
      return Optional.empty();
    }
    return Optional.empty();
  }

  private boolean isAfterRequirementAnalysis(ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    if (profile == null) {
      return false;
    }
    return stageIdsAfter(profile, "requirement-analysis").contains(doc.run().currentStageId());
  }

  private boolean hasRequirementDiscovery(String runId) {
    return profilesByRun.get(runId).stages().stream()
        .anyMatch(stage -> "requirement-discovery".equals(stage.capabilityId()));
  }

  private RequirementBrief approvedRequirementBrief(String runId) {
    Reference ref = selectedRequirementBriefReference(requireRun(runId));
    return ref == null ? null : artifactStore.payload(artifactStore.get(runId, ref).orElseThrow(), RequirementBrief.class);
  }

  /** Selects the accepted brief or an exact source projection saved by an author command. */
  public static Reference selectedRequirementBriefReference(ProductPipelineRunDocument doc) {
    String projection = doc.attempts().stream().map(StageAttempt::failureEvidence).filter(Objects::nonNull)
        .map(ProductPipelineRunSupport::readHaltEvidence)
        .filter(evidence -> evidence.containsKey(ACCEPTED_SOURCE_BRIEF_REF_ATTR))
        .map(evidence -> evidence.get(ACCEPTED_SOURCE_BRIEF_REF_ATTR)).filter(String.class::isInstance)
        .map(String.class::cast).reduce((first, second) -> second).orElse("");
    if (!projection.isBlank()) {
      try {
        Reference ref = HALT_EVIDENCE_JSON.readValue(projection, Reference.class);
        if (ref.kind() != Kind.REQUIREMENT_BRIEF) { throw new IllegalStateException("Source projection must name a brief"); }
        return ref;
      } catch (java.io.IOException e) {
        throw new IllegalStateException("Cannot read the accepted source brief reference", e);
      }
    }
    List<Reference> refs = doc.run().stages().stream()
        .filter(stage -> stage.status() == StageStatus.SUCCEEDED || stage.approvedArtifactId() != null)
        .flatMap(stage -> stage.outputRefs().stream()).filter(ref -> ref.kind() == Kind.REQUIREMENT_BRIEF)
        .distinct().toList();
    return refs.size() == 1 ? refs.getFirst() : null;
  }

  private Multi<PipelineSignal> reopenAfterApprovedMappingChange(
      ProductPipelineRunDocument doc, AcceptInputCommand command, RequirementBrief updated) {
    artifactStore.append(
        new AppendCommand(
            command.runId(),
            Kind.USER_INPUT,
            "1",
            "product-pipeline-runtime",
            "1",
            new UserInput(
                userInputId(command), "requirement-analysis", command.text(), clock.instant()),
            List.of(),
            null,
            provenance(command.runId(), "requirement-analysis", "requirement-analysis")));
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    attributes.put("userText", command.text());
    Reference acceptedBrief = selectedRequirementBriefReference(doc);
    if (acceptedBrief != null) { attributes.put(SUPERSEDED_BRIEF_CONTENT_HASH_ATTR, acceptedBrief.contentHash()); }
    persistCanonicalRequirementBrief(doc, command, updated);
    List<String> supersededArtifactHashes = collectSupersededDerivedArtifactHashes(command.runId());
    if (!supersededArtifactHashes.isEmpty()) {
      attributes.put(SUPERSEDED_ARTIFACT_HASHES_ATTR, supersededArtifactHashes);
    }
    return resetDownstreamAndMoveTo(
        doc, "requirement-analysis", BriefMappingReview.REOPEN_MESSAGE, command);
  }

  private Multi<PipelineSignal> stayAfterMappingTurn(
      ProductPipelineRunDocument doc, String message) {
    Multi<PipelineSignal> wait =
        doc.run().status() == RunStatus.WAITING_FOR_APPROVAL
            ? reemitApprovalCard(doc)
            : reemitHaltCard(doc);
    if (message == null || message.isBlank()) {
      return wait;
    }
    return Multi.createFrom()
        .item((PipelineSignal) new PipelineSignal.Message(message))
        .onCompletion()
        .switchTo(() -> wait);
  }

  private String mappingReplyMessage(
      ProductPipelineRunDocument doc,
      AcceptInputCommand command,
      MappingTurnApplication application) {
    if (application.answer() != null) {
      PauseQuestionResult reply =
          failureNarrative.answerApprovalQuestion(
              command.runId(), responseLocaleOf(command.runId()), command.text(),
              doc.run().currentStageId(), application.answer().rendered());
      return reply.isAnswer() ? reply.answer() : FailureNarrative.NO_EXPLANATION_AVAILABLE;
    }
    return mappingStayMessage(application);
  }

  private static String mappingStayMessage(MappingTurnApplication application) {
    return switch (application.result()) {
      case MappingTurnResult.Clarification clarification -> mappingClarificationMessage(clarification);
      case MappingTurnResult.ConfirmationRequired confirmation ->
          mappingConfirmationMessage(confirmation);
      case null, default -> "";
    };
  }

  private static String mappingClarificationMessage(MappingTurnResult.Clarification clarification) {
    if ("STALE_REVISION".equals(clarification.reason())) {
      return "This mapping change is stale. Send it again against the latest requirement brief.";
    }
    StringBuilder sb = new StringBuilder("Cannot apply the mapping change (");
    sb.append(clarification.reason()).append(").");
    if (!clarification.candidates().isEmpty()) {
      sb.append(" Candidates: ").append(String.join(", ", clarification.candidates())).append('.');
    }
    return sb.toString();
  }

  private static String mappingConfirmationMessage(
      MappingTurnResult.ConfirmationRequired confirmation) {
    if (confirmation.kind() == MappingTurnResult.ConfirmationRequired.Kind.DELETE_LAST_RULE) {
      return "Confirm pass-through before deleting the last rule of mapping intent '"
          + confirmation.mappingIntentId()
          + "'.";
    }
    return "Confirm pass-through before deleting mapping intent '"
        + confirmation.mappingIntentId()
        + "'.";
  }

  private static boolean isMappingGapWait(ProductPipelineRunDocument doc) {
    String gate = PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
    return PipelineGates.MAPPING_GAP.equals(gate)
        || PipelineGates.MAPPING_GAP_DESCRIBE.equals(gate);
  }

  /**
   * Describe-prose on the mapping-gap card is a mapping turn against the current brief. Canonical
   * {@code pass_through} and {@code describe_mappings} stay in {@link #recordMappingGapInput}.
   * Missing adapter or brief fails closed; it does not reopen requirement-analysis.
   */
  private Multi<PipelineSignal> interpretMappingGapDescribeTurn(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    if (mappingTurnAdapter == null) {
      return Multi.createFrom()
          .failure(
              new IllegalStateException("mapping-gap describe requires a mapping-turn adapter"));
    }
    return Multi.createFrom()
        .deferred(() -> applyMappingGapDescribeTurn(doc, command))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private Multi<PipelineSignal> applyMappingGapDescribeTurn(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RequirementBrief brief = approvedRequirementBrief(command.runId());
    if (brief == null) {
      return Multi.createFrom()
          .failure(
              new IllegalStateException(
                  "mapping-gap describe requires a committed RequirementBrief"));
    }
    boolean authoredMappingOmitted = artifactStore.history(command.runId(), Kind.REQUIREMENT_DRAFT).stream()
        .filter(revision -> revision.artifactId().equals(brief.approvedDraftReference()))
        .map(revision -> artifactStore.payload(revision, org.qubership.integration.platform.ai.plan.RequirementDraft.class))
        .anyMatch(draft -> org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator
            .validateAuthoredMappings(draft, brief).isPresent());
    if (MappingGapCoverage.uncovered(brief).isEmpty() || authoredMappingOmitted) {
      // Reassess the stored gate before interpreting an answer as new mapping instructions.
      return acceptTypedInput(doc, command);
    }
    MappingTurnApplication application =
        MappingTurnProcessor.processGap(
            brief, command.text(), mappingTurnAdapter, mappingTurnTelemetry);
    if (application.applied()) {
      persistMappingGapBrief(doc, command, application.brief());
      if (MappingGapCoverage.uncovered(application.brief()).isEmpty()) {
        commitStatus(
            doc,
            RunStatus.RUNNING,
            StageStatus.RUNNING,
            doc.run().stages(),
            "accepted input",
            haltEvidence(attributesByRun.get(command.runId()), null),
            command.commandId(),
            command.commandPayloadHash());
        return Multi.createFrom().empty();
      }
      return stayOnMappingGap(doc, command, application.brief(), "");
    }
    MappingTurnResult result = application.result();
    var structuralRepair = tryStructuralReminder(doc, command, brief, result);
    if (structuralRepair.isPresent()) {
      return structuralRepair.get();
    }
    if (result instanceof MappingTurnResult.Query
        || result instanceof MappingTurnResult.Clarification
        || result instanceof MappingTurnResult.ConfirmationRequired) {
      return stayAfterMappingTurn(doc, mappingReplyMessage(doc, command, application));
    }
    return stayOnMappingGap(doc, command, brief, mappingGapStayReason(application));
  }

  private Multi<PipelineSignal> stayOnMappingGap(
      ProductPipelineRunDocument doc,
      AcceptInputCommand command,
      RequirementBrief brief,
      String reason) {
    String tagged =
        PipelineGates.tag(
            PipelineGates.MAPPING_GAP,
            MappingGapWait.encode(
                null, MappingGapCoverage.readableEdges(MappingGapCoverage.uncovered(brief))));
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        tagged,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    Multi<PipelineSignal> wait =
        Multi.createFrom()
            .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), tagged));
    if (reason == null || reason.isBlank()) {
      return wait;
    }
    return Multi.createFrom()
        .item((PipelineSignal) new PipelineSignal.Message(reason))
        .onCompletion()
        .switchTo(() -> wait);
  }

  private static String mappingGapStayReason(MappingTurnApplication application) {
    String fromResult = mappingStayMessage(application);
    if (fromResult != null && !fromResult.isBlank()) {
      return fromResult;
    }
    if (application.result() instanceof MappingTurnResult.Changes(var operations)) {
      List<String> omitted = new ArrayList<>();
      for (MappingTurnResult.Operation operation : operations) {
        if (operation instanceof MappingTurnResult.AddIntent add && add.rules().isEmpty()) {
          return add.sourceRef()
              + " -> "
              + add.targetRef()
              + " still needs a mapping rule or a skip.";
        }
        if (operation instanceof MappingTurnResult.AddIntent add
            && application.brief().mappingIntents().stream()
                .noneMatch(
                    intent ->
                        add.sourceRef().equals(intent.sourceRef())
                            && add.targetRef().equals(intent.targetRef()))) {
          omitted.add(add.sourceRef() + " -> " + add.targetRef());
        }
      }
      if (!omitted.isEmpty()) {
        return "No mapping change was applied. Omitted hops: "
            + String.join(", ", omitted)
            + ". Map or skip an approved flow transition.";
      }
    }
    return "No mapping change was applied. Describe a hop with at least one rule, or pass through"
        + " the remainder.";
  }

  /** Appends the updated brief so design-input and the compiler see mapping intents. */
  private void persistMappingGapBrief(
      ProductPipelineRunDocument doc, AcceptInputCommand command, RequirementBrief updated) {
    persistCanonicalRequirementBrief(doc, command, updated);
  }

  /**
   * Canonicalize mapping intents, then append. Mapping-gap and an applied mapping turn write
   * through this method.
   */
  private Revision persistCanonicalRequirementBrief(
      ProductPipelineRunDocument doc, AcceptInputCommand command, RequirementBrief updated) {
    RequirementBrief canonical = RequirementBriefProjector.canonicalizeMappingIntents(updated);
    Optional<Revision> stored = artifactStore.latest(command.runId(), Kind.REQUIREMENT_BRIEF);
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                command.runId(),
                Kind.REQUIREMENT_BRIEF,
                stored.map(Revision::schemaVersion).orElse("1"),
                "product-pipeline-runtime",
                "1",
                canonical,
                List.of(),
                null,
                provenance(
                    command.runId(),
                    doc.run().currentStageId(),
                    currentStage(doc).capabilityId())));
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    attributes.put("requirementBrief", canonical);
    attributes.put("requirementBriefContentHash", revision.contentHash());
    try {
      attributes.put(ACCEPTED_SOURCE_BRIEF_REF_ATTR, HALT_EVIDENCE_JSON.writeValueAsString(revision.reference()));
    } catch (java.io.IOException e) { throw new IllegalStateException("Cannot save the accepted source brief reference", e); }
    return revision;
  }

  /** Handles typed pass-through, the describe action, and blank input on the mapping-gap card. */
  private Multi<PipelineSignal> recordMappingGapInput(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String canonical = canonicalMappingGapAction(command.text());
    if ("pass_through".equals(canonical)) {
      return acceptMappingGapPassThrough(doc, command);
    }
    if ("describe_mappings".equals(canonical)) {
      return waitForMappingGapDescribe(doc, command);
    }
    if (canonical.isBlank()) {
      return reemitHaltCard(doc);
    }
    return Multi.createFrom()
        .failure(
            new IllegalStateException(
                "mapping-gap input must be pass_through, describe_mappings, or blank"));
  }

  private Multi<PipelineSignal> acceptMappingGapPassThrough(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RequirementBrief brief = approvedRequirementBrief(command.runId());
    if (brief == null) {
      return Multi.createFrom()
          .failure(
              new IllegalStateException(
                  "mapping-gap pass-through requires a committed RequirementBrief"));
    }
    persistMappingGapBrief(doc, command, MappingGapCoverage.skipUncovered(brief));
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        doc.run().stages(),
        "accepted input",
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom().empty();
  }

  private Multi<PipelineSignal> waitForMappingGapDescribe(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String prompt =
        PipelineGates.tag(PipelineGates.MAPPING_GAP_DESCRIBE, "Describe the field mappings.");
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        prompt,
        null,
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  /**
   * Same canonical tokens as A2A {@code pass_through} / {@code describe_mappings}. Other text is
   * left unchanged so mapping prose reaches MappingTurnProcessor as typed.
   */
  private static String canonicalMappingGapAction(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    if ("pass_through".equalsIgnoreCase(trimmed) || "PASS_THROUGH".equalsIgnoreCase(trimmed)) {
      return "pass_through";
    }
    if ("describe_mappings".equalsIgnoreCase(trimmed)) {
      return "describe_mappings";
    }
    return text;
  }

  static boolean resourcePauseFollowedRepairOffer(ProductPipelineRunDocument doc) {
    List<RunTransition> transitions = doc.transitions();
    for (int i = transitions.size() - 1; i >= 0; i--) {
      RunTransition transition = transitions.get(i);
      if (!doc.run().currentStageId().equals(transition.stageId())
          || transition.toStatus() != RunStatus.WAITING_FOR_INPUT) {
        return false;
      }
      String gate = PipelineGates.gateOf(transition.reason()).orElse("");
      if (PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(gate)) {
        continue;
      }
      return PipelineGates.RECOVERY_REPAIR_ALLOWANCE.equals(gate);
    }
    return false;
  }

  /** Applies author input to the existing waiting stage. */
  private Multi<PipelineSignal> acceptTypedInput(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return Multi.createFrom()
        .deferred(
            () -> {
              String haltAction = PipelineGates.haltCardAction(command.text());
              boolean retryClick = PipelineGates.RETRY_ACTION.equals(haltAction);
              boolean reviseClick = PipelineGates.REVISE_ACTION.equals(haltAction);
              boolean haltCardClick = retryClick || reviseClick;
              String haltGate = PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
              boolean resourceGrant = retryClick && llmBudget != null
                  && llmBudget.state(command.runId()).refusal() != null
                  && PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(haltGate);
              if (resourceGrant && command.origin() != InputOrigin.TRUSTED) {
                return reemitHaltCard(doc);
              }
              recordRecoverySelection(command.runId(), haltGate, command.text());
              if (haltCardClick
                  && PipelineGates.isTerminalRecoveryGate(haltGate)) {
                return reemitHaltCard(doc);
              }
              if (retryClick
                  && !PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(haltGate)
                  && (!PipelineGates.RECOVERY_REGENERATE_EXECUTION.equals(haltGate)
                      || !diagnosedOwnerOf(command.runId()).equals(doc.run().currentStageId()))) {
                return rewindBeforeGeneration(doc, command);
              }
              if (retryClick
                  && PipelineGates.RECOVERY_REGENERATE_EXECUTION.equals(haltGate)
                  && catalogHasBeenWritten(doc.run().runId())) {
                return refuseWithGuard(doc, command, HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN);
              }
              if (retryClick && !resourceGrant) {
                HaltRecoveryGuard retryRefusal = diagnoseRetryRefusal(doc, command);
                if (retryRefusal != null) {
                  return refuseWithGuard(doc, command, retryRefusal);
                }
              }
              if (!haltCardClick) {
                artifactStore.append(
                    new AppendCommand(
                        command.runId(),
                        Kind.USER_INPUT,
                        "1",
                        "product-pipeline-runtime",
                        "1",
                        new UserInput(
                            userInputId(command),
                            doc.run().currentStageId(),
                            command.text(),
                            clock.instant()),
                        List.of(),
                        null,
                        provenance(
                            command.runId(),
                            doc.run().currentStageId(),
                            currentStage(doc).capabilityId())));
              }
              Map<String, Object> attributes =
                  attributesByRun.computeIfAbsent(
                      command.runId(), ignored -> new ConcurrentHashMap<>());
              if (!haltCardClick) {
                attributes.put("userText", command.text());
                attributes.remove(ADMITTED_INPUT_GATE_ATTR);
                attributes.remove(ADMITTED_INPUT_REVISION_ATTR);
                attributes.remove(ADMITTED_CLARIFICATION_OFFER_ATTR);
                if (PipelineGates.admitsAuthoredClarification(haltGate)
                    || PipelineGates.IDS_PATH_CHOICE.equals(haltGate) && command.text() != null
                    && ChatEvent.IDS_PATH_CHOICE_ACTIONS.contains(command.text().strip())) {
                  attributes.put(ADMITTED_INPUT_GATE_ATTR, haltGate);
                  attributes.put(ADMITTED_INPUT_REVISION_ATTR, doc.run().runRevision());
                  if (PipelineGates.STAGE_CLARIFICATION.equals(haltGate)) {
                    var offer = openOffer(doc);
                    if (offer != null && offer.offerId() != null && !offer.offerId().isBlank()) {
                      attributes.put(ADMITTED_CLARIFICATION_OFFER_ATTR, offer.offerId());
                    }
                  }
                }
                if (isClarificationWait(doc)) {
                  attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, command.text() == null ? "" : command.text());
                }
                // Leak checks must cover the original requirement ask, not later clarifications
                // or process instructions sent while discovery is WAITING_FOR_INPUT.
                Object priorDiscovery = attributes.get("discoveryUserText");
                if (!(priorDiscovery instanceof String prior) || prior.isBlank()) {
                  attributes.put("discoveryUserText", command.text() == null ? "" : command.text());
                }
              }
              if (reviseClick) {
                return recordRevise(doc, command);
              }
              String reason = "accepted input";
              if (resourceGrant) {
                String grantId = command.commandId() == null
                    ? "resource-grant:" + doc.run().runRevision() : command.commandId();
                llmBudget.grant(command.runId(), grantId);
                if (resourcePauseFollowedRepairOffer(doc)) {
                  String evidence = stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR).orElse("");
                  String identity = ToolCallFingerprints.failureSignature(evidence);
                  String renewed = PipelineGates.tagHaltIdentity(PipelineGates.tag(PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
                      "Model allowance accepted. Continue repair authorizes two additional targeted corrections"
                          + " and two clean regenerations for this defect."), identity);
                  commitStatus(doc, RunStatus.WAITING_FOR_INPUT, StageStatus.WAITING_FOR_INPUT, doc.run().stages(),
                      renewed, haltEvidence(attributes, null, evidence), command.commandId(), command.commandPayloadHash());
                  recoveryTelemetry.recordPresented(command.runId(), PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
                      identity, evidence);
                  return Multi.createFrom().item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), renewed));
                }
                reason = recoveryLedger.reissueUnsentAttempt(doc.transitions(), doc.run().currentStageId())
                    .orElse("author granted an additional model allowance");
                if (!"author granted an additional model allowance".equals(reason)) {
                  try {
                    attributes.put(RECOVERY_PENDING_ATTEMPT_ATTR, HALT_EVIDENCE_JSON.writeValueAsString(
                        Map.of("stageId", doc.run().currentStageId(), "revision", doc.run().runRevision() + 1)));
                  } catch (Exception e) {
                    throw new IllegalStateException("Cannot restore the unsent correction intent", e);
                  }
                }
              } else if (retryClick) {
                reason = recordAttempt(doc, command);
              } else if (PipelineGates.admitsAuthoredClarification(haltGate)) {
                reason = "accepted clarification";
              }
              commitStatus(
                  doc,
                  RunStatus.RUNNING,
                  StageStatus.RUNNING,
                  doc.run().stages(),
                  reason,
                  resourceGrant ? haltEvidence(attributes, null) : null,
                  command.commandId(),
                  command.commandPayloadHash());
              return Multi.createFrom().empty();
            });
  }

  /**
   * Typed text at an approval card that no earlier branch claimed. Agree arrives as a decision
   * command on its own endpoint and never reaches here, and a halt-card action is a fixed token
   * rather than something a person types, so what is left is either a question about the candidate
   * or the change request the refine path already handles.
   */
  private static boolean isTypedAtApprovalCard(ProductPipelineRunDocument doc, String text) {
    return doc.run().status() == RunStatus.WAITING_FOR_APPROVAL
        && text != null
        && !text.isBlank()
        && !PipelineGates.isHaltCardAction(text);
  }

  /**
   * Reads a message typed at an approval card and answers it when it asks about the candidate,
   * instead of taking every typed message as a request to revise. A model decides which it is, so
   * the decision holds in the language the conversation is in.
   *
   * <p>An answer writes nothing. The run keeps its status, its revision, and its approvable
   * reference, so the card the person is looking at stays valid and the Agree they send next is not
   * refused as stale. Nothing is recorded as the stage's user text either, which is what keeps a
   * question out of the next candidate. Anything read as an instruction falls through to the refine
   * path unchanged.
   *
   * <p>The turn runs off the calling thread because {@code recordInput} can be reached on the event
   * loop, where a model call may not block.
   */
  private Multi<PipelineSignal> answerApprovalQuestionOrRefine(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return Multi.createFrom()
        .deferred(
            () -> {
              PauseQuestionResult asked =
                  failureNarrative.answerApprovalQuestion(
                      command.runId(),
                      responseLocaleOf(command.runId()),
                      command.text(),
                      doc.run().currentStageId(),
                      approvalCandidateEvidence(doc));
              if (asked.isUnanswerable()) {
                return Multi.createFrom()
                    .item(
                        (PipelineSignal)
                            new PipelineSignal.Message(FailureNarrative.NO_EXPLANATION_AVAILABLE))
                    .onCompletion()
                    .switchTo(() -> reemitApprovalCard(doc));
              }
              Reference selectedCandidate = approvalCandidate(doc);
              if (asked.isRequirementChange()
                  || asked.isNotAQuestion() && !asked.isWordingChange() && hasRequirementDiscovery(command.runId())
                      && selectedCandidate != null && selectedCandidate.kind() == Kind.REQUIREMENT_BRIEF) {
                return reopenRequirementDraft(doc, command);
              }
              if (asked.isNotAQuestion()) {
                return acceptTypedInput(doc, command);
              }
              return Multi.createFrom()
                  .item((PipelineSignal) new PipelineSignal.Message(asked.answer()))
                  .onCompletion()
                  .switchTo(() -> reemitApprovalCard(doc));
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  /**
   * Puts the approval card back after an answer, the way a resume re-announces it. No commit: a
   * status transition would move the run revision the open card carries, and the next Agree would
   * be refused as stale.
   */
  private Multi<PipelineSignal> reemitApprovalCard(ProductPipelineRunDocument doc) {
    Reference candidate = approvalCandidate(doc);
    if (candidate == null) {
      return Multi.createFrom().empty();
    }
    return Multi.createFrom()
        .item(
            new PipelineSignal.WaitingForApproval(
                doc.run().currentStageId(),
                candidate,
                approvalPromptFor(doc.run().runId(), doc.run().currentStageId())));
  }

  /**
   * What a question at an approval card is answered from. An approval pause holds no failure, so
   * the evidence is the artifact the person is being asked to accept: its kind, which revision of
   * it this is, its content hash, the stage's own account of producing it, and the stored payload.
   *
   * <p>The content hash carries the staleness of the answer cache on its own. Normalizing the
   * evidence masks bracketed lists, so two payloads that differ only inside a JSON array sign
   * alike; the hash does not, and it changes the moment the stage emits a new candidate. The
   * payload is capped because a plan can run to megabytes and the cap costs nothing the hash does
   * not already cover.
   */
  private String approvalCandidateEvidence(ProductPipelineRunDocument doc) {
    Reference candidate = approvalCandidate(doc);
    if (candidate == null) {
      return "";
    }
    StageSnapshot snapshot = approvalStageSnapshot(doc);
    Integer revision = snapshot == null ? null : snapshot.candidateRevision();
    List<String> lines = new ArrayList<>();
    lines.add("kind: " + candidate.kind().name());
    lines.add("revision: " + (revision == null ? 1 : revision));
    lines.add("contentHash: " + candidate.contentHash());
    lines.add("stageMessage: " + latestWaitingForApprovalReason(doc));
    lines.add("payload: " + approvalCandidatePayload(doc.run().runId(), candidate));
    return String.join("\n", lines);
  }

  private String approvalCandidatePayload(String runId, Reference candidate) {
    Optional<Revision> stored = artifactStore.get(runId, candidate);
    if (stored.isEmpty() || stored.get().payload() == null) {
      return "(unavailable)";
    }
    String json = stored.get().payload().toString();
    return json.length() <= MAX_APPROVAL_CANDIDATE_CHARS
        ? json
        : json.substring(0, MAX_APPROVAL_CANDIDATE_CHARS) + " (truncated)";
  }

  private static StageSnapshot approvalStageSnapshot(ProductPipelineRunDocument doc) {
    return doc.run().stages().stream()
        .filter(stage -> stage.stageId().equals(doc.run().currentStageId()))
        .findFirst()
        .orElse(null);
  }

  private static Reference approvalCandidate(ProductPipelineRunDocument doc) {
    StageSnapshot snapshot = approvalStageSnapshot(doc);
    return snapshot == null ? null : resolveReopenApprovable(snapshot);
  }

  /** Last durable WAITING_FOR_APPROVAL transition reason: the stage's own account of the wait. */
  private static String latestWaitingForApprovalReason(ProductPipelineRunDocument doc) {
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_APPROVAL)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElse("");
  }

  /**
   * Derives the user-input artifact identity from the command so a replay reuses the same artifact
   * instead of appending a second copy under a fresh random ID.
   */
  private static String userInputId(AcceptInputCommand command) {
    return command.commandId() == null || command.commandId().isBlank()
        ? UUID.randomUUID().toString()
        : "user-input-" + command.commandId();
  }

  /**
   * Typed message at a recoverable halt: same run, original requirements unchanged. When the text
   * names one stage in the closed candidate set, that owner is reopened. Naming more than one stage
   * re-emits the current card. A named stage outside the set stays halted with End run. A bare
   * go-back reopens the diagnosed owner, or the previous generative stage when diagnosis is empty.
   * Whatever none of those branches claims goes to {@link #answerQuestionOrStayWaiting}. The next
   * diagnosis turn reads {@link #HALT_FOLLOW_UP_TEXT_ATTR}.
   */
  private Multi<PipelineSignal> recordHaltFollowUp(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    artifactStore.append(
        new AppendCommand(
            command.runId(),
            Kind.USER_INPUT,
            "1",
            "product-pipeline-runtime",
            "1",
            new UserInput(
                haltFollowUpInputId(command),
                doc.run().currentStageId(),
                command.text(),
                clock.instant()),
            List.of(),
            null,
            provenance(
                command.runId(),
                doc.run().currentStageId(),
                currentStage(doc).capabilityId())));
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    String followUp = command.text() == null ? "" : command.text();
    String priorFollowUp =
        attributes.get(HALT_FOLLOW_UP_TEXT_ATTR) instanceof String prior ? prior : "";
    // Bare go-back confirms reopen; keep a prior correction such as "add rbac".
    if (!OwnerCandidateSet.isBareGoBack(followUp) || priorFollowUp.isBlank()) {
      attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, followUp);
    }
    List<OwnerCandidate> closed = haltOwnerCandidates(doc);
    List<String> named = OwnerCandidateSet.namedStages(command.text(), closed);
    if (named.size() == 1) {
      attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, named.get(0));
      return applyDiagnosedOwner(doc, command);
    }
    if (named.size() > 1) {
      attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, "");
      return reemitHaltCard(doc);
    }
    if (OwnerCandidateSet.isBareGoBack(command.text())) {
      return applyBareGoBack(doc, command, closed);
    }
    if (OwnerCandidateSet.requestsNamedStage(command.text())) {
      return refuseWithGuard(
          doc, command, HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET);
    }
    return answerQuestionOrStayWaiting(doc, command, closed, priorFollowUp);
  }

  /**
   * Last branch of a halt follow-up: a message that named no stage and asked for no go-back either
   * asks about the pause, instructs the run, or cannot be answered. A model decides the first two;
   * a timeout or a failed call is the third and is never treated as an instruction.
   *
   * <p>A question is answered from the evidence the halt card was already built from and reaches
   * the transcript as a message. The run keeps its status, the same wait comes back so the card
   * stays, and the question is lifted back off {@link #HALT_FOLLOW_UP_TEXT_ATTR} so the next repair
   * turn does not read it as a correction. An unanswerable question produces a card that says no
   * explanation is available and keeps the raw evidence.
   *
   * <p>The turn runs off the calling thread because {@code recordInput} can be reached on the event
   * loop, where a model call may not block.
   */
  private Multi<PipelineSignal> answerQuestionOrStayWaiting(
      ProductPipelineRunDocument doc,
      AcceptInputCommand command,
      List<OwnerCandidate> closed,
      String priorFollowUp) {
    return Multi.createFrom()
        .deferred(
            () -> {
              PauseQuestionResult asked =
                  failureNarrative.answerHaltQuestion(
                      command.runId(),
                      responseLocaleOf(command.runId()),
                      command.text(),
                      stringAttribute(command.runId(), STAGE_ERROR_FAILED_STAGE_ATTR)
                          .orElse(doc.run().currentStageId()),
                      haltOutcomeClass(command.runId()),
                      stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR).orElse(""),
                      stringAttribute(command.runId(), STAGE_ERROR_FINDINGS_ATTR).orElse(""),
                      closed,
                      priorFollowUp);
              if (asked.isUnanswerable()) {
                return showUnanswerableHalt(doc, command, priorFollowUp);
              }
              if (asked.isRequirementChange()) {
                return reopenRequirementDraft(doc, command, selectedRequirementBriefReference(doc));
              }
              if (asked.isNotAQuestion()) {
                String gate =
                    PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
                if (PipelineGates.STAGE_CLARIFICATION.equals(gate)
                    && currentRecoveryCause(command.runId()).isMissingCatalogBinding()) {
                  return applyMissingBindingClarification(doc, command);
                }
                if (PipelineGates.RECOVERY_REVISE_BRIEF.equals(gate)
                    && currentRecoveryCause(command.runId()).causeCode()
                        == RecoveryCauseCode.MAPPING_CONTRACT) {
                  return applyMappingRepairInput(doc, command);
                }
                if (PipelineGates.STAGE_RETRY.equals(gate)
                    || PipelineGates.isContextualRecoveryGate(gate)) {
                  if (!asked.isRepairInstruction()) {
                    return reemitHaltCard(doc);
                  }
                  if (diagnosedOwnerOf(command.runId()).isBlank()
                      && isCurrentUnapprovedOwner(doc, doc.run().currentStageId())) {
                    attributesByRun
                        .computeIfAbsent(
                            command.runId(), ignored -> new ConcurrentHashMap<>())
                        .put(DIAGNOSED_OWNER_STAGE_ATTR, doc.run().currentStageId());
                  }
                  if (!diagnosedOwnerOf(command.runId()).isBlank()) {
                    return applyDiagnosedOwner(doc, command);
                  }
                  return reemitHaltCard(doc);
                }
                return applyDiagnosedOwner(doc, command);
              }
              restoreHaltFollowUpText(command.runId(), priorFollowUp);
              return Multi.createFrom()
                  .item((PipelineSignal) new PipelineSignal.Message(asked.answer()))
                  .onCompletion()
                  .switchTo(() -> reemitHaltCard(doc));
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private Multi<PipelineSignal> applyMappingRepairInput(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RequirementBrief brief = approvedRequirementBrief(command.runId());
    if (mappingTurnAdapter == null || brief == null) {
      return reemitHaltCard(doc);
    }
    MappingTurnApplication correction =
        MappingTurnProcessor.process(brief, command.text(), mappingTurnAdapter);
    var structuralRepair = tryStructuralReminder(doc, command, brief, correction.result());
    if (structuralRepair.isPresent()) {
      return structuralRepair.get();
    }
    if (!correction.applied()) {
      return stayAfterMappingTurn(doc, mappingReplyMessage(doc, command, correction));
    }
    boolean authored = artifactStore.history(command.runId(), Kind.REQUIREMENT_DRAFT).stream()
        .filter(revision -> revision.artifactId().equals(brief.approvedDraftReference()))
        .map(revision -> artifactStore.payload(revision, org.qubership.integration.platform.ai.plan.RequirementDraft.class))
        .anyMatch(draft -> draft.authoredDraft() != null);
    if (authored) {
      Reference selectedBrief = artifactStore.latest(command.runId(), Kind.REQUIREMENT_BRIEF)
          .map(Revision::reference).orElse(null);
      return reopenRequirementDraft(doc, command, selectedBrief);
    }
    if (currentStage(doc).produces().stream().anyMatch(type -> type.matches(Kind.REQUIREMENT_BRIEF))
        && !catalogHasBeenWritten(command.runId())) {
      commitStatus(doc, RunStatus.RUNNING, StageStatus.RUNNING, doc.run().stages(), "accepted mapping correction",
          haltEvidence(attributesByRun.get(command.runId()), null), command.commandId(), command.commandPayloadHash());
      return Multi.createFrom().empty();
    }
    // A mapping amendment can reopen the brief; rephrasing a retry cannot.
    return applyDiagnosedOwner(doc, command);
  }

  /**
   * Halt card for a pause question the turn could not answer. Keeps the gate and the raw evidence;
   * does not treat the message as an instruction.
   */
  private Multi<PipelineSignal> showUnanswerableHalt(
      ProductPipelineRunDocument doc, AcceptInputCommand command, String priorFollowUp) {
    restoreHaltFollowUpText(command.runId(), priorFollowUp);
    String previous = latestWaitingForInputPrompt(doc);
    String evidence =
        stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR)
            .orElseGet(() -> PipelineGates.strip(previous));
    String body =
        FailureNarrative.NO_EXPLANATION_AVAILABLE
            + (evidence.isBlank() ? "" : " " + evidence);
    String prompt = PipelineGates.withStrippedBody(previous, body);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        prompt,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  /**
   * Puts back the follow-up text a question displaced. Asking is not instructing, so the next
   * repair turn must not receive the question as the correction to work from.
   */
  private void restoreHaltFollowUpText(String runId, String priorFollowUp) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return;
    }
    if (priorFollowUp.isBlank()) {
      attributes.remove(HALT_FOLLOW_UP_TEXT_ATTR);
    } else {
      attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, priorFollowUp);
    }
  }

  /**
   * Resolves a missing catalog binding from clarification text and persists it on the binding
   * producer. Saving the text and retrying execution against unchanged hints does not count.
   * Several catalog matches stay on an actionable wait. An empty, unknown, or incompatible answer
   * explains the unresolved choice and does not spend a semantic repair.
   */
  private Multi<PipelineSignal> applyMissingBindingClarification(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RecoveryCause cause = currentRecoveryCause(command.runId());
    String interactionId = cause.unresolvedInteractionId().orElse("");
    String producerStageId = diagnosedOwnerOf(command.runId());
    if (!"requirement-discovery".equals(producerStageId) || interactionId.isBlank()) {
      return reemitHaltCard(doc);
    }
    Optional<RequirementDiscoveryCapability> discovery =
        capabilities
            .find(RequirementDiscoveryCapability.CAPABILITY_ID)
            .filter(RequirementDiscoveryCapability.class::isInstance)
            .map(RequirementDiscoveryCapability.class::cast);
    if (discovery.isEmpty() || !discovery.get().canResolveMissingBindings()) {
      return reemitHaltCard(doc);
    }
    Optional<CatalogBindingHint> hint =
        discovery
            .get()
            .bindMissingInteraction(doc.run().conversationId(), interactionId, command.text());
    if (hint.isPresent()) {
      artifactStore.append(
          new AppendCommand(
              command.runId(),
              Kind.CATALOG_BINDING_HINT,
              "1",
              RequirementDiscoveryCapability.CAPABILITY_ID,
              "1",
              hint.get(),
              List.of(),
              null,
              provenance(
                  command.runId(),
                  producerStageId,
                  RequirementDiscoveryCapability.CAPABILITY_ID)));
      Map<String, Object> attributes = attributesByRun.get(command.runId());
      if (attributes != null) {
        attributes.remove(HALT_FOLLOW_UP_TEXT_ATTR);
      }
      commitStatus(
          doc,
          RunStatus.RUNNING,
          StageStatus.RUNNING,
          doc.run().stages(),
          "accepted binding clarification",
          null,
          command.commandId(),
          command.commandPayloadHash());
      return Multi.createFrom().empty();
    }
    CatalogBindingMatcher.MatchResult result =
        discovery.get().matchMissingInteraction(doc.run().conversationId(), command.text());
    if (result instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      String body = ambiguousBindingBody(ambiguous.matches());
      if (!body.isBlank()) {
        return waitMissingBindingClarification(doc, command, body);
      }
    }
    return waitMissingBindingClarification(doc, command, UNUSABLE_BINDING_ANSWER_BODY);
  }

  private static String ambiguousBindingBody(List<CatalogMatch> matches) {
    if (matches == null || matches.isEmpty()) {
      return "";
    }
    List<String> labels = new ArrayList<>();
    for (CatalogMatch match : matches) {
      if (match == null) {
        continue;
      }
      String label = catalogOperationLabel(match);
      if (!label.isBlank()) {
        labels.add(label);
      }
    }
    if (labels.isEmpty()) {
      return "";
    }
    return "Several catalog operations match that name. Name one: "
        + String.join(", ", labels)
        + ".";
  }

  private static String catalogOperationLabel(CatalogMatch match) {
    String name =
        match.operationName() == null || match.operationName().isBlank()
            ? match.integrationOperationId()
            : match.operationName();
    if (name == null || name.isBlank()) {
      return "";
    }
    if (match.method() != null
        && !match.method().isBlank()
        && match.path() != null
        && !match.path().isBlank()) {
      return name + " (" + match.method() + " " + match.path() + ")";
    }
    return name;
  }

  /**
   * Keeps the missing-binding clarification wait with a new reader-visible body. Does not persist a
   * hint, invoke the compiler, or record a semantic repair.
   */
  private Multi<PipelineSignal> waitMissingBindingClarification(
      ProductPipelineRunDocument doc, AcceptInputCommand command, String body) {
    String previous = latestWaitingForInputPrompt(doc);
    String remaining =
        HaltRecoveryGuard.remainingLine(
            recoveryLedger.remaining(
                doc.transitions(), currentAttemptKey(doc), InputOrigin.TRUSTED));
    String prompt = PipelineGates.withStrippedBody(previous, body + remaining);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        prompt,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  /** Outcome class of the halt holding this run, or {@code null} when none was recorded. */
  private StageOutcomeClass haltOutcomeClass(String runId) {
    Optional<String> recorded = stringAttribute(runId, STAGE_ERROR_OUTCOME_ATTR);
    if (recorded.isEmpty()) {
      return null;
    }
    try {
      return StageOutcomeClass.valueOf(recorded.get());
    } catch (IllegalArgumentException unknown) {
      LOG.warnf("unknown halt outcome class %s on run %s", recorded.get(), runId);
      return null;
    }
  }

  private Multi<PipelineSignal> applyBareGoBack(
      ProductPipelineRunDocument doc,
      AcceptInputCommand command,
      List<OwnerCandidate> closed) {
    String currentPrompt = latestWaitingForInputPrompt(doc);
    if (PipelineGates.OWNER_CHOICE.equals(PipelineGates.gateOf(currentPrompt).orElse(""))) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.BARE_GO_BACK_AT_OWNER_CHOICE);
    }
    Optional<String> owner =
        OwnerCandidateSet.ownerForBareGoBack(
            diagnosedOwnerOf(command.runId()), closed, doc.run().currentStageId());
    if (owner.isEmpty()) {
      ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
      owner =
          OwnerCandidateSet.previousGenerativeStageId(profile, doc.run().currentStageId());
    }
    if (owner.isEmpty()) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.BLANK_OR_UNAPPROVED_OWNER);
    }
    attributesByRun
        .computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>())
        .put(DIAGNOSED_OWNER_STAGE_ATTR, owner.get());
    return applyDiagnosedOwner(doc, command);
  }

  private List<OwnerCandidate> haltOwnerCandidates(ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    List<OwnerCandidate> first =
        OwnerCandidateSet.firstLayer(profile, doc.run().currentStageId());
    return OwnerCandidateSet.deepen(profile, first);
  }

  /**
   * Re-emits the current halt wait without committing. Used after an answered question so the card
   * stays and the transcript message is the observable effect.
   */
  private Multi<PipelineSignal> reemitHaltCard(ProductPipelineRunDocument doc) {
    String prompt = latestWaitingForInputPrompt(doc);
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  /**
   * Refuses a halt command by advancing to a repeated-failure recovery wait that names {@code
   * guard}. Re-committing the previous wait is not a legal answer. A second refusal of the same
   * already-recorded guard emits the guard sentence as a message and keeps the terminal card.
   */
  private Multi<PipelineSignal> refuseWithGuard(
      ProductPipelineRunDocument doc, AcceptInputCommand command, HaltRecoveryGuard guard) {
    HaltRecoveryGuard named = guard == null ? HaltRecoveryGuard.MAX_SEMANTIC_REPAIRS : guard;
    String previous = latestWaitingForInputPrompt(doc);
    if (PipelineGates.RECOVERY_REPEATED.equals(PipelineGates.gateOf(previous).orElse(""))
        && named.name().equals(PipelineGates.guardOf(previous).orElse(""))) {
      return Multi.createFrom()
          .item((PipelineSignal) new PipelineSignal.Message(named.cardSentence()))
          .onCompletion()
          .switchTo(() -> reemitHaltCard(doc));
    }
    String evidence =
        stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR)
            .orElseGet(() -> PipelineGates.strip(previous));
    String listed = "";
    if (named == HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET) {
      listed = String.join(", ", OwnerCandidateSet.stageIds(haltOwnerCandidates(doc)));
    }
    String body =
        listed.isBlank() ? named.cardSentence() : named.cardSentence() + " " + listed;
    String details =
        (evidence.isBlank() ? "" : evidence + " ") + "(runId=" + command.runId() + ")";
    String prompt =
        PipelineGates.tagGuard(
            PipelineGates.tagRecoveryDetails(
                PipelineGates.retag(PipelineGates.RECOVERY_REPEATED, body),
                details,
                null),
            named.name());
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        prompt,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  private HaltRecoveryGuard diagnoseReopenRefusal(
      ProductPipelineRunDocument doc,
      String owner,
      InputOrigin origin,
      RecoveryAttemptLedger.ReopenInitiator initiator) {
    if (owner == null
        || owner.isBlank()
        || owner.equals(doc.run().currentStageId())
        || !isEarlierApprovedOwner(doc, owner)) {
      return HaltRecoveryGuard.BLANK_OR_UNAPPROVED_OWNER;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN;
    }
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    String legacy = causalReopenFailureSignature(doc.run().runId());
    if (recoveryLedger.ownerAlreadyReopened(doc.transitions(), key, legacy)
        && !RecoveryAttemptLedger.isAuthorMissingBriefFactsCorrection(
            initiator, cause.causeCode())) {
      return HaltRecoveryGuard.OWNER_ALREADY_REOPENED;
    }
    if (!recoveryLedger.mayReopen(doc.transitions(), key, origin, initiator, legacy)) {
      return HaltRecoveryGuard.MAX_CAUSAL_REOPENS;
    }
    return HaltRecoveryGuard.MAX_CAUSAL_REOPENS;
  }

  private Multi<PipelineSignal> rewindBeforeGeneration(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String rewind = rewindTarget(doc);
    if (rewind.isBlank() || rewind.equals(doc.run().currentStageId())) {
      HaltRecoveryGuard retryRefusal = diagnoseRetryRefusal(doc, command);
      if (retryRefusal != null) {
        return refuseWithGuard(doc, command, retryRefusal);
      }
      String reason = recordAttempt(doc, command);
      commitStatus(
          doc,
          RunStatus.RUNNING,
          StageStatus.RUNNING,
          doc.run().stages(),
          reason,
          null,
          command.commandId(),
          command.commandPayloadHash());
      return Multi.createFrom().empty();
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN);
    }
    attributesByRun
        .computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>())
        .put(DIAGNOSED_OWNER_STAGE_ATTR, rewind);
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    if (!isEarlierApprovedOwner(doc, rewind) && isCommittedRejectedInputOwner(doc, rewind, cause)) {
      HaltRecoveryGuard retryRefusal = diagnoseRetryRefusal(doc, command);
      if (retryRefusal != null) {
        return refuseWithGuard(doc, command, retryRefusal);
      }
      return resetDownstreamAndMoveTo(doc, rewind, recordAttempt(doc, command), command);
    }
    if (shouldCausalReopen(
        doc, rewind, command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR)) {
      return causalReopenOwner(doc, command, rewind);
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, rewind);
    RecoveryAttemptKey key = recoveryLedger.key(rewind, cause, artifact, doc.transitions());
    if (!recoveryLedger.mayReopen(
        doc.transitions(),
        key,
        command.origin(),
        RecoveryAttemptLedger.ReopenInitiator.AUTHOR,
        causalReopenFailureSignature(doc.run().runId()))) {
      return refuseWithGuard(
          doc,
          command,
          diagnoseReopenRefusal(
              doc,
              rewind,
              command.origin(),
              RecoveryAttemptLedger.ReopenInitiator.AUTHOR));
    }
    return resetDownstreamAndMoveTo(
        doc, rewind, reopenReason(doc, rewind, command), command);
  }

  private String rewindTarget(ProductPipelineRunDocument doc) {
    String current = doc.run().currentStageId() == null ? "" : doc.run().currentStageId();
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    String diagnosed = diagnosedOwnerOf(doc.run().runId());
    if (!diagnosed.isBlank()
        && !diagnosed.equals(current)
        && isEarlierStage(profile, diagnosed, current)) {
      return diagnosed;
    }
    return OwnerCandidateSet.previousGenerativeStageId(profile, current).orElse("");
  }

  private static boolean isEarlierStage(
      ProductPipelineProfile profile, String owner, String current) {
    if (profile == null || profile.stages() == null) {
      return false;
    }
    int ownerIndex = -1;
    int currentIndex = -1;
    List<ProfileStage> stages = profile.stages();
    for (int i = 0; i < stages.size(); i++) {
      if (owner.equals(stages.get(i).stageId())) {
        ownerIndex = i;
      }
      if (current.equals(stages.get(i).stageId())) {
        currentIndex = i;
      }
    }
    return ownerIndex >= 0 && currentIndex >= 0 && ownerIndex < currentIndex;
  }

  private HaltRecoveryGuard diagnoseRetryRefusal(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return diagnoseAttemptRefusal(doc, command);
  }

  private HaltRecoveryGuard diagnoseAttemptRefusal(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RecoveryAttemptKey key = currentAttemptKey(doc);
    if (recoveryLedger.mayRepair(doc.transitions(), key, command.origin())) {
      return null;
    }
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    if (cause.causeCode() == RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED) {
      return HaltRecoveryGuard.TECHNICAL_RETRY;
    }
    return HaltRecoveryGuard.MAX_SEMANTIC_REPAIRS;
  }

  private String recordAttempt(ProductPipelineRunDocument doc, AcceptInputCommand command) {
    RecoveryAttemptKey key = currentAttemptKey(doc);
    String owner = key.ownerStageId();
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    return recoveryLedger.recordRepair(key, artifact);
  }

  private RecoveryAttemptKey currentAttemptKey(ProductPipelineRunDocument doc) {
    String owner = diagnosedOwnerOf(doc.run().runId());
    if (owner.isBlank()) {
      owner = doc.run().currentStageId() == null ? "" : doc.run().currentStageId();
    }
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    return recoveryLedger.key(owner, cause, artifact, doc.transitions());
  }

  private SemanticRecoveryState.RemainingAttempts remainingOf(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return recoveryLedger.remaining(doc.transitions(), currentAttemptKey(doc), command.origin());
  }

  private static boolean isClarificationWait(ProductPipelineRunDocument doc) {
    return PipelineGates.STAGE_CLARIFICATION.equals(
        PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""));
  }

  private Multi<PipelineSignal> recordRevise(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return applyDiagnosedOwner(doc, command);
  }

  private Multi<PipelineSignal> applyDiagnosedOwner(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String owner = diagnosedOwnerOf(command.runId());
    if (isCurrentUnapprovedOwner(doc, owner)) {
      commitStatus(
          doc,
          RunStatus.RUNNING,
          StageStatus.RUNNING,
          doc.run().stages(),
          "accepted input",
          null,
          command.commandId(),
          command.commandPayloadHash());
      return Multi.createFrom().empty();
    }
    if (shouldCausalReopen(
        doc, owner, command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR)) {
      return causalReopenOwner(doc, command, owner);
    }
    return refuseWithGuard(
        doc,
        command,
        diagnoseReopenRefusal(
            doc, owner, command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR));
  }

  private boolean shouldCausalReopen(
      ProductPipelineRunDocument doc,
      String owner,
      InputOrigin origin,
      RecoveryAttemptLedger.ReopenInitiator initiator) {
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    if (!isEarlierApprovedOwner(doc, owner)
        && !isCommittedIdentityMismatchOwner(doc, owner, cause)
        && !isCommittedRejectedInputOwner(doc, owner, cause)
        && !isCommittedRequirementDraftOwner(doc, owner, profilesByRun.get(doc.run().runId()), artifactStore)) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    return recoveryLedger.mayReopen(
        doc.transitions(),
        key,
        origin,
        initiator,
        causalReopenFailureSignature(doc.run().runId()));
  }

  public static boolean isCommittedRequirementDraftOwner(
      ProductPipelineRunDocument doc, String owner, ProductPipelineProfile profile,
      ProductPipelineArtifactStore artifactStore) {
    if (profile == null || owner == null || owner.equals(doc.run().currentStageId())) {
      return false;
    }
    boolean declared = profile.stages().stream().filter(stage -> owner.equals(stage.stageId()))
        .anyMatch(stage -> "requirement-discovery".equals(stage.capabilityId())
            && stage.produces().stream().anyMatch(type -> type.matches(Kind.REQUIREMENT_DRAFT)));
    if (!declared || !stageIdsAfter(profile, owner).contains(doc.run().currentStageId())) {
      return false;
    }
    return doc.run().stages().stream().filter(stage -> owner.equals(stage.stageId())
            && stage.status() == StageStatus.SUCCEEDED)
        .flatMap(stage -> stage.outputRefs().stream()).filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT)
        .anyMatch(ref -> artifactStore.get(doc.run().runId(), ref)
            .filter(revision -> revision.provenance() != null
                && doc.run().runId().equals(revision.provenance().runId())
                && owner.equals(revision.provenance().stageId())).isPresent());
  }

  private Multi<PipelineSignal> reopenRequirementDraft(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return reopenRequirementDraft(doc, command, approvalCandidate(doc));
  }

  private Multi<PipelineSignal> reopenRequirementDraft(
      ProductPipelineRunDocument doc, AcceptInputCommand command, Reference candidate) {
    ProductPipelineProfile profile = profilesByRun.get(command.runId());
    String owner = profile == null ? null : profile.stages().stream()
        .filter(stage -> "requirement-discovery".equals(stage.capabilityId())
            && stage.produces().stream().anyMatch(type -> type.matches(Kind.REQUIREMENT_DRAFT)))
        .map(ProfileStage::stageId).findFirst().orElse(null);
    Reference published = doc.run().stages().stream().filter(stage -> java.util.Objects.equals(owner, stage.stageId()))
        .map(stage -> preparingReplacement(profile, attributesByRun.get(command.runId()), owner)
            ? stage.candidateReferences().stream().filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT)
                .findFirst().orElse(null)
            : resolveReopenApprovable(stage)).filter(java.util.Objects::nonNull)
        .filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT).findFirst().orElse(null);
    if (published == null || candidate == null || candidate.kind() != Kind.REQUIREMENT_BRIEF) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.MISSING_PROFILE_OR_PRIOR_CANDIDATE);
    }
    var brief = artifactStore.get(command.runId(), candidate)
        .map(revision -> artifactStore.payload(revision, RequirementBrief.class)).orElse(null);
    Reference selected = brief == null ? null : artifactStore.history(command.runId(), Kind.REQUIREMENT_DRAFT)
        .stream().filter(revision -> revision.artifactId().equals(brief.approvedDraftReference()))
        .map(Revision::reference).findFirst().orElse(null);
    if (selected == null || !draftDerivesFrom(command.runId(), selected, published)) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.MISSING_PROFILE_OR_PRIOR_CANDIDATE);
    }
    if (catalogHasBeenWritten(command.runId())) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN);
    }
    boolean authorRevision = doc.run().status() == RunStatus.WAITING_FOR_APPROVAL;
    if (!authorRevision
        && !shouldCausalReopen(doc, owner, command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR)) {
      return refuseWithGuard(doc, command, HaltRecoveryGuard.MAX_CAUSAL_REOPENS);
    }
    List<String> sourceIds = conversations == null ? List.of() : conversations.archive()
        .pendingInputs(doc.run().conversationId()).stream().map(turn -> turn.id()).toList();
    artifactStore.append(new AppendCommand(command.runId(), Kind.USER_INPUT, "1",
        "product-pipeline-runtime", "1", new UserInput(userInputId(command), owner, command.text(),
            clock.instant(), selected, sourceIds), List.of(selected), null,
        provenance(command.runId(), owner, profile.stages().stream()
            .filter(stage -> owner.equals(stage.stageId())).findFirst().orElseThrow().capabilityId())));
    var attributes = attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    attributes.put("userText", command.text());
    attributes.put("mappingRepairAutomatic", "false");
    attributes.put("requirementCorrectionDraftReference", selected);
    attributes.put("requirementCorrectionDraft", artifactStore.get(command.runId(), selected)
        .map(revision -> artifactStore.payload(revision, org.qubership.integration.platform.ai.plan.RequirementDraft.class))
        .orElseThrow());
    attributes.put("requirementCorrectionSourceTurnIds", sourceIds);
    return authorRevision
        ? resetDownstreamAndMoveTo(doc, owner, "requirement change requested", command)
        : causalReopenOwner(doc, command, owner);
  }

  private boolean draftDerivesFrom(String runId, Reference selected, Reference published) {
    var pending = new java.util.ArrayDeque<Reference>();
    var visited = new java.util.HashSet<String>();
    pending.add(selected);
    while (!pending.isEmpty()) {
      Reference ref = pending.removeFirst();
      if (published.equals(ref)) {
        return true;
      }
      if (visited.add(ref.artifactId())) {
        artifactStore.get(runId, ref).ifPresent(revision -> revision.inputs().stream()
            .filter(input -> input.kind() == Kind.REQUIREMENT_DRAFT).forEach(pending::addLast));
      }
    }
    return false;
  }

  private boolean isCommittedIdentityMismatchOwner(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause) {
    if (cause == null || !cause.isBindingIdentityMismatch()) {
      return false;
    }
    if (owner == null || owner.isBlank() || owner.equals(doc.run().currentStageId())) {
      return false;
    }
    return doc.run().stages().stream()
        .filter(stage -> owner.equals(stage.stageId()))
        .findFirst()
        .map(
            stage ->
                stage.status() == StageStatus.SUCCEEDED
                    && stage.outputRefs() != null
                    && !stage.outputRefs().isEmpty())
        .orElse(false);
  }

  private boolean isCommittedRejectedInputOwner(
      ProductPipelineRunDocument doc, String owner, RecoveryCause cause) {
    if (isCommittedContractInputOwner(doc, owner, cause, profilesByRun.get(doc.run().runId()), artifactStore)) {
      return true;
    }
    if (cause == null
        || !((cause.causeCode() == RecoveryCauseCode.CONTRACT_SHAPE
                && "materialization".equals(doc.run().currentStageId())
                && "design-execution".equals(owner))
            || (cause.causeCode() == RecoveryCauseCode.GRAPH_STRUCTURE
                && "design-input".equals(owner)
                && profilesByRun.get(doc.run().runId()) != null
                && stageIdsAfter(profilesByRun.get(doc.run().runId()), owner).contains(doc.run().currentStageId())))) {
      return false;
    }
    return doc.run().stages().stream()
        .filter(stage -> owner.equals(stage.stageId()))
        .findFirst()
        .map(
            stage ->
                stage.status() == StageStatus.SUCCEEDED
                    && stage.outputRefs() != null
                    && !stage.outputRefs().isEmpty())
        .orElse(false);
  }

  /** A later plan approves semantic inputs, so their producers need no separate approval. */
  public static boolean isCommittedContractInputOwner(ProductPipelineRunDocument doc, String owner,
      RecoveryCause cause, ProductPipelineProfile profile, ProductPipelineArtifactStore artifactStore) {
    if (cause == null || cause.causeCode() != RecoveryCauseCode.CONTRACT_SHAPE || profile == null
        || !stageIdsAfter(profile, owner).contains(doc.run().currentStageId())) {
      return false;
    }
    var consumer = profile.stages().stream().filter(stage -> stage.stageId().equals(doc.run().currentStageId()))
        .findFirst().orElseThrow();
    return doc.run().stages().stream()
        .filter(stage -> owner.equals(stage.stageId()) && stage.status() == StageStatus.SUCCEEDED)
        .flatMap(stage -> stage.outputRefs().stream())
        .filter(ref -> consumer.consumes().stream().anyMatch(type -> type.matches(ref.kind())))
        .filter(ref -> cause.findings().stream().anyMatch(finding -> ref.artifactId().equals(finding.originRecordId())))
        .anyMatch(ref -> artifactStore.get(doc.run().runId(), ref)
            .filter(revision -> revision.provenance() != null
                && doc.run().runId().equals(revision.provenance().runId())
                && owner.equals(revision.provenance().stageId())).isPresent());
  }

  private static boolean isEarlierApprovedOwner(ProductPipelineRunDocument doc, String owner) {
    if (owner == null || owner.isBlank() || owner.equals(doc.run().currentStageId())) {
      return false;
    }
    return doc.run().stages().stream()
        .filter(stage -> owner.equals(stage.stageId()))
        .findFirst()
        .map(
            stage ->
                stage.approvedArtifactId() != null && !stage.approvedArtifactId().isBlank())
        .orElse(false);
  }

  private boolean catalogHasBeenWritten(String runId) {
    return artifactStore.hasCatalogWriteEvidence(runId);
  }

  private String causalReopenFailureSignature(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    Object evidence = attributes == null ? null : attributes.get(STAGE_ERROR_CONTEXT_ATTR);
    return ToolCallFingerprints.failureSignature(evidence instanceof String text ? text : "");
  }

  public static String causalReopenReason(String owner, String failureSignature) {
    return CAUSAL_REOPEN_REASON_PREFIX + owner + '\u0000' + failureSignature;
  }

  private String reopenReason(
      ProductPipelineRunDocument doc, String owner, AcceptInputCommand command) {
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    RecoveryAttemptLedger.ReopenInitiator initiator =
        command == null
            ? RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC
            : RecoveryAttemptLedger.ReopenInitiator.AUTHOR;
    return recoveryLedger.recordReopen(key, initiator, artifact);
  }

  private RecoveryCause currentRecoveryCause(String runId) {
    String json = stringAttribute(runId, STAGE_ERROR_CAUSE_JSON_ATTR).orElse("");
    if (!json.isBlank()) {
      try {
        return HALT_EVIDENCE_JSON.readValue(json, RecoveryCause.class);
      } catch (Exception e) {
        throw new IllegalStateException("Cannot restore the persisted recovery cause", e);
      }
    }
    String codeName = stringAttribute(runId, STAGE_ERROR_CAUSE_CODE_ATTR).orElse("");
    String findings = stringAttribute(runId, STAGE_ERROR_FINDINGS_ATTR).orElse("");
    String outcomeName = stringAttribute(runId, STAGE_ERROR_OUTCOME_ATTR).orElse("");
    String requestedFact = stringAttribute(runId, STAGE_ERROR_REQUESTED_FACT_ATTR).orElse("");
    StageOutcomeClass outcomeClass = StageOutcomeClass.VALIDATION_FAILURE;
    if (!outcomeName.isBlank()) {
      try {
        outcomeClass = StageOutcomeClass.valueOf(outcomeName);
      } catch (IllegalArgumentException ignored) {
        outcomeClass = StageOutcomeClass.VALIDATION_FAILURE;
      }
    }
    RecoveryCause fromFindings = RecoveryCause.fromFormattedFindingCodes(findings, outcomeClass);
    if (codeName.isBlank()) {
      return fromFindings;
    }
    try {
      return new RecoveryCause(
          RecoveryCauseCode.valueOf(codeName), fromFindings.findings(), requestedFact);
    } catch (IllegalArgumentException ignored) {
      return fromFindings;
    }
  }

  private Multi<PipelineSignal> causalReopenOwner(
      ProductPipelineRunDocument doc, AcceptInputCommand command, String owner) {
    return causalReopenOwner(
        doc, owner, command.commandId(), command.commandPayloadHash(), command);
  }

  private Multi<PipelineSignal> causalReopenOwner(
      ProductPipelineRunDocument doc,
      String owner,
      String commandId,
      String commandPayloadHash) {
    return causalReopenOwner(doc, owner, commandId, commandPayloadHash, null);
  }

  private Multi<PipelineSignal> causalReopenOwner(
      ProductPipelineRunDocument doc,
      String owner,
      String commandId,
      String commandPayloadHash,
      AcceptInputCommand command) {
    String runId = doc.run().runId();
    ProductPipelineProfile profile = profilesByRun.get(runId);
    StageSnapshot ownerSnapshot =
        doc.run().stages().stream()
            .filter(stage -> owner.equals(stage.stageId()))
            .findFirst()
            .orElse(null);
    Reference prior = ownerSnapshot == null ? null : ownerSnapshot.outputRefs().stream()
        .filter(ref -> ref.artifactId().equals(ownerSnapshot.approvedArtifactId())).findFirst()
        .orElseGet(() -> resolveReopenApprovable(ownerSnapshot));
    if (profile == null || ownerSnapshot == null || prior == null) {
      return command == null
          ? Multi.createFrom().empty()
          : refuseWithGuard(doc, command, HaltRecoveryGuard.MISSING_PROFILE_OR_PRIOR_CANDIDATE);
    }
    attributesByRun
        .computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
        .put(PRIOR_CANDIDATE_ATTR, prior.contentHash());
    if (currentRecoveryCause(runId).causeCode() == RecoveryCauseCode.MAPPING_CONTRACT) {
      attributesByRun.get(runId).put("mappingRepairAutomatic", Boolean.toString(command == null));
    }
    attributesByRun.get(runId).put(RECOVERY_PREPARING_OWNER_ATTR, owner);
    Set<String> afterOwner = stageIdsAfter(profile, owner);
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (owner.equals(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.RUNNING,
                snapshot.outputRefs(),
                snapshot.approvedArtifactId(),
                List.of(),
                null,
                snapshot.candidateRevision()));
      } else if (afterOwner.contains(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.PENDING,
                snapshot.outputRefs(),
                snapshot.approvedArtifactId(),
                List.of(),
                null,
                snapshot.candidateRevision()));
      } else {
        updated.add(snapshot);
      }
    }
    commitMove(
        doc,
        owner,
        updated,
        reopenReason(doc, owner, command),
        haltEvidence(attributesByRun.get(runId), prior.contentHash()),
        commandId,
        commandPayloadHash);
    return Multi.createFrom().empty();
  }

  /**
   * Moves to {@code ownerStageId} and resets later stages to PENDING. Does not consult the recovery
   * ledger; approval revisions and mapping-gap descriptions are not failure recovery.
   */
  private Multi<PipelineSignal> resetDownstreamAndMoveTo(
      ProductPipelineRunDocument doc,
      String ownerStageId,
      String reason,
      AcceptInputCommand command) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    if (profile == null) {
      throw new IllegalStateException("cannot reset downstream: profile is missing");
    }
    Set<String> afterOwner = stageIdsAfter(profile, ownerStageId);
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (ownerStageId.equals(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.RUNNING,
                snapshot.outputRefs(),
                null,
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
      } else if (afterOwner.contains(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.PENDING,
                List.of(),
                null,
                List.of(),
                null,
                null));
      } else {
        updated.add(snapshot);
      }
    }
    commitMove(
        doc, ownerStageId, updated, reason, haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(), command.commandPayloadHash());
    return Multi.createFrom().empty();
  }

  private Multi<PipelineSignal> recordOwnerChoice(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    boolean escalated =
        PipelineGates.STAGE_ESCALATED.equals(
            PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""));
    boolean internalFailure =
        PipelineGates.STAGE_INTERNAL_FAILURE.equals(
            PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""));
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, command.text());
    if (escalated || internalFailure) {
      return applyDiagnosedOwner(doc, command);
    }
    if (isCurrentUnapprovedOwner(doc, command.text())) {
      commitStatus(
          doc,
          RunStatus.RUNNING,
          StageStatus.RUNNING,
          doc.run().stages(),
          "accepted input",
          null,
          command.commandId(),
          command.commandPayloadHash());
      return Multi.createFrom().empty();
    }
    String body = PipelineGates.strip(latestWaitingForInputPrompt(doc));
    String prompt = PipelineGates.retag(PipelineGates.STAGE_REVISE, body);
    commitStatus(
        doc,
        RunStatus.WAITING_FOR_INPUT,
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages(),
        prompt,
        null,
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(new PipelineSignal.WaitingForInput(doc.run().currentStageId(), prompt));
  }

  private static boolean isOwnerChoicePick(ProductPipelineRunDocument doc, String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String prompt = latestWaitingForInputPrompt(doc);
    String gate = PipelineGates.gateOf(prompt).orElse("");
    if (!PipelineGates.OWNER_CHOICE.equals(gate)
        && !PipelineGates.STAGE_INTERNAL_FAILURE.equals(gate)
        && !PipelineGates.STAGE_ESCALATED.equals(gate)) {
      return false;
    }
    return PipelineGates.ownerCandidatesOf(prompt).contains(text);
  }

  private static boolean isStopWithReportAction(ProductPipelineRunDocument doc, String text) {
    if (!PipelineGates.STOP_WITH_REPORT_ACTION.equals(text)) {
      return false;
    }
    String prompt = latestWaitingForInputPrompt(doc);
    String gate = PipelineGates.gateOf(prompt).orElse("");
    if (PipelineGates.STAGE_ESCALATED.equals(gate)) {
      return PipelineGates.escalatedActionsOf(prompt).contains(text);
    }
    if (PipelineGates.STAGE_INTERNAL_FAILURE.equals(gate)) {
      return PipelineGates.internalFailureActionsOf(prompt).contains(text);
    }
    return PipelineGates.isContextualRecoveryGate(gate);
  }

  private static boolean isEscalatedAction(
      ProductPipelineRunDocument doc, String text, String expectedAction) {
    if (!expectedAction.equals(text)) {
      return false;
    }
    String prompt = latestWaitingForInputPrompt(doc);
    return PipelineGates.STAGE_ESCALATED.equals(PipelineGates.gateOf(prompt).orElse(""))
        && PipelineGates.escalatedActionsOf(prompt).contains(text);
  }

  private Multi<PipelineSignal> dropBlockingElement(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    attributesByRun
        .computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>())
        .put(HALT_FOLLOW_UP_TEXT_ATTR, PipelineGates.DROP_ELEMENT_ACTION);
    commitStatus(
        doc,
        RunStatus.RUNNING,
        StageStatus.RUNNING,
        doc.run().stages(),
        "drop blocking element",
        null,
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom().empty();
  }

  private void recordRecoverySelection(String runId, String gate, String pipelineAction) {
    if (!PipelineGates.isContextualRecoveryGate(gate) || pipelineAction == null) {
      return;
    }
    String semantic = recoveryTelemetry.semanticAction(gate, pipelineAction);
    List<String> offered = ChatEvent.actionsForGate(gate);
    if (offered == null || !offered.contains(semantic)) {
      return;
    }
    recoveryTelemetry.recordSelected(runId, semantic);
  }

  private Multi<PipelineSignal> stopWithReport(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    recoveryTelemetry.recordUserExit(command.runId());
    StageOutcomeClass outcomeClass = haltOutcomeClass(command.runId());
    String evidence = stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR).orElse("halted");
    Revision report =
        artifactStore.append(
            new AppendCommand(
                command.runId(),
                Kind.FAILURE_RECORD,
                "1",
                "product-pipeline-runtime",
                "1",
                new FailureRecord(
                    failureClass(outcomeClass),
                    doc.run().currentStageId(),
                    "stop-" + doc.run().runRevision(),
                    evidence,
                    false),
                List.of(),
                null,
                provenance(
                    command.runId(),
                    doc.run().currentStageId(),
                    currentStage(doc).capabilityId())));
    List<StageSnapshot> stages = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (!snapshot.stageId().equals(doc.run().currentStageId())) {
        stages.add(snapshot);
        continue;
      }
      List<Reference> outputs = new ArrayList<>(snapshot.outputRefs());
      outputs.add(report.reference());
      stages.add(
          new StageSnapshot(
              snapshot.stageId(),
              StageStatus.FAILED,
              outputs,
              snapshot.approvedArtifactId(),
              snapshot.candidateReferences(),
              snapshot.approvableReference(),
              snapshot.candidateRevision()));
    }
    commitStatus(
        doc,
        RunStatus.FAILED,
        StageStatus.FAILED,
        stages,
        evidence,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom()
        .item(
            new PipelineSignal.Failed(
                doc.run().currentStageId(),
                outcomeClass == null ? StageOutcomeClass.DOMAIN_FAILURE : outcomeClass,
                evidence));
  }

  private static FailureClass failureClass(StageOutcomeClass outcomeClass) {
    if (outcomeClass == null) {
      return FailureClass.DOMAIN;
    }
    return switch (outcomeClass) {
      case VALIDATION_FAILURE -> FailureClass.VALIDATION;
      case CONTRACT_FAILURE, INTERNAL_FAILURE -> FailureClass.CONTRACT;
      case POLICY_FAILURE -> FailureClass.POLICY;
      case MISSING_MANDATORY_INPUT -> FailureClass.MISSING_MANDATORY_INPUT;
      case RETRYABLE_TECHNICAL_FAILURE -> FailureClass.TECHNICAL;
      default -> FailureClass.DOMAIN;
    };
  }

  private String diagnosedOwnerOf(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return "";
    }
    Object value = attributes.get(DIAGNOSED_OWNER_STAGE_ATTR);
    return value instanceof String text ? text : "";
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

  private boolean isHaltFollowUp(ProductPipelineRunDocument doc, String text) {
    if (PipelineGates.isHaltCardAction(text) || doc.run().status() != RunStatus.WAITING_FOR_INPUT) {
      return false;
    }
    if (isOwnerChoicePick(doc, text)) {
      return false;
    }
    String gate = PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
    if (PipelineGates.TARGET_FOLDER_CHOICE.equals(gate)
        || PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION.equals(gate)) {
      return false;
    }
    if (PipelineGates.STAGE_CLARIFICATION.equals(gate)) {
      if (OwnerCandidateSet.requestsNamedStage(text)) {
        return true;
      }
      return currentRecoveryCause(doc.run().runId()).isMissingCatalogBinding();
    }
    return PipelineGates.isRecoverableHaltGate(gate);
  }

  private static String haltFollowUpInputId(AcceptInputCommand command) {
    String suffix =
        command.commandId() == null || command.commandId().isBlank()
            ? UUID.randomUUID().toString()
            : command.commandId();
    return HALT_FOLLOW_UP_INPUT_PREFIX + suffix;
  }

  private static boolean isHaltFollowUpInput(UserInput input) {
    return input != null
        && input.inputId() != null
        && input.inputId().startsWith(HALT_FOLLOW_UP_INPUT_PREFIX);
  }

  /** Latest halt follow-up text on this run, or empty when none has been recorded. */
  public Optional<String> haltFollowUpText(String runId) {
    return stringAttribute(runId, HALT_FOLLOW_UP_TEXT_ATTR);
  }

  /** Diagnosed owner from the last halt, or empty when none was chosen. */
  public Optional<String> diagnosedOwnerStageId(String runId) {
    return stringAttribute(runId, DIAGNOSED_OWNER_STAGE_ATTR);
  }

  /** Current run attribute snapshot for tests and diagnostics. */
  public Map<String, Object> runAttributes(String runId) {
    Objects.requireNonNull(runId, "runId");
    Map<String, Object> attributes = attributesByRun.get(runId);
    return attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  private Optional<String> stringAttribute(String runId, String key) {
    Objects.requireNonNull(runId, "runId");
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return Optional.empty();
    }
    Object value = attributes.get(key);
    if (value instanceof String text && !text.isBlank()) {
      return Optional.of(text);
    }
    return Optional.empty();
  }

  /** Records approval without selecting or running the next stage. */
  public Multi<PipelineSignal> recordApprove(ApproveCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              ensureDurablePinsLoaded(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return Multi.createFrom().empty();
              }
              if (isTerminalRunStatus(doc.run().status())) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "run is already terminal: " + doc.run().status()));
              }
              if (doc.run().status() != RunStatus.WAITING_FOR_APPROVAL) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "run is not waiting for approval: " + doc.run().status()));
              }
              if (command.expectedRunRevision() != doc.run().runRevision()) {
                return Multi.createFrom()
                    .failure(
                        new StaleApprovalException(
                            "expected runRevision "
                                + command.expectedRunRevision()
                                + " but was "
                                + doc.run().runRevision()));
              }
              Reference target = command.target();
              StageSnapshot stage =
                  doc.run().stages().stream()
                      .filter(s -> s.stageId().equals(doc.run().currentStageId()))
                      .findFirst()
                      .orElseThrow();
              ProfileStage stageProfile = currentStage(doc);
              Reference approvable =
                  stage.approvableReference() != null
                      ? stage.approvableReference()
                      : stage.candidateReferences().stream()
                          .reduce((a, b) -> b)
                          .orElse(null);
              if (approvable == null || !approvable.equals(target)) {
                return Multi.createFrom()
                    .failure(
                        new StaleApprovalException(
                            "approval target is not the current approvable candidate"));
              }
              boolean holdUnchangedBrief =
                  shouldHoldUnchangedExhaustedBrief(doc, stage, target);
              if (!holdUnchangedBrief) {
                recordSupersededBriefOnRepairApproval(command.runId(), target);
              }
              List<Reference> approvedCandidates =
                  approvedCandidates(stage.candidateReferences(), stageProfile.approval());
              boolean multiItemApproval =
                  stageProfile.approval() != null
                      && stageProfile.approval().candidateSet().size() > 1;
              Revision approvalRevision;
              if (multiItemApproval) {
                ApprovalPolicy approvalPolicy = stageProfile.approval();
                ApprovalRecordV2 approvalRecord =
                    semanticApprovalRecord(
                        command.runId(),
                        target,
                        approvedCandidates,
                        approvalPolicy);
                approvalRevision =
                    artifactStore.append(
                        new AppendCommand(
                            command.runId(),
                            Kind.APPROVAL_RECORD,
                            "2",
                            "product-pipeline-runtime",
                            "1",
                            approvalRecord,
                            approvedCandidates,
                            null,
                            provenance(
                                command.runId(),
                                doc.run().currentStageId(),
                                stageProfile.capabilityId())));
              } else {
                approvalRevision =
                    artifactStore.append(
                        new AppendCommand(
                            command.runId(),
                            Kind.APPROVAL_RECORD,
                            "1",
                            "product-pipeline-runtime",
                            "1",
                            new ApprovalRecord(
                                target, target.contentHash(), "user", null, clock.instant()),
                            List.of(target),
                            null,
                            provenance(
                                command.runId(),
                                doc.run().currentStageId(),
                                stageProfile.capabilityId())));
              }
              List<Reference> approvedOutputs = new ArrayList<>(stage.candidateReferences());
              approvedOutputs.add(approvalRevision.reference());

              List<StageSnapshot> updated = new ArrayList<>();
              for (StageSnapshot snapshot : doc.run().stages()) {
                if (snapshot.stageId().equals(doc.run().currentStageId())) {
                  updated.add(
                      new StageSnapshot(
                          snapshot.stageId(),
                          StageStatus.SUCCEEDED,
                          approvedOutputs,
                          target.artifactId(),
                          snapshot.candidateReferences(),
                          target,
                          snapshot.candidateRevision()));
                } else {
                  updated.add(snapshot);
                }
              }

              ProductPipelineProfile profile = profilesByRun.get(command.runId());
              String currentStageId = doc.run().currentStageId();
              if (holdUnchangedBrief) {
                return holdUnchangedExhaustedBrief(doc, command, updated);
              }
              boolean promotesReplacement = currentStageId.equals(replacementApprovalStage(profile,
                  attributesByRun.get(command.runId())));
              String promotionEvidence = null;
              boolean promotesSource = "requirement-analysis".equals(currentStageId);
              if (promotesReplacement || promotesSource) {
                if (promotesReplacement) { updated = promoteReplacement(profile, attributesByRun.get(command.runId()), updated, currentStageId); }
                Map<String, Object> acceptedAttributes = new LinkedHashMap<>(attributesByRun.get(command.runId()));
                if (promotesReplacement) { acceptedAttributes.put(RECOVERY_PREPARING_OWNER_ATTR, ""); }
                if (promotesSource) { acceptedAttributes.put(ACCEPTED_SOURCE_BRIEF_REF_ATTR, ""); }
                promotionEvidence = haltEvidence(acceptedAttributes, null);
              }
              String correction = recordExhaustedBriefCorrection(doc, currentStageId, updated);
              ImplementationGatePolicy gate = profile.implementationGate();
              if (gate != null && currentStageId.equals(gate.afterStageId())) {
                commitStatus(
                    doc,
                    RunStatus.WAITING_FOR_IMPLEMENT,
                    StageStatus.SUCCEEDED,
                    updated,
                    "waiting for implement",
                    promotionEvidence,
                    command.commandId(),
                    command.commandPayloadHash());
                if (promotesReplacement) { attributesByRun.get(command.runId()).remove(RECOVERY_PREPARING_OWNER_ATTR); }
              if (promotesSource) { attributesByRun.get(command.runId()).remove(ACCEPTED_SOURCE_BRIEF_REF_ATTR); }
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.WaitingForImplement(
                            currentStageId, target.contentHash()));
              }
              boolean terminal = profile.terminal().stageId().equals(currentStageId);
              if (terminal) {
                RunStatus terminalStatus = terminalStatus(profile);
                commitStatus(
                    doc,
                    terminalStatus,
                    StageStatus.SUCCEEDED,
                    updated,
                    "plan approved",
                    promotionEvidence,
                    command.commandId(),
                    command.commandPayloadHash());
                if (promotesReplacement) { attributesByRun.get(command.runId()).remove(RECOVERY_PREPARING_OWNER_ATTR); }
              if (promotesSource) { attributesByRun.get(command.runId()).remove(ACCEPTED_SOURCE_BRIEF_REF_ATTR); }
                return Multi.createFrom().item(new PipelineSignal.Completed(terminalStatus));
              }

              String nextStageId = nextStageId(profile, currentStageId);
              // Drop stage-local reply text so the next stage cannot misread discovery / Agree as
              // an IDS path choice.
              clearStageLocalReplyAttributes(command.runId());
              commitStatus(
                  doc,
                  RunStatus.RUNNING,
                  StageStatus.SUCCEEDED,
                  updated,
                  correction.isEmpty() ? "approved" : correction,
                  promotionEvidence,
                  command.commandId(),
                  command.commandPayloadHash());
              if (promotesReplacement) { attributesByRun.get(command.runId()).remove(RECOVERY_PREPARING_OWNER_ATTR); }
              if (promotesSource) { attributesByRun.get(command.runId()).remove(ACCEPTED_SOURCE_BRIEF_REF_ATTR); }
              ProductPipelineRunDocument after = requireRun(command.runId());
              commitMove(after, nextStageId, markStageRunning(after, nextStageId), "advance after approval");
              return Multi.createFrom().empty();
            });
  }

  /**
   * True when this approval is the exhausted known-brief-defect edit path and the approved brief
   * is the same canonical artifact automatic replay already observed.
   */
  private boolean shouldHoldUnchangedExhaustedBrief(
      ProductPipelineRunDocument doc, StageSnapshot stage, Reference target) {
    if (target == null || target.kind() != Kind.REQUIREMENT_BRIEF) {
      return false;
    }
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    if (!cause.isKnownBriefDefect()) {
      return false;
    }
    String owner = doc.run().currentStageId();
    List<StageSnapshot> preview = previewApprovedStages(doc, stage, target);
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(preview, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    String legacy = causalReopenFailureSignature(doc.run().runId());
    if (!recoveryLedger.automaticReopenRecorded(doc.transitions(), key, legacy)) {
      return false;
    }
    return !recoveryLedger.correctionAdvancesEpoch(doc.transitions(), key, artifact);
  }

  private static List<StageSnapshot> previewApprovedStages(
      ProductPipelineRunDocument doc, StageSnapshot stage, Reference target) {
    List<StageSnapshot> preview = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.stageId().equals(stage.stageId())) {
        preview.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.SUCCEEDED,
                snapshot.candidateReferences(),
                target.artifactId(),
                snapshot.candidateReferences(),
                target,
                snapshot.candidateRevision()));
      } else {
        preview.add(snapshot);
      }
    }
    return preview;
  }

  private String recordExhaustedBriefCorrection(
      ProductPipelineRunDocument doc, String owner, List<StageSnapshot> updated) {
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    if (!cause.isKnownBriefDefect()) {
      return "";
    }
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(updated, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    String legacy = causalReopenFailureSignature(doc.run().runId());
    if (!recoveryLedger.automaticReopenRecorded(doc.transitions(), key, legacy)) {
      return "";
    }
    return recoveryLedger.recordCorrection(
        doc.transitions(), key, artifact, InputOrigin.TRUSTED);
  }

  /**
   * Keeps downstream execution paused after an unchanged brief. The exhausted edit card stays on
   * the failed stage so the author can correct again.
   */
  private Multi<PipelineSignal> holdUnchangedExhaustedBrief(
      ProductPipelineRunDocument doc, ApproveCommand command, List<StageSnapshot> approved) {
    String failedStage =
        stringAttribute(command.runId(), STAGE_ERROR_FAILED_STAGE_ATTR)
            .orElse(doc.run().currentStageId());
    String prompt = latestWaitingForInputPrompt(doc);
    List<StageSnapshot> waiting = new ArrayList<>();
    for (StageSnapshot snapshot : approved) {
      if (failedStage.equals(snapshot.stageId())) {
        waiting.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.WAITING_FOR_INPUT,
                snapshot.outputRefs(),
                snapshot.approvedArtifactId(),
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
      } else {
        waiting.add(snapshot);
      }
    }
    commitWaitAt(
        doc,
        failedStage,
        waiting,
        prompt,
        haltEvidence(attributesByRun.get(command.runId()), null),
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom().item(new PipelineSignal.WaitingForInput(failedStage, prompt));
  }

  /** Records the implementation gate command without selecting or running the next stage. */
  public Multi<PipelineSignal> recordImplement(ImplementCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              ensureDurablePinsLoaded(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return Multi.createFrom().empty();
              }
              if (doc.run().status() != RunStatus.WAITING_FOR_IMPLEMENT) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "run is not waiting for implement: " + doc.run().status()));
              }
              if (command.expectedRunRevision() != doc.run().runRevision()) {
                return Multi.createFrom()
                    .failure(
                        new StaleApprovalException(
                            "expected runRevision "
                                + command.expectedRunRevision()
                                + " but was "
                                + doc.run().runRevision()));
              }
              ProductPipelineProfile profile = profilesByRun.get(command.runId());
              if (profile == null || profile.implementationGate() == null) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "profile does not declare an implementation gate"));
              }
              ImplementationGatePolicy gate = profile.implementationGate();
              ApprovalRecordV2 approval = latestApprovalRecordV2(command.runId());
              if (!gate.targetArtifact().matches(approval.target().kind())) {
                return Multi.createFrom()
                    .failure(
                        new StaleApprovalException(
                            "approval target kind does not match implementation gate"));
              }
              if (!Objects.equals(
                  approval.targetContentHash(), command.approvedPlanContentHash())) {
                return Multi.createFrom()
                    .failure(
                        new StaleApprovalException(
                            "approved plan content hash does not match implementation gate target"));
              }
              String nextStageId = nextStageId(profile, doc.run().currentStageId());
              commitMove(
                  doc,
                  nextStageId,
                  markStageRunning(doc, nextStageId),
                  "advance after implement",
                  command.commandId(),
                  command.commandPayloadHash());
              return Multi.createFrom().empty();
            });
  }

  private Multi<PipelineSignal> applyStageDecision(String runId, StageExecutionResult result) {
    return switch (result.decision()) {
      case StageDecision.Continue continueDecision ->
          applyContinue(runId, continueDecision, result.signals());
      case StageDecision.Retry ignored -> Multi.createFrom().iterable(result.signals());
      case StageDecision.ReopenProducer reopen -> applyReopenProducer(runId, reopen, result.signals());
      case StageDecision.WaitForApproval wait ->
          applyWaitForApproval(runId, wait, result.signals());
      case StageDecision.WaitForInput ignored -> Multi.createFrom().iterable(result.signals());
      case StageDecision.WaitForImplementation ignored ->
          Multi.createFrom().iterable(result.signals());
      case StageDecision.Fail ignored -> Multi.createFrom().iterable(result.signals());
      case StageDecision.Complete ignored -> Multi.createFrom().iterable(result.signals());
    };
  }

  private Multi<PipelineSignal> applyReopenProducer(
      String runId, StageDecision.ReopenProducer reopen, List<PipelineSignal> signals) {
    ProductPipelineRunDocument doc = requireRun(runId);
    if (shouldCausalReopen(
        doc,
        reopen.producerStageId(),
        InputOrigin.TRUSTED,
        RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC)) {
      rememberAuthorNote(runId, reopen.authorNote());
      return causalReopenOwner(doc, reopen.producerStageId(), null, null)
          .onCompletion()
          .switchTo(() -> Multi.createFrom().iterable(signals));
    }
    return Multi.createFrom().iterable(signals);
  }

  private Multi<PipelineSignal> applyContinue(
      String runId, StageDecision.Continue decision, List<PipelineSignal> signals) {
    ProductPipelineRunDocument doc = requireRun(runId);
    StageSnapshot completed = doc.run().stages().stream()
        .filter(stage -> decision.stageId().equals(stage.stageId())).findFirst().orElse(null);
    if (doc.run().status() != RunStatus.RUNNING || !decision.stageId().equals(doc.run().currentStageId())
        || completed == null || completed.status() != StageStatus.SUCCEEDED) {
      return Multi.createFrom().iterable(signals);
    }
    ProductPipelineProfile profile = profilesByRun.get(runId);
    pinSemanticRevisionOf(runId, decision.stageId());
    Map<String, Object> attributes = attributesByRun.get(runId);
    boolean promote = preparingReplacement(profile, attributes, decision.stageId())
        && replacementApprovalStage(profile, attributes).isEmpty();
    List<StageSnapshot> selected = promote
        ? promoteReplacement(profile, attributes, doc.run().stages(), decision.stageId()) : doc.run().stages();
    String next = nextStageId(profile, decision.stageId());
    List<StageSnapshot> advancing = selected.stream().map(stage -> next.equals(stage.stageId())
        ? new StageSnapshot(stage.stageId(), StageStatus.RUNNING, stage.outputRefs(), stage.approvedArtifactId(),
            stage.candidateReferences(), stage.approvableReference(), stage.candidateRevision()) : stage).toList();
    Map<String, Object> persistedAttributes = new LinkedHashMap<>(attributes);
    if (promote) { persistedAttributes.put(RECOVERY_PREPARING_OWNER_ATTR, ""); }
    commitMove(doc, next, advancing, "advance after success", haltEvidence(persistedAttributes, null), null, null);
    if (promote) { attributes.remove(RECOVERY_PREPARING_OWNER_ATTR); }
    return Multi.createFrom().iterable(signals);
  }

  private Multi<PipelineSignal> applyWaitForApproval(
      String runId, StageDecision.WaitForApproval wait, List<PipelineSignal> signals) {
    List<PipelineSignal> emitted = new ArrayList<>();
    for (PipelineSignal signal : signals) {
      if (signal instanceof PipelineSignal.WaitingForApproval) {
        emitImplementationPlanForReview(runId, wait.candidate(), emitted);
        emitIdsDocumentForReview(runId, wait.candidate(), emitted);
        emitRequirementBriefForReview(runId, wait.candidate(), emitted);
      }
      emitted.add(signal);
    }
    return Multi.createFrom().iterable(emitted);
  }

  private void commitStatus(
      ProductPipelineRunDocument doc,
      RunStatus nextStatus,
      StageStatus stageStatus,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence) {
    commitStatus(doc, nextStatus, stageStatus, stages, reason, failureEvidence, null, null);
  }

  /**
   * Commits a status transition and, when {@code commandId} is present, the durable evidence that
   * this external command produced it. Both land in one compare-and-set write.
   */
  private void commitStatus(
      ProductPipelineRunDocument doc,
      RunStatus nextStatus,
      StageStatus stageStatus,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence,
      String commandId,
      String commandPayloadHash) {
    long expected = doc.run().runRevision();
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
                stageStatus,
                clock.instant(),
                clock.instant(),
                stages.stream()
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
                commandId,
                commandPayloadHash,
                waitingClarification(doc, nextStatus, expected + 1L, reason))));
    if (stageStatus == StageStatus.SUCCEEDED) {
      settleActiveRepair(doc.run().currentStageId(), attributesByRun.get(doc.run().runId()));
    }
  }

  private void commitMove(
      ProductPipelineRunDocument doc,
      String nextStageId,
      List<StageSnapshot> stages,
      String reason) {
    commitMove(doc, nextStageId, stages, reason, null, null, null);
  }

  private void commitMove(
      ProductPipelineRunDocument doc,
      String nextStageId,
      List<StageSnapshot> stages,
      String reason,
      String commandId,
      String commandPayloadHash) {
    commitMove(doc, nextStageId, stages, reason, null, commandId, commandPayloadHash);
  }

  /**
   * Moves to the next stage and, when {@code commandId} is present, records the durable evidence
   * that this external command caused the move. Both land in one compare-and-set write.
   */
  private void commitMove(
      ProductPipelineRunDocument doc,
      String nextStageId,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence,
      String commandId,
      String commandPayloadHash) {
    long expected = doc.run().runRevision();
    runStore.commit(
        expected,
        new LogicalCommit(
            doc.run().runId(),
            expected,
            RunStatus.RUNNING,
            nextStageId,
            stages,
            new StageAttempt(
                UUID.randomUUID().toString(),
                nextStageId,
                expected + 1L,
                StageStatus.RUNNING,
                clock.instant(),
                clock.instant(),
                List.of(),
                failureEvidence),
            new RunTransition(
                expected,
                expected + 1L,
                doc.run().status(),
                RunStatus.RUNNING,
                nextStageId,
                clock.instant(),
                reason,
                commandId,
                commandPayloadHash,
                pendingAnswer(doc))));
  }

  /**
   * Moves currentStageId to {@code stageId} and waits there. Used when an unchanged exhausted brief
   * must not resume downstream execution.
   */
  private void commitWaitAt(
      ProductPipelineRunDocument doc,
      String stageId,
      List<StageSnapshot> stages,
      String reason,
      String failureEvidence,
      String commandId,
      String commandPayloadHash) {
    long expected = doc.run().runRevision();
    runStore.commit(
        expected,
        new LogicalCommit(
            doc.run().runId(),
            expected,
            RunStatus.WAITING_FOR_INPUT,
            stageId,
            stages,
            new StageAttempt(
                UUID.randomUUID().toString(),
                stageId,
                expected + 1L,
                StageStatus.WAITING_FOR_INPUT,
                clock.instant(),
                clock.instant(),
                List.of(),
                failureEvidence),
            new RunTransition(
                expected,
                expected + 1L,
                doc.run().status(),
                RunStatus.WAITING_FOR_INPUT,
                stageId,
                clock.instant(),
                reason,
                commandId,
                commandPayloadHash,
                requirementClarification(doc, expected + 1L, reason, stageId))));
  }

  /**
   * Offer for a requirement question wait, written in the same compare-and-set as that wait.
   * When this wait consumes the open offer, the same envelope also carries that answer.
   */
  public ClarificationTransition requirementClarification(
      ProductPipelineRunDocument doc, long targetRevision, String reason) {
    return requirementClarification(doc, targetRevision, reason, doc.run().currentStageId());
  }

  private ClarificationTransition waitingClarification(
      ProductPipelineRunDocument doc, RunStatus nextStatus, long targetRevision, String reason) {
    if (nextStatus != RunStatus.WAITING_FOR_INPUT) {
      return pendingAnswer(doc);
    }
    return requirementClarification(doc, targetRevision, reason, doc.run().currentStageId());
  }

  private ClarificationTransition requirementClarification(
      ProductPipelineRunDocument doc, long targetRevision, String reason, String stageId) {
    var answer = pendingAnswer(doc);
    if (requirementDrafts == null
        || PipelineGates.gateOf(reason).filter(PipelineGates.STAGE_CLARIFICATION::equals).isEmpty()
        || !RequirementDiscoveryCapability.CAPABILITY_ID.equals(capabilityId(doc, stageId))) {
      return answer;
    }
    var accepted = requirementDrafts.latestRevision(doc.run().conversationId()).orElse(null);
    var draft = requirementDrafts.get(doc.run().conversationId()).orElse(null);
    if (accepted == null || draft == null) {
      return answer;
    }
    String generation = "";
    if (conversations != null) {
      String archived = conversations.archive().read(doc.run().conversationId()).generation();
      generation = archived == null ? "" : archived;
    }
    var coordinator = new RequirementClarificationCoordinator();
    var active = coordinator.activeQuestion(accepted, draft, generation).orElse(null);
    if (active == null) {
      return answer;
    }
    return new ClarificationTransition(coordinator.createOffer(active, targetRevision),
        answer == null ? null : answer.answer());
  }

  /**
   * The accepted answer for the offer that is still open. A later commit does not attach it again.
   */
  private ClarificationTransition pendingAnswer(ProductPipelineRunDocument doc) {
    if (requirementDrafts == null || doc == null || doc.run() == null) {
      return null;
    }
    var answer = requirementDrafts.latestClarificationAnswer(doc.run().conversationId()).orElse(null);
    var open = openOffer(doc);
    if (answer == null || open == null || !open.offerId().equals(answer.offerId())) {
      return null;
    }
    return new ClarificationTransition(null, answer);
  }

  private static ClarificationTransition.Offer openOffer(ProductPipelineRunDocument doc) {
    ClarificationTransition.Offer open = null;
    for (var transition : doc.transitions()) {
      var clarification = transition.clarification();
      if (clarification == null) {
        continue;
      }
      if (clarification.answer() != null && open != null
          && clarification.answer().offerId().equals(open.offerId())) {
        open = null;
      }
      if (clarification.offer() != null) {
        open = clarification.offer();
      }
    }
    return open;
  }

  private String capabilityId(ProductPipelineRunDocument doc, String stageId) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    if (profile != null) {
      for (ProfileStage stage : profile.stages()) {
        if (stage.stageId().equals(stageId) && stage.capabilityId() != null && !stage.capabilityId().isBlank()) {
          return stage.capabilityId();
        }
      }
    }
    return stageId == null ? "" : stageId;
  }

  private static List<StageSnapshot> markStageRunning(
      ProductPipelineRunDocument doc, String stageId) {
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (snapshot.stageId().equals(stageId)) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.RUNNING,
                snapshot.outputRefs(),
                null,
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
      } else {
        updated.add(snapshot);
      }
    }
    return updated;
  }

  private List<Reference> approvedCandidates(List<Reference> refs, ApprovalPolicy approval) {
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

  private ProfileStage currentStage(ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    return profile.stages().stream()
        .filter(stage -> stage.stageId().equals(doc.run().currentStageId()))
        .findFirst()
        .orElseThrow();
  }

  private static String nextStageId(ProductPipelineProfile profile, String currentStageId) {
    List<ProfileStage> stages = profile.stages();
    for (int i = 0; i < stages.size() - 1; i++) {
      if (stages.get(i).stageId().equals(currentStageId)) {
        return stages.get(i + 1).stageId();
      }
    }
    throw new IllegalStateException("no next stage after " + currentStageId);
  }

  private static Reference resolveReopenApprovable(StageSnapshot snapshot) {
    if (snapshot.approvableReference() != null) {
      return snapshot.approvableReference();
    }
    for (Reference ref : snapshot.outputRefs()) {
      if (ref.kind() == Kind.REQUIREMENT_DRAFT) {
        return ref;
      }
    }
    if (!snapshot.candidateReferences().isEmpty()) {
      return snapshot.candidateReferences().get(snapshot.candidateReferences().size() - 1);
    }
    for (int i = snapshot.outputRefs().size() - 1; i >= 0; i--) {
      Reference ref = snapshot.outputRefs().get(i);
      if (ref != null && ref.kind() != Kind.APPROVAL_RECORD) {
        return ref;
      }
    }
    return null;
  }

  private static Set<String> stageIdsAfter(ProductPipelineProfile profile, String stageId) {
    boolean after = false;
    Set<String> ids = new java.util.LinkedHashSet<>();
    for (ProfileStage stage : profile.stages()) {
      if (after) {
        ids.add(stage.stageId());
      }
      if (stage.stageId().equals(stageId)) {
        after = true;
      }
    }
    return ids;
  }

  /** Reloads profile and manifest pins from durable evidence after a process restart. */
  public void ensureDurablePinsLoaded(String runId) {
    Objects.requireNonNull(runId, "runId");
    if (profilesByRun.containsKey(runId)
        && manifestsByRun.containsKey(runId)
        && attributesByRun.containsKey(runId)) {
      return;
    }
    hydrateCaches(requireRun(runId), null);
  }

  private ProductPipelineRunDocument requireRun(String runId) {
    return runStore
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
  }

  private void hydrateCaches(ProductPipelineRunDocument doc, StartOrResumeCommand command) {
    String runId = doc.run().runId();
    RunManifest manifest =
        artifactStore
            .latest(runId, Kind.RUN_MANIFEST)
            .map(revision -> artifactStore.payload(revision, RunManifest.class))
            .orElse(command == null ? null : command.runManifest());
    if (manifest == null) {
      throw new IllegalStateException("cannot restore Flow pins: RUN_MANIFEST is missing for " + runId);
    }
    ProductPipelineProfile profile;
    if (profileCatalog != null
        && manifest.profileId() != null
        && manifest.profileVersion() != null) {
      profile = profileCatalog.require(manifest.profileId(), manifest.profileVersion());
    } else if (command != null) {
      profile = command.profile();
    } else {
      throw new IllegalStateException("cannot restore profile pins for " + runId);
    }
    if (profile == null) {
      throw new IllegalStateException("cannot restore profile pins for " + runId);
    }
    profilesByRun.put(runId, profile);
    manifestsByRun.put(runId, manifest);
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>());
    Reference selectedBrief = selectedRequirementBriefReference(doc);
    attributes.remove("requirementBrief");
    attributes.remove("requirementBriefContentHash");
    (selectedBrief == null ? Optional.<Revision>empty() : artifactStore.get(runId, selectedBrief))
        .ifPresent(
            revision -> {
              attributes.put(
                  "requirementBrief",
                  artifactStore.payload(revision, RequirementBrief.class));
              attributes.put("requirementBriefContentHash", revision.contentHash());
            });
    List<UserInput> allInputs =
        artifactStore.history(runId, Kind.USER_INPUT).stream()
            .map(revision -> artifactStore.payload(revision, UserInput.class))
            .toList();
    UserInput latestConfirmation = null;
    for (UserInput input : allInputs) {
      if (MappingGapPassThroughConfirmation.parse(input.text()).isPresent()) {
        latestConfirmation = input;
      }
    }
    if (latestConfirmation != null) {
      MappingGapPassThroughConfirmation.parse(latestConfirmation.text())
          .ifPresent(confirmation -> attributes.put("mappingGapPassThrough", confirmation));
    }
    String currentStageId = doc.run().currentStageId();
    List<UserInput> stageInputs =
        allInputs.stream()
            .filter(
                input ->
                    input.targetStageId().equals(currentStageId)
                        && MappingGapPassThroughConfirmation.parse(input.text()).isEmpty())
            .toList();
    List<UserInput> requirementInputs =
        stageInputs.stream().filter(input -> !isHaltFollowUpInput(input)).toList();
    List<UserInput> followUps =
        stageInputs.stream().filter(ProductPipelineRunSupport::isHaltFollowUpInput).toList();
    if (!requirementInputs.isEmpty()) {
      UserInput latest = requirementInputs.get(requirementInputs.size() - 1);
      attributes.put("userText", latest.text());
      attributes.put("discoveryUserText", requirementInputs.get(0).text());
      if (latest.draftReference() != null) {
        attributes.put("requirementCorrectionDraftReference", latest.draftReference());
        attributes.put("requirementCorrectionDraft", artifactStore.get(runId, latest.draftReference())
            .map(revision -> artifactStore.payload(revision, org.qubership.integration.platform.ai.plan.RequirementDraft.class))
            .orElseThrow());
        attributes.put("requirementCorrectionSourceTurnIds", latest.sourceTurnIds());
      }
    }
    if (!followUps.isEmpty()) {
      attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, followUps.get(followUps.size() - 1).text());
    }
    // Rehydrate only this run's counters. Clearing the whole map would drop retries for
    // other in-memory runs that share the same runtime bean.
    technicalRetriesByStage.keySet().removeIf(key -> key.startsWith(runId + ":"));
    technicalRetriesByStage.putAll(consecutiveTechnicalRetries(runId, doc.attempts(), doc.transitions()));
    restoreHaltEvidence(doc, attributes);
  }

  /**
   * Puts this stage's last halt JSON onto {@code attributes} when that stage is about to run. A
   * causal reopen moves {@code currentStageId} and hydrate would otherwise restore only the new
   * owner's attempts, dropping the observing stage's error. Overlaying from the stage that is
   * executing gives planning its rejected-plan halt back after analysis returns.
   */
  public static void overlayHaltEvidenceForStage(
      ProductPipelineRunDocument doc, String stageId, Map<String, Object> attributes) {
    if (doc == null || stageId == null || stageId.isBlank() || attributes == null) {
      return;
    }
    unresolvedHaltEvidence(doc, stageId).forEach((key, value) -> {
      if (!RECOVERY_PREPARING_OWNER_ATTR.equals(key) && !ACCEPTED_SOURCE_BRIEF_REF_ATTR.equals(key)) {
        attributes.put(key, value);
      }
    });
  }

  private static Map<String, Object> unresolvedHaltEvidence(ProductPipelineRunDocument doc, String stageId) {
    Map<String, Object> pending = Map.of();
    for (StageAttempt attempt : doc.attempts()) {
      if (!stageId.equals(attempt.stageId())) { continue; }
      if (attempt.outcome() == StageStatus.SUCCEEDED) {
        pending = Map.of();
      } else {
        var evidence = readHaltEvidence(attempt.failureEvidence());
        if (!evidence.isEmpty()) { pending = evidence; }
      }
    }
    return pending;
  }

  /** Retires an observing stage's active error only after that stage succeeds or its output is approved. */
  public static void settleActiveRepair(String stageId, Map<String, Object> attributes) {
    if (attributes != null && stageId.equals(attributes.get(STAGE_ERROR_FAILED_STAGE_ATTR))) {
      clearActiveRepair(attributes);
    }
  }

  /** Retires a discovery defect after native validation reaches the normal ready-draft gate. */
  public static boolean settleReadyDiscoveryRepair(
      String stageId, StageOutcome outcome, Map<String, Object> attributes) {
    if (attributes == null || !stageId.equals(attributes.get(STAGE_ERROR_FAILED_STAGE_ATTR))
        || outcome.outcomeClass() != StageOutcomeClass.NEEDS_INPUT || outcome.recoveryCause() != null
        || !PipelineGates.REQUIREMENT_DRAFT_READY.equals(PipelineGates.gateOf(outcome.message()).orElse(""))) {
      return false;
    }
    settleActiveRepair(stageId, attributes);
    Object preparing = attributes.get(RECOVERY_PREPARING_OWNER_ATTR);
    if (preparing == null || preparing instanceof String owner && (owner.isBlank() || stageId.equals(owner))) {
      for (String key : List.of(RECOVERY_PREPARING_OWNER_ATTR, ACCEPTED_SOURCE_BRIEF_REF_ATTR,
          "mappingRepairAutomatic", "mappingRepairPaths", "requirementCorrectionDraft", "missingMappingTargets")) {
        attributes.remove(key);
      }
    }
    return true;
  }

  private static void clearActiveRepair(Map<String, Object> attributes) {
    for (String key : List.of(
        STAGE_ERROR_CONTEXT_ATTR, STAGE_ERROR_OUTCOME_ATTR, STAGE_ERROR_FAILED_STAGE_ATTR,
        STAGE_ERROR_FAILED_SKILL_ATTR, STAGE_ERROR_FINDINGS_ATTR, STAGE_ERROR_CAUSE_CODE_ATTR,
        STAGE_ERROR_CAUSE_JSON_ATTR, STAGE_ERROR_REQUESTED_FACT_ATTR, DIAGNOSED_OWNER_STAGE_ATTR,
        RECOVERY_EVIDENCE_REF_ATTR, RECOVERY_INVOCATION_ATTR, RECOVERY_PENDING_ATTEMPT_ATTR,
        RECOVERY_DIAGNOSIS_ATTR, DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR,
        DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR, PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR,
        PRIOR_CANDIDATE_ATTR)) {
      attributes.remove(key);
    }
  }

  public static Map<String, Integer> consecutiveTechnicalRetries(
      String runId, List<StageAttempt> attempts, List<RunTransition> transitions) {
    Map<String, Long> cycleStarts = new LinkedHashMap<>();
    for (RunTransition transition : transitions) {
      if (transition.fromStatus() == RunStatus.WAITING_FOR_INPUT
          && transition.toStatus() == RunStatus.RUNNING) {
        cycleStarts.merge(transition.stageId(), transition.toRevision(), Math::max);
      }
    }
    Map<String, Integer> consecutiveFailures = new LinkedHashMap<>();
    for (StageAttempt attempt : attempts) {
      if (attempt.runRevision() < cycleStarts.getOrDefault(attempt.stageId(), 0L)) {
        continue;
      }
      String key = stageRetryKey(runId, attempt.stageId());
      if (attempt.outcome() == StageStatus.SUCCEEDED) {
        consecutiveFailures.remove(key);
      } else if (isTechnicalRetryFailure(attempt)) {
        consecutiveFailures.merge(key, 1, Integer::sum);
      }
    }
    return consecutiveFailures;
  }

  /** Tags a failed attempt that represents a terminal halt rather than a technical retry. */
  public static String nonTechnicalFailureEvidence(String evidence) {
    return NON_TECHNICAL_FAILURE_EVIDENCE_PREFIX + (evidence == null ? "" : evidence);
  }

  private static boolean isTechnicalRetryFailure(StageAttempt attempt) {
    if (attempt.outcome() != StageStatus.FAILED
        || attempt.failureEvidence() != null
            && attempt.failureEvidence().startsWith(NON_TECHNICAL_FAILURE_EVIDENCE_PREFIX)) {
      return false;
    }
    Object outcome = readHaltEvidence(attempt.failureEvidence()).get(STAGE_ERROR_OUTCOME_ATTR);
    return outcome == null || "".equals(outcome)
        || StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE.name().equals(outcome);
  }

  /** Encodes halt attributes in the failure-evidence field of the matching stage attempt. */
  public static String haltEvidence(Map<String, Object> attributes, String priorCandidate) {
    return haltEvidence(attributes, priorCandidate, null);
  }

  public static String haltEvidence(Map<String, Object> attributes, String priorCandidate, String currentReason) {
    Map<String, String> evidence = new LinkedHashMap<>();
    if (currentReason != null && !currentReason.isBlank()) {
      evidence.put(CURRENT_HALT_REASON_ATTR, currentReason);
    }
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_CONTEXT_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_OUTCOME_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_FAILED_STAGE_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_FAILED_SKILL_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_FINDINGS_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_CAUSE_CODE_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_CAUSE_JSON_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_REQUESTED_FACT_ATTR);
    copyHaltAttribute(attributes, evidence, DIAGNOSED_OWNER_STAGE_ATTR);
    copyHaltAttribute(attributes, evidence, RECOVERY_EVIDENCE_REF_ATTR);
    copyHaltAttribute(attributes, evidence, RECOVERY_INVOCATION_ATTR);
    copyHaltAttribute(attributes, evidence, RECOVERY_PENDING_ATTEMPT_ATTR);
    copyHaltAttribute(attributes, evidence, RECOVERY_DIAGNOSIS_ATTR);
    copyHaltAttribute(attributes, evidence, RECOVERY_PREPARING_OWNER_ATTR);
    copyHaltAttribute(attributes, evidence, ACCEPTED_SOURCE_BRIEF_REF_ATTR);
    copyHaltAttribute(attributes, evidence, "mappingRepairAutomatic");
    copyHaltAttribute(attributes, evidence, DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    copyHaltAttribute(attributes, evidence, DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    copyHaltAttribute(attributes, evidence, PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
    if (priorCandidate != null && !priorCandidate.isBlank()) {
      evidence.put(PRIOR_CANDIDATE_ATTR, priorCandidate);
    }
    try {
      return HALT_EVIDENCE_JSON.writeValueAsString(evidence);
    } catch (Exception e) {
      throw new IllegalStateException("cannot write halt evidence", e);
    }
  }

  public static String recoveryCauseJson(RecoveryCause cause) {
    try {
      var value = new LinkedHashMap<String, Object>(Map.of(
          "causeCode", cause.causeCode(), "findings", cause.findings(), "requestedFact", cause.requestedFact(),
          "noProgress", cause.noProgress()));
      value.put("captureFailure", cause.captureFailure());
      return HALT_EVIDENCE_JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot persist the recovery cause", e);
    }
  }

  private static void copyHaltAttribute(
      Map<String, Object> attributes, Map<String, String> evidence, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    evidence.put(key, value instanceof String text ? text : "");
  }

  private static void restoreHaltEvidence(
      ProductPipelineRunDocument doc, Map<String, Object> attributes) {
    clearActiveRepair(attributes);
    for (String key : List.of(RECOVERY_PREPARING_OWNER_ATTR, ACCEPTED_SOURCE_BRIEF_REF_ATTR)) {
      attributes.remove(key);
      doc.attempts().stream().map(StageAttempt::failureEvidence).filter(Objects::nonNull)
          .map(ProductPipelineRunSupport::readHaltEvidence).filter(evidence -> evidence.containsKey(key))
          .reduce((first, second) -> second).ifPresent(evidence -> attributes.put(key, evidence.get(key)));
    }
    overlayHaltEvidenceForStage(doc, doc.run().currentStageId(), attributes);
  }

  public static Map<String, Object> readHaltEvidence(String evidence) {
    try {
      Map<String, String> values =
          HALT_EVIDENCE_JSON.readValue(evidence, new TypeReference<Map<String, String>>() {});
      if (!values.containsKey(STAGE_ERROR_CONTEXT_ATTR)) {
        return Map.of();
      }
      return new LinkedHashMap<>(values);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private void verifyCompilerPin(RunManifest manifest) {
    if (manifest == null || manifest.compilerRunPin() == null) {
      return;
    }
    if (compilerRunPinResolver == null) {
      throw new IllegalStateException(
          "compilerRunPinResolver is required to verify pinned compiler package for run "
              + manifest.runId());
    }
    compilerRunPinResolver.verifyAvailable(manifest);
  }

  private ApprovalRecordV2 semanticApprovalRecord(
      String runId,
      Reference target,
      List<Reference> approvedCandidates,
      ApprovalPolicy approvalPolicy) {
    String subjectKind = null;
    String subjectSchemaVersion = null;
    String subjectRevisionId = null;
    String subjectSha256 = null;
    String compilerContractVersion = null;
    String compilerContractSha256 = null;
    Reference semanticRef = semanticSubject(target, approvedCandidates);
    if (semanticRef != null) {
      ChainSemanticRevision revision =
          artifactStore
              .get(runId, semanticRef)
              .map(stored -> artifactStore.payload(stored, ChainSemanticRevision.class))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Required artifact CHAIN_SEMANTIC_REVISION is missing for design-execution"));
      CompilerContract contract =
          new ClasspathCompilerContractRepository().require(CompilerContract.V1);
      ApprovalRecordV2 semantic = stageExecutor.approveCandidate(revision, contract);
      subjectKind = semantic.subjectArtifactKind();
      subjectSchemaVersion = semantic.subjectSchemaVersion();
      subjectRevisionId = semantic.subjectRevisionId();
      subjectSha256 = semantic.subjectSha256();
      compilerContractVersion = semantic.compilerContractVersion();
      compilerContractSha256 = semantic.compilerContractSha256();
      persistCombinedSemanticPin(runId, revision, contract);
    }
    return new ApprovalRecordV2(
        target,
        target.contentHash(),
        approvedCandidates,
        "user",
        null,
        clock.instant(),
        approvalPolicy.bindingResolutionPolicy(),
        approvalPolicy.bindingResolutionPolicyHash(),
        subjectKind,
        subjectSchemaVersion,
        subjectRevisionId,
        subjectSha256,
        compilerContractVersion,
        compilerContractSha256);
  }

  /**
   * The semantic revision this approval pins: the gate's own subject when the gate approves it,
   * otherwise the one inside the approved candidate set. {@code design-execution} verifies the
   * revision digest against the approval, so the pin has to survive approving the plan instead.
   */
  private static Reference semanticSubject(Reference target, List<Reference> approvedCandidates) {
    if (target != null && target.kind() == Kind.CHAIN_SEMANTIC_REVISION) {
      return target;
    }
    if (approvedCandidates == null) {
      return null;
    }
    return approvedCandidates.stream()
        .filter(ref -> ref != null && ref.kind() == Kind.CHAIN_SEMANTIC_REVISION)
        .findFirst()
        .orElse(null);
  }

  /**
   * Pins the semantic revision as soon as the stage that produced it completes. Planning reads the
   * pin before any approval happens, so waiting for the approval to write it would leave the run
   * with nothing to plan against.
   */
  private void pinSemanticRevisionOf(String runId, String stageId) {
    ProfileStage stage = stageOf(runId, stageId);
    if (stage == null
        || stage.produces().stream()
            .noneMatch(ref -> ref != null && ref.matches(Kind.CHAIN_SEMANTIC_REVISION))) {
      return;
    }
    StageSnapshot snapshot = requireRun(runId).run().stages().stream()
        .filter(value -> stageId.equals(value.stageId())).findFirst().orElseThrow();
    List<Reference> selected = preparingReplacement(profilesByRun.get(runId), attributesByRun.get(runId), stageId)
        ? snapshot.candidateReferences() : snapshot.outputRefs();
    List<Reference> revisions = selected.stream().filter(ref -> ref.kind() == Kind.CHAIN_SEMANTIC_REVISION)
        .distinct().toList();
    if (revisions.size() != 1) { throw new IllegalStateException("Completed stage must select one semantic revision"); }
    Revision stored = artifactStore.get(runId, revisions.getFirst()).orElseThrow();
    persistCombinedSemanticPin(runId, artifactStore.payload(stored, ChainSemanticRevision.class),
        new ClasspathCompilerContractRepository().require(CompilerContract.V1));
  }

  /** Keeps accepted outputs effective until the replacement reaches its approval boundary. */
  public static boolean preparingReplacement(ProductPipelineProfile profile, Map<String, Object> attributes,
      String stageId) {
    if (profile == null || attributes == null || !(attributes.get(RECOVERY_PREPARING_OWNER_ATTR) instanceof String owner)
        || owner.isBlank()) { return false; }
    boolean active = false;
    for (ProfileStage stage : profile.stages()) {
      if (owner.equals(stage.stageId())) { active = true; }
      if (active && stageId.equals(stage.stageId())) { return true; }
      if (active && stage.approval() != null) { return false; }
    }
    return false;
  }

  private static String replacementApprovalStage(ProductPipelineProfile profile, Map<String, Object> attributes) {
    if (profile == null || attributes == null || !(attributes.get(RECOVERY_PREPARING_OWNER_ATTR) instanceof String owner)
        || owner.isBlank()) { return ""; }
    boolean active = false;
    for (ProfileStage stage : profile.stages()) {
      if (owner.equals(stage.stageId())) { active = true; }
      if (active && stage.approval() != null) { return stage.stageId(); }
    }
    return "";
  }

  private static List<StageSnapshot> promoteReplacement(ProductPipelineProfile profile, Map<String, Object> attributes,
      List<StageSnapshot> stages, String acceptedStageId) {
    Set<String> invalidate = stageIdsAfter(profile, acceptedStageId);
    return stages.stream().map(stage -> {
      if (invalidate.contains(stage.stageId())) {
        return new StageSnapshot(stage.stageId(), StageStatus.PENDING, List.of(), null);
      }
      if (preparingReplacement(profile, attributes, stage.stageId()) && !acceptedStageId.equals(stage.stageId())
          && stage.status() == StageStatus.SUCCEEDED && !stage.candidateReferences().isEmpty()) {
        return new StageSnapshot(stage.stageId(), stage.status(), stage.candidateReferences(), null,
            stage.candidateReferences(), stage.approvableReference(), stage.candidateRevision());
      }
      if (acceptedStageId.equals(stage.stageId()) && stage.approvedArtifactId() == null
          && !stage.candidateReferences().isEmpty()) {
        return new StageSnapshot(stage.stageId(), stage.status(), stage.candidateReferences(), null,
            stage.candidateReferences(), stage.approvableReference(), stage.candidateRevision());
      }
      return stage;
    }).toList();
  }

  private ProfileStage stageOf(String runId, String stageId) {
    ProductPipelineProfile profile = profilesByRun.get(runId);
    if (profile == null || stageId == null) {
      return null;
    }
    return profile.stages().stream()
        .filter(stage -> stageId.equals(stage.stageId()))
        .findFirst()
        .orElse(null);
  }

  private void persistCombinedSemanticPin(
      String runId, ChainSemanticRevision revision, CompilerContract contract) {
    RunManifest manifest = manifestsByRun.get(runId);
    if (manifest == null || manifest.compilerRunPin() == null) {
      return;
    }
    CompilerRunPin semanticPin =
        compilerRunPinResolver == null
            ? null
            : compilerRunPinResolver.resolve(runId, revision, contract);
    if (semanticPin == null) {
      semanticPin =
          new CompilerRunPin(
              null,
              null,
              null,
              0,
              null,
              null,
              null,
              List.of(),
              Map.of(),
              Map.of(),
              List.of(),
              Kind.CHAIN_SEMANTIC_REVISION.name(),
              revision.schemaVersion(),
              revision.revisionId(),
              CanonicalPayloadHash.sha256Hex(revision),
              contract.contractVersion(),
              contract.sha256());
    }
    CompilerRunPin combined = manifest.compilerRunPin().withSemanticSubject(semanticPin);
    RunManifest updated =
        new RunManifest(
            manifest.runId(),
            manifest.parentRunId(),
            manifest.sourceReferences(),
            manifest.runtimeSelection(),
            manifest.profileId(),
            manifest.profileVersion(),
            manifest.profileDigest(),
            manifest.referenceBaselineId(),
            manifest.referenceBaselineDigest(),
            manifest.dependencyClosure(),
            manifest.dependencyClosureDigest(),
            manifest.knowledgePackage(),
            manifest.languageVersion(),
            manifest.artifactSchemaVersions(),
            combined,
            manifest.responseLocale());
    artifactStore.append(
        new AppendCommand(
            runId,
            Kind.RUN_MANIFEST,
            "1",
            "product-pipeline-runtime",
            "1",
            updated,
            List.of(),
            null,
            provenance(runId, "design-input", "design-input")));
    manifestsByRun.put(runId, updated);
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

  private static String stageRetryKey(String runId, String stageId) {
    return runId + ":" + stageId;
  }

  private static <K, V> ConcurrentMap<K, V> idleCache(Duration idleTimeout) {
    return Caffeine.newBuilder()
        .expireAfterAccess(Objects.requireNonNull(idleTimeout, "cacheIdleTimeout"))
        .<K, V>build()
        .asMap();
  }

  /** Last durable WAITING_FOR_INPUT transition reason, used when re-emitting a wait on resume. */
  private static String latestWaitingForInputPrompt(ProductPipelineRunDocument doc) {
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((a, b) -> b)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElse("");
  }

  private void clearStageLocalReplyAttributes(String runId) {
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return;
    }
    attributes.remove("userText");
    attributes.remove(ADMITTED_INPUT_GATE_ATTR);
    attributes.remove(ADMITTED_INPUT_REVISION_ATTR);
    attributes.remove(ADMITTED_CLARIFICATION_OFFER_ATTR);
  }

  private String approvalPromptFor(String runId, String stageId) {
    if (isBriefRepairApproval(runId, stageId)) {
      return withAuthorNote(attributesByRun.get(runId), BRIEF_REPAIR_APPROVAL_PROMPT);
    }
    return org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget.withRun(runId,
        () -> approvalPrompts.stageApprovalPrompt(stageId, responseLocaleOf(runId), languageReferenceFor(runId)));
  }

  /** Locale pinned for this run's replies; English when the manifest is not cached yet. */
  private String responseLocaleOf(String runId) {
    RunManifest manifest = manifestsByRun.get(runId);
    return manifest == null ? "en" : manifest.responseLocale();
  }

  private void rememberAuthorNote(String runId, String note) {
    if (note == null || note.isBlank()) {
      return;
    }
    attributesByRun
        .computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
        .put(RECOVERY_AUTHOR_NOTE_ATTR, note);
  }

  /**
   * Prefixes an approval prompt with the reason the run came back here. Without it the author is
   * asked to approve a rebuilt brief with no explanation of what was wrong.
   */
  public static String withAuthorNote(Map<String, Object> attributes, String prompt) {
    Object note = attributes == null ? null : attributes.get(RECOVERY_AUTHOR_NOTE_ATTR);
    if (note instanceof String text && !text.isBlank()) {
      return text.trim() + "\n\n" + prompt;
    }
    return prompt;
  }

  private boolean isBriefRepairApproval(String runId, String stageId) {
    if (stageId == null || !stageId.contains("analysis")) {
      return false;
    }
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes == null) {
      return false;
    }
    Object error = attributes.get(STAGE_ERROR_CONTEXT_ATTR);
    return error instanceof String text && !text.isBlank();
  }

  private void recordSupersededBriefOnRepairApproval(String runId, Reference approvedTarget) {
    if (!isBriefRepairApproval(runId, requireRun(runId).run().currentStageId())) {
      return;
    }
    if (approvedTarget == null || approvedTarget.kind() != Kind.REQUIREMENT_BRIEF) {
      return;
    }
    String priorBriefHash = priorBriefHashForSupersession(runId, approvedTarget);
    if (priorBriefHash == null || priorBriefHash.isBlank()) {
      return;
    }
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>());
    attributes.put(SUPERSEDED_BRIEF_CONTENT_HASH_ATTR, priorBriefHash);
    List<String> supersededArtifactHashes = collectSupersededDerivedArtifactHashes(runId);
    if (!supersededArtifactHashes.isEmpty()) {
      attributes.put(SUPERSEDED_ARTIFACT_HASHES_ATTR, supersededArtifactHashes);
    }
  }

  private List<String> collectSupersededDerivedArtifactHashes(String runId) {
    List<String> hashes = new ArrayList<>();
    for (Kind kind : SUPERSEDED_DERIVED_ARTIFACT_KINDS) {
      artifactStore
          .latest(runId, kind)
          .map(Revision::contentHash)
          .filter(hash -> hash != null && !hash.isBlank())
          .ifPresent(hashes::add);
    }
    return List.copyOf(hashes);
  }

  private String priorBriefHashForSupersession(String runId, Reference approvedTarget) {
    Optional<String> effective = requireRun(runId).run().stages().stream()
        .flatMap(stage -> stage.outputRefs().stream()).filter(ref -> ref.kind() == Kind.REQUIREMENT_BRIEF)
        .map(Reference::contentHash).filter(hash -> !approvedTarget.contentHash().equals(hash)).findFirst();
    if (effective.isPresent()) { return effective.get(); }
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes != null) {
      Object prior = attributes.get(PRIOR_CANDIDATE_ATTR);
      if (prior instanceof String text && !text.isBlank()) {
        return text.trim();
      }
    }
    return null;
  }

  private String languageReferenceFor(String runId) {
    Reference selected = selectedRequirementBriefReference(requireRun(runId));
    var currentBrief = selected == null ? Optional.<Revision>empty() : artifactStore.get(runId, selected);
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

  /**
   * Content hash of the implementation plan approved at the implementation gate, when present.
   */
  public Optional<String> approvedPlanContentHash(String runId) {
    Objects.requireNonNull(runId, "runId");
    return latestApprovalRecordV2Optional(runId).map(ApprovalRecordV2::targetContentHash);
  }

  /**
   * Latest catalog snapshot after materialization, when present. Scripted tests may store a Map
   * stub; those payloads are ignored rather than failing deserialization.
   */
  public Optional<ChainCatalogFacts> latestCatalogChainSnapshot(String runId) {
    Objects.requireNonNull(runId, "runId");
    Optional<Revision> revision = artifactStore.latest(runId, Kind.CATALOG_CHAIN_SNAPSHOT);
    if (revision.isEmpty()) {
      return Optional.empty();
    }
    try {
      ChainCatalogFacts facts = artifactStore.payload(revision.get(), ChainCatalogFacts.class);
      if (facts == null || facts.chainId() == null || facts.chainId().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(facts);
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private ApprovalRecordV2 latestApprovalRecordV2(String runId) {
    return latestApprovalRecordV2Optional(runId)
        .orElseThrow(
            () ->
                new StaleApprovalException(
                    "schema-v2 approval record is required for implement"));
  }

  private Optional<ApprovalRecordV2> latestApprovalRecordV2Optional(String runId) {
    return artifactStore.history(runId, Kind.APPROVAL_RECORD).stream()
        .filter(item -> "2".equals(item.schemaVersion()))
        .reduce((first, second) -> second)
        .map(revision -> artifactStore.payload(revision, ApprovalRecordV2.class));
  }

  /**
   * Surfaces the human-readable plan before the approval wait so chat shows the candidate instead
   * of only a stage-id banner.
   *
   * <p>No CTA here: the decision card carries the approve / create actions, and telling the reader
   * to reply with a word is the instruction the card replaces.
   */
  private void emitImplementationPlanForReview(
      String runId, Reference approvable, List<PipelineSignal> emitted) {
    if (approvable == null || approvable.kind() != Kind.IMPLEMENTATION_PLAN) {
      return;
    }
    Optional<Revision> revision = artifactStore.get(runId, approvable);
    if (revision.isEmpty()) {
      return;
    }
    try {
      ImplementationPlan plan = artifactStore.payload(revision.get(), ImplementationPlan.class);
      String planText = plan == null || plan.planText() == null ? "" : plan.planText().trim();
      // Stored planText may keep digests; chat omits "* hash:" metadata lines.
      String chatPlan = ImplementationPlanChatView.forChatReview(planText);
      if (chatPlan.isBlank()) {
        return;
      }
      emitted.add(new PipelineSignal.Message(chatPlan + "\n\n"));
    } catch (RuntimeException ex) {
      // Scripted tests may store a Map stub with no readable plan text; nothing to surface.
      LOG.debugf(ex, "No readable plan text for review (runId=%s)", runId);
    }
  }

  /**
   * Surfaces the requirement brief before the analysis approval wait so chat shows what the reader
   * is approving instead of only a stage CTA.
   */
  private void emitRequirementBriefForReview(
      String runId, Reference approvable, List<PipelineSignal> emitted) {
    if (approvable == null || approvable.kind() != Kind.REQUIREMENT_BRIEF) {
      return;
    }
    Optional<Revision> revision = artifactStore.get(runId, approvable);
    if (revision.isEmpty()) {
      return;
    }
    try {
      RequirementBrief brief = artifactStore.payload(revision.get(), RequirementBrief.class);
      String body = requirementBriefChatReview(brief);
      if (body.isBlank()) {
        return;
      }
      emitted.add(new PipelineSignal.Message(body + "\n\n"));
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Failed to surface REQUIREMENT_BRIEF for approval review (runId=%s)", runId);
    }
  }

  /** Compact markdown for the approval card's preceding narrative. */
  static String requirementBriefChatReview(RequirementBrief brief) {
    if (brief == null) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    String summary = brief.summary() == null ? "" : brief.summary().trim();
    String goal = brief.goal() == null ? "" : brief.goal().trim();
    if (!summary.isBlank()) {
      body.append(summary);
    }
    if (!goal.isBlank()) {
      if (!body.isEmpty()) {
        body.append("\n\n");
      }
      body.append("**Goal:** ").append(goal);
    }
    if (!brief.facts().isEmpty()) {
      if (!body.isEmpty()) {
        body.append("\n\n");
      }
      body.append("**Facts:**\n");
      for (var fact : brief.facts()) {
        if (fact == null || fact.text() == null || fact.text().isBlank()) {
          continue;
        }
        body.append("- ").append(fact.text().trim()).append('\n');
      }
    }
    return body.toString().strip();
  }

  /**
   * Surfaces IDS markdown (and optional storage download link) before the design-input approval
   * wait so chat shows the candidate instead of only an Agree CTA. Trailing blank lines separate
   * the Message from the following WaitingForApproval token (adjacent chat tokens, no separator).
   */
  /**
   * Shows the IDS document at whichever gate is open. It is a reader's view of the design, not the
   * subject of any gate, and an {@code IDS_BYPASS} on the run means the author asked not to see it.
   */
  private void emitIdsDocumentForReview(
      String runId, Reference approvable, List<PipelineSignal> emitted) {
    if (artifactStore.latest(runId, Kind.IDS_BYPASS).isPresent()) {
      return;
    }
    Optional<Revision> revision =
        approvable != null && approvable.kind() == Kind.IDS_DOCUMENT
            ? artifactStore.get(runId, approvable)
            : artifactStore.latest(runId, Kind.IDS_DOCUMENT);
    if (revision.isEmpty()) {
      return;
    }
    try {
      IdsDocument document = artifactStore.payload(revision.get(), IdsDocument.class);
      String markdown =
          document == null || document.markdown() == null ? "" : document.markdown().trim();
      if (markdown.isBlank()) {
        return;
      }
      // Blank line between body sections so the download link does not glue to the markdown.
      StringBuilder body = new StringBuilder(markdown);
      String downloadLink = idsDownloadMarkdownLink(markdown);
      if (downloadLink != null && !downloadLink.isBlank()) {
        body.append("\n\n").append(downloadLink);
      }
      body.append("\n\n");
      emitted.add(new PipelineSignal.Message(body.toString()));
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Failed to surface IDS_DOCUMENT for approval review (runId=%s)", runId);
    }
  }

  /**
   * Uploads IDS markdown for browser download. Returns a markdown link, or null when storage is
   * unavailable.
   */
  private String idsDownloadMarkdownLink(String markdown) {
    if (s3Service == null) {
      return null;
    }
    try {
      String objectKey = s3Service.putDesignIdsMarkdown(markdown);
      if (objectKey == null || objectKey.isBlank()) {
        return null;
      }
      // Link text is the product filename (language-neutral); CTA copy stays in ApprovalPrompts.
      return "[ids.md](/api/v1/storage/objects?key="
          + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
          + ")";
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Failed to upload IDS markdown for download");
      return null;
    }
  }

  private static RunStatus terminalStatus(ProductPipelineProfile profile) {
    return RunStatus.valueOf(profile.terminal().state());
  }

  private static boolean isTerminalRunStatus(RunStatus status) {
    return status == RunStatus.PLAN_APPROVED || status == RunStatus.CHAIN_MATERIALIZED;
  }
}
