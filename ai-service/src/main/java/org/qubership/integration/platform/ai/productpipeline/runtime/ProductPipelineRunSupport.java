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
import org.jboss.logging.Logger;
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
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidate;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet;
import org.qubership.integration.platform.ai.productpipeline.create.PauseQuestionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
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
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanChatView;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.storage.S3Service;

/**
 * Durable create-chain run journal used by Flow. Records commands, hydrates pins, and applies one
 * stage decision. It does not own stage order or resume a recursive advance loop.
 */
public final class ProductPipelineRunSupport {

  private static final Logger LOG = Logger.getLogger(ProductPipelineRunSupport.class);

  /**
   * Latest typed message recorded while the run was at a recoverable halt. The next diagnosis
   * turn reads this attribute from the stage context.
   */
  public static final String HALT_FOLLOW_UP_TEXT_ATTR = "haltFollowUpText";

  /** Diagnosed owner stage id from the last validation/domain/contract halt. */
  public static final String DIAGNOSED_OWNER_STAGE_ATTR = "diagnosedOwnerStageId";

  /** Raw failure evidence for the next attempt of the current unapproved stage. */
  public static final String STAGE_ERROR_CONTEXT_ATTR = "stageErrorContext";

  /** Outcome class stored with {@link #STAGE_ERROR_CONTEXT_ATTR}. */
  public static final String STAGE_ERROR_OUTCOME_ATTR = "stageErrorOutcomeClass";

  /** Stage id that produced the halt; kept when a causal reopen moves currentStageId. */
  public static final String STAGE_ERROR_FAILED_STAGE_ATTR = "stageErrorFailedStageId";

  /** Formatted validation findings from the halt, when present. */
  public static final String STAGE_ERROR_FINDINGS_ATTR = "stageErrorFindings";

  /** Content hash of the durable recovery evidence for the current validation halt. */
  public static final String RECOVERY_EVIDENCE_REF_ATTR = "recoveryEvidenceRef";

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
          Kind.GRAPH_PATCH_ARTIFACT,
          Kind.GRAPH_ASSEMBLY_RESULT,
          Kind.COMPILER_VALIDATION_BUNDLE,
          Kind.DESIGN_EXECUTION_PLAN,
          Kind.ORDERED_GRAPH_PATCHES);

  /** Typed {@code RecoveryCauseCode} name stored with the halt. */
  public static final String STAGE_ERROR_CAUSE_CODE_ATTR = "stageErrorCauseCode";

  /** Catalog-resolution requested fact, when the cause carries one. */
  public static final String STAGE_ERROR_REQUESTED_FACT_ATTR = "stageErrorRequestedFact";

  /**
   * Content hash of the owner's last approved candidate. Set when that stage is re-entered after a
   * causal reopen.
   */
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
          recoveryLedger);
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
      RecoveryAttemptLedger recoveryLedger) {
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
            this.recoveryLedger);
  }

  /** Single-stage execution seam used by Flow. */
  public StageExecutor stageExecutor() {
    return stageExecutor;
  }

  /**
   * Restores the persisted technical-retry budget before Flow re-executes a stage. After restart,
   * this count is the source of truth; the in-memory map is only a process-local cache.
   */
  public void restoreTechnicalRetryCount(String runId, String stageId, int used) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(stageId, "stageId");
    String key = stageRetryKey(runId, stageId);
    if (used <= 0) {
      technicalRetriesByStage.remove(key);
    } else {
      technicalRetriesByStage.put(key, used);
    }
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
  public Multi<PipelineSignal> recordInput(AcceptInputCommand command) {
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
              if (isHaltFollowUp(doc, command.text())) {
                return recordHaltFollowUp(doc, command);
              }
              if (isOwnerChoicePick(doc, command.text())) {
                return recordOwnerChoice(doc, command);
              }
              if (isTypedAtApprovalCard(doc, command.text())) {
                return answerApprovalQuestionOrRefine(doc, command);
              }
              return acceptTypedInput(doc, command);
            });
  }

  /**
   * Applies a typed message that reaches the stage: it becomes the stage's user text and the run
   * goes back to RUNNING, which re-executes the stage and, at an approval card, replaces the
   * candidate with a revised one.
   */
  private Multi<PipelineSignal> acceptTypedInput(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return Multi.createFrom()
        .deferred(
            () -> {
              boolean retryClick = PipelineGates.RETRY_ACTION.equals(command.text());
              boolean reviseClick = PipelineGates.REVISE_ACTION.equals(command.text());
              boolean haltCardClick = retryClick || reviseClick;
              if (retryClick) {
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
              if (retryClick) {
                reason = recordAttempt(doc, command);
              } else if (isClarificationWait(doc)) {
                // The clarification card is the ask after repair budget. Retry the stage with the
                // author's text; do not escalate to owner-stage buttons when repairs are spent.
                if (diagnoseAttemptRefusal(doc, command) == null) {
                  reason = recordAttempt(doc, command);
                } else {
                  reason = "accepted clarification";
                }
              }
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
   * Typed message at a recoverable halt: same run, original requirements unchanged, Retry still
   * open. At {@link PipelineGates#STAGE_RETRY}, persist the text and re-emit the same halt card;
   * Retry remains the only action that reruns the stage. When the text names one stage in the
   * closed candidate set, that owner is used and the run follows the same Revise path (causal
   * reopen counts against the run budget). Ambiguous names become an owner-choice card. A named
   * stage outside the set stays halted and lists the allowed stage ids. A bare go-back reopens
   * the diagnosed owner. Whatever none of those branches claims goes to {@link
   * #answerQuestionOrStayWaiting}, which answers a question and leaves an instruction waiting. The
   * next diagnosis turn reads {@link #HALT_FOLLOW_UP_TEXT_ATTR}.
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
    String gate =
        PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
    if (PipelineGates.STAGE_RETRY.equals(gate)
        || PipelineGates.isContextualRecoveryGate(gate)) {
      return reemitHaltCard(doc);
    }
    List<OwnerCandidate> closed = haltOwnerCandidates(doc);
    List<String> named = OwnerCandidateSet.namedStages(command.text(), closed);
    if (named.size() == 1) {
      attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, named.get(0));
      return applyDiagnosedOwner(doc, command);
    }
    if (named.size() > 1) {
      attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, "");
      String body = PipelineGates.strip(latestWaitingForInputPrompt(doc));
      String prompt = PipelineGates.tagOwnerChoice(body, named);
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
              if (asked.isNotAQuestion()) {
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
   * Refuses a halt command by advancing to an escalated wait that names {@code guard}. Re-committing
   * the previous wait is not a legal answer. A second refusal of the same already-escalated guard
   * emits the guard sentence as a message and keeps the terminal card.
   */
  private Multi<PipelineSignal> refuseWithGuard(
      ProductPipelineRunDocument doc, AcceptInputCommand command, HaltRecoveryGuard guard) {
    HaltRecoveryGuard named = guard == null ? HaltRecoveryGuard.MAX_SEMANTIC_REPAIRS : guard;
    String previous = latestWaitingForInputPrompt(doc);
    if (PipelineGates.STAGE_ESCALATED.equals(PipelineGates.gateOf(previous).orElse(""))
        && named.name().equals(PipelineGates.guardOf(previous).orElse(""))) {
      return Multi.createFrom()
          .item((PipelineSignal) new PipelineSignal.Message(named.cardSentence()))
          .onCompletion()
          .switchTo(() -> reemitHaltCard(doc));
    }
    String evidence =
        stringAttribute(command.runId(), STAGE_ERROR_CONTEXT_ATTR)
            .orElseGet(() -> PipelineGates.strip(previous));
    List<OwnerCandidate> closed = haltOwnerCandidates(doc);
    String allowed = String.join(", ", OwnerCandidateSet.stageIds(closed));
    SemanticRecoveryState.RemainingAttempts remaining = remainingOf(doc, command);
    String body =
        named.cardSentence()
            + (allowed.isBlank() ? "" : " Allowed stages: " + allowed + ".")
            + (evidence.isBlank() ? "" : " " + evidence)
            + " (runId="
            + command.runId()
            + ")"
            + HaltRecoveryGuard.remainingLine(remaining);
    List<String> working = workingEscalatedActions(doc, command, closed);
    boolean drop = working.contains(PipelineGates.DROP_ELEMENT_ACTION);
    List<String> stages = new ArrayList<>();
    for (String action : working) {
      if (!PipelineGates.DROP_ELEMENT_ACTION.equals(action)
          && !PipelineGates.STOP_WITH_REPORT_ACTION.equals(action)) {
        stages.add(action);
      }
    }
    String haltIdentity = PipelineGates.haltIdentityOf(previous).orElse("");
    String prompt =
        PipelineGates.tagGuard(
            PipelineGates.tagEscalated(body, stages, drop, haltIdentity), named.name());
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

  private List<String> workingEscalatedActions(
      ProductPipelineRunDocument doc, AcceptInputCommand command, List<OwnerCandidate> closed) {
    List<String> actions = new ArrayList<>();
    if (bareGoBackAtOwnerChoice(doc, command)) {
      actions.addAll(PipelineGates.ownerCandidatesOf(latestWaitingForInputPrompt(doc)));
    } else if (namedStageListing(command)) {
      actions.addAll(OwnerCandidateSet.stageIds(closed));
    } else {
      for (String stageId : OwnerCandidateSet.stageIds(closed)) {
        if (isCurrentUnapprovedOwner(doc, stageId)
            || shouldCausalReopen(
                doc, stageId, command.origin(), RecoveryAttemptLedger.ReopenInitiator.AUTHOR)) {
          actions.add(stageId);
        }
      }
    }
    ProfileStage stage = currentStageOrNull(doc);
    if (stage != null && stage.skip() != null) {
      actions.add(PipelineGates.DROP_ELEMENT_ACTION);
    }
    actions.add(PipelineGates.STOP_WITH_REPORT_ACTION);
    return actions;
  }

  private static boolean namedStageListing(AcceptInputCommand command) {
    return OwnerCandidateSet.requestsNamedStage(command.text());
  }

  private static boolean bareGoBackAtOwnerChoice(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    return OwnerCandidateSet.isBareGoBack(command.text())
        && PipelineGates.OWNER_CHOICE.equals(
            PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""));
  }

  private ProfileStage currentStageOrNull(ProductPipelineRunDocument doc) {
    ProductPipelineProfile profile = profilesByRun.get(doc.run().runId());
    if (profile == null || profile.stages() == null) {
      return null;
    }
    return profile.stages().stream()
        .filter(stage -> stage.stageId().equals(doc.run().currentStageId()))
        .findFirst()
        .orElse(null);
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
    if (recoveryLedger.ownerAlreadyReopened(doc.transitions(), key, legacy)) {
      return HaltRecoveryGuard.OWNER_ALREADY_REOPENED;
    }
    if (!recoveryLedger.mayReopen(doc.transitions(), key, origin, initiator, legacy)) {
      return HaltRecoveryGuard.MAX_CAUSAL_REOPENS;
    }
    return HaltRecoveryGuard.MAX_CAUSAL_REOPENS;
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
    if (PipelineGates.RECOVERY_REGENERATE_EXECUTION.equals(
        PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""))) {
      artifact =
          artifactStore
              .latest(doc.run().runId(), Kind.REQUIREMENT_BRIEF)
              .map(revision -> revision.reference().contentHash())
              .orElse(artifact);
    }
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
    if (!isEarlierApprovedOwner(doc, owner)) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    RecoveryCause cause = currentRecoveryCause(doc.run().runId());
    String artifact = RecoveryAttemptLedger.inputArtifactIdentity(doc, owner);
    RecoveryAttemptKey key = recoveryLedger.key(owner, cause, artifact, doc.transitions());
    return recoveryLedger.mayReopen(
        doc.transitions(),
        key,
        origin,
        initiator,
        causalReopenFailureSignature(doc.run().runId()));
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
    if (latestCatalogChainSnapshot(runId).isPresent()) {
      return true;
    }
    return artifactStore.latest(runId, Kind.MATERIALIZATION_RESULT).isPresent();
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
    Reference prior = ownerSnapshot == null ? null : resolveReopenApprovable(ownerSnapshot);
    if (profile == null || ownerSnapshot == null || prior == null) {
      return command == null
          ? Multi.createFrom().empty()
          : refuseWithGuard(doc, command, HaltRecoveryGuard.MISSING_PROFILE_OR_PRIOR_CANDIDATE);
    }
    attributesByRun
        .computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
        .put(PRIOR_CANDIDATE_ATTR, prior.contentHash());
    Set<String> afterOwner = stageIdsAfter(profile, owner);
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (owner.equals(snapshot.stageId())) {
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
        doc,
        owner,
        updated,
        reopenReason(doc, owner, command),
        haltEvidence(attributesByRun.get(runId), prior.contentHash()),
        commandId,
        commandPayloadHash);
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

  private Multi<PipelineSignal> stopWithReport(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
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

  private static boolean isHaltFollowUp(ProductPipelineRunDocument doc, String text) {
    if (PipelineGates.isHaltCardAction(text) || doc.run().status() != RunStatus.WAITING_FOR_INPUT) {
      return false;
    }
    if (isOwnerChoicePick(doc, text)) {
      return false;
    }
    String gate = PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse("");
    if (PipelineGates.STAGE_CLARIFICATION.equals(gate)) {
      return OwnerCandidateSet.requestsNamedStage(text);
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
              recordSupersededBriefOnRepairApproval(command.runId(), target);
              List<Reference> approvedCandidates =
                  approvedCandidates(stage.outputRefs(), stageProfile.approval());
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
              List<Reference> approvedOutputs = new ArrayList<>(stage.outputRefs());
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
              ImplementationGatePolicy gate = profile.implementationGate();
              if (gate != null && currentStageId.equals(gate.afterStageId())) {
                commitStatus(
                    doc,
                    RunStatus.WAITING_FOR_IMPLEMENT,
                    StageStatus.SUCCEEDED,
                    updated,
                    "waiting for implement",
                    null,
                    command.commandId(),
                    command.commandPayloadHash());
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
                    null,
                    command.commandId(),
                    command.commandPayloadHash());
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
                  "approved",
                  null,
                  command.commandId(),
                  command.commandPayloadHash());
              ProductPipelineRunDocument after = requireRun(command.runId());
              commitMove(after, nextStageId, markStageRunning(after, nextStageId), "advance after approval");
              return Multi.createFrom().empty();
            });
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
      return causalReopenOwner(doc, reopen.producerStageId(), null, null)
          .onCompletion()
          .switchTo(() -> Multi.createFrom().iterable(signals));
    }
    return Multi.createFrom().iterable(signals);
  }

  private Multi<PipelineSignal> applyContinue(
      String runId, StageDecision.Continue decision, List<PipelineSignal> signals) {
    ProductPipelineProfile profile = profilesByRun.get(runId);
    pinSemanticRevisionOf(runId, decision.stageId());
    ProductPipelineRunDocument doc = requireRun(runId);
    String next = nextStageId(profile, decision.stageId());
    commitMove(doc, next, markStageRunning(doc, next), "advance after success");
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
                commandId,
                commandPayloadHash)));
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
                commandPayloadHash)));
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
    List<UserInput> stageInputs =
        artifactStore.history(runId, Kind.USER_INPUT).stream()
            .map(revision -> artifactStore.payload(revision, UserInput.class))
            .filter(input -> input.targetStageId().equals(doc.run().currentStageId()))
            .toList();
    List<UserInput> requirementInputs =
        stageInputs.stream().filter(input -> !isHaltFollowUpInput(input)).toList();
    List<UserInput> followUps =
        stageInputs.stream().filter(ProductPipelineRunSupport::isHaltFollowUpInput).toList();
    if (!requirementInputs.isEmpty()) {
      attributes.put("userText", requirementInputs.get(requirementInputs.size() - 1).text());
      attributes.put("discoveryUserText", requirementInputs.get(0).text());
    }
    if (!followUps.isEmpty()) {
      attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, followUps.get(followUps.size() - 1).text());
    }
    // Rehydrate only this run's counters. Clearing the whole map would drop retries for
    // other in-memory runs that share the same runtime bean.
    technicalRetriesByStage.keySet().removeIf(key -> key.startsWith(runId + ":"));
    technicalRetriesByStage.putAll(consecutiveTechnicalRetries(runId, doc.attempts()));
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
    doc.attempts().stream()
        .filter(attempt -> stageId.equals(attempt.stageId()))
        .map(StageAttempt::failureEvidence)
        .filter(Objects::nonNull)
        .map(ProductPipelineRunSupport::readHaltEvidence)
        .filter(evidence -> !evidence.isEmpty())
        .reduce((first, second) -> second)
        .ifPresent(attributes::putAll);
  }

  static Map<String, Integer> consecutiveTechnicalRetries(
      String runId, List<StageAttempt> attempts) {
    Map<String, Integer> consecutiveFailures = new LinkedHashMap<>();
    for (StageAttempt attempt : attempts) {
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
    return attempt.outcome() == StageStatus.FAILED
        && (attempt.failureEvidence() == null
            || !attempt.failureEvidence().startsWith(NON_TECHNICAL_FAILURE_EVIDENCE_PREFIX));
  }

  /** Encodes halt attributes in the failure-evidence field of the matching stage attempt. */
  public static String haltEvidence(Map<String, Object> attributes, String priorCandidate) {
    Map<String, String> evidence = new LinkedHashMap<>();
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_CONTEXT_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_OUTCOME_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_FAILED_STAGE_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_FINDINGS_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_CAUSE_CODE_ATTR);
    copyHaltAttribute(attributes, evidence, STAGE_ERROR_REQUESTED_FACT_ATTR);
    copyHaltAttribute(attributes, evidence, DIAGNOSED_OWNER_STAGE_ATTR);
    if (priorCandidate != null && !priorCandidate.isBlank()) {
      evidence.put(PRIOR_CANDIDATE_ATTR, priorCandidate);
    }
    try {
      return HALT_EVIDENCE_JSON.writeValueAsString(evidence);
    } catch (Exception e) {
      throw new IllegalStateException("cannot write halt evidence", e);
    }
  }

  private static void copyHaltAttribute(
      Map<String, Object> attributes, Map<String, String> evidence, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    evidence.put(key, value instanceof String text ? text : "");
  }

  private static void restoreHaltEvidence(
      ProductPipelineRunDocument doc, Map<String, Object> attributes) {
    for (String key : List.of(
        STAGE_ERROR_CONTEXT_ATTR,
        STAGE_ERROR_OUTCOME_ATTR,
        STAGE_ERROR_FAILED_STAGE_ATTR,
        STAGE_ERROR_FINDINGS_ATTR,
        STAGE_ERROR_CAUSE_CODE_ATTR,
        STAGE_ERROR_REQUESTED_FACT_ATTR,
        DIAGNOSED_OWNER_STAGE_ATTR,
        PRIOR_CANDIDATE_ATTR)) {
      attributes.remove(key);
    }
    doc.attempts().stream()
        .filter(attempt -> doc.run().currentStageId().equals(attempt.stageId()))
        .map(StageAttempt::failureEvidence)
        .filter(Objects::nonNull)
        .map(ProductPipelineRunSupport::readHaltEvidence)
        .filter(evidence -> !evidence.isEmpty())
        .reduce((first, second) -> second)
        .ifPresent(attributes::putAll);
  }

  private static Map<String, Object> readHaltEvidence(String evidence) {
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
    artifactStore
        .latest(runId, Kind.CHAIN_SEMANTIC_REVISION)
        .map(stored -> artifactStore.payload(stored, ChainSemanticRevision.class))
        .ifPresent(
            revision ->
                persistCombinedSemanticPin(
                    runId, revision, new ClasspathCompilerContractRepository().require(CompilerContract.V1)));
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
  }

  private String approvalPromptFor(String runId, String stageId) {
    if (isBriefRepairApproval(runId, stageId)) {
      return BRIEF_REPAIR_APPROVAL_PROMPT;
    }
    return approvalPrompts.stageApprovalPrompt(
        stageId, responseLocaleOf(runId), languageReferenceFor(runId));
  }

  /** Locale pinned for this run's replies; English when the manifest is not cached yet. */
  private String responseLocaleOf(String runId) {
    RunManifest manifest = manifestsByRun.get(runId);
    return manifest == null ? "en" : manifest.responseLocale();
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
    Map<String, Object> attributes = attributesByRun.get(runId);
    if (attributes != null) {
      Object prior = attributes.get(PRIOR_CANDIDATE_ATTR);
      if (prior instanceof String text && !text.isBlank()) {
        return text.trim();
      }
    }
    return artifactStore.latest(runId, Kind.REQUIREMENT_BRIEF)
        .map(Revision::contentHash)
        .filter(hash -> !approvedTarget.contentHash().equals(hash))
        .orElse(null);
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
      emitted.add(new PipelineSignal.Message(chatPlan));
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
