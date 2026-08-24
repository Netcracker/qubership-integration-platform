package org.qubership.integration.platform.ai.productpipeline.runtime;

import io.smallrye.mutiny.Multi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecord;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputIdsPathPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ImplementationGatePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
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

  /**
   * Content hash of the owner's last approved candidate. Set when that stage is re-entered after a
   * causal reopen.
   */
  public static final String PRIOR_CANDIDATE_ATTR = "priorCandidate";

  private static final String CAUSAL_REOPEN_REASON_PREFIX = "causal reopen of ";
  private static final int MAX_CAUSAL_REOPENS = 2;

  private static final String HALT_FOLLOW_UP_INPUT_PREFIX = "halt-follow-up-";

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final StageCapabilityRegistry capabilities;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final CompilerRunPinResolver compilerRunPinResolver;
  private final Clock clock;
  private final DesignInputIdsPathPrompts idsPathPrompts;
  private final ApprovalPrompts approvalPrompts;
  /** Optional; when present, IDS approval also offers a storage download link. */
  private final S3Service s3Service;
  private final Map<String, ProductPipelineProfile> profilesByRun = new ConcurrentHashMap<>();
  private final Map<String, RunManifest> manifestsByRun = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> attributesByRun = new ConcurrentHashMap<>();
  private final Map<String, Integer> technicalRetriesByStage = new ConcurrentHashMap<>();
  private final StageExecutor stageExecutor;

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock) {
    this(runStore, artifactStore, capabilities, null, null, clock, null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      Clock clock) {
    this(runStore, artifactStore, capabilities, profileCatalog, null, clock, null, null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock) {
    this(
        runStore,
        artifactStore,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        null,
        null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      DesignInputIdsPathPrompts idsPathPrompts) {
    this(
        runStore,
        artifactStore,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        idsPathPrompts,
        null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      DesignInputIdsPathPrompts idsPathPrompts,
      ApprovalPrompts approvalPrompts) {
    this(
        runStore,
        artifactStore,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        idsPathPrompts,
        approvalPrompts,
        null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      DesignInputIdsPathPrompts idsPathPrompts,
      ApprovalPrompts approvalPrompts,
      S3Service s3Service) {
    this(
        runStore,
        artifactStore,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        clock,
        idsPathPrompts,
        approvalPrompts,
        s3Service,
        null);
  }

  public ProductPipelineRunSupport(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      DesignInputIdsPathPrompts idsPathPrompts,
      ApprovalPrompts approvalPrompts,
      S3Service s3Service,
      FailureNarrative failureNarrative) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.profileCatalog = profileCatalog;
    this.compilerRunPinResolver = compilerRunPinResolver;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idsPathPrompts =
        idsPathPrompts == null ? new DesignInputIdsPathPrompts() : idsPathPrompts;
    this.approvalPrompts = approvalPrompts == null ? new ApprovalPrompts() : approvalPrompts;
    this.s3Service = s3Service;
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
            failureNarrative == null ? new FailureNarrative() : failureNarrative);
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
              if (doc.run().status() == RunStatus.FAILED) {
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.Failed(
                            doc.run().currentStageId(),
                            StageOutcomeClass.DOMAIN_FAILURE,
                            "run is failed; use retry"));
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
              if (isHaltFollowUp(doc, command.text())) {
                return recordHaltFollowUp(doc, command);
              }
              if (isOwnerChoicePick(doc, command.text())) {
                return recordOwnerChoice(doc, command);
              }
              boolean retryClick = PipelineGates.RETRY_ACTION.equals(command.text());
              boolean reviseClick = PipelineGates.REVISE_ACTION.equals(command.text());
              boolean haltCardClick = retryClick || reviseClick;
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
                // Only design-input may latch GENERATE/DERIVE. Keywords only here: acceptInput may
                // run on the Vert.x event loop, so blocking LLM classify is forbidden. Full LLM
                // classify runs later in DesignInputCapability on the worker pool.
                if ("design-input".equals(doc.run().currentStageId())) {
                  DesignMode idsPathChoice =
                      DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords(command.text());
                  if (idsPathChoice == DesignMode.GENERATE || idsPathChoice == DesignMode.DERIVE) {
                    attributes.put(
                        DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR, idsPathChoice);
                  }
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
            });
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
   * open. The next diagnosis turn reads {@link #HALT_FOLLOW_UP_TEXT_ATTR}.
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
    attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, command.text() == null ? "" : command.text());
    String prompt = latestWaitingForInputPrompt(doc);
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

  private Multi<PipelineSignal> recordRevise(
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
    if (shouldCausalReopen(doc, owner)) {
      return causalReopenOwner(doc, command, owner);
    }
    return stayWaitingForInput(doc, command);
  }

  private boolean shouldCausalReopen(ProductPipelineRunDocument doc, String owner) {
    if (!isEarlierApprovedOwner(doc, owner)) {
      return false;
    }
    if (catalogHasBeenWritten(doc.run().runId())) {
      return false;
    }
    if (causalReopenCount(doc) >= MAX_CAUSAL_REOPENS) {
      return false;
    }
    return !ownerAlreadyHadCausalReopen(doc, owner);
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

  private static long causalReopenCount(ProductPipelineRunDocument doc) {
    return doc.transitions().stream()
        .filter(
            transition ->
                transition.reason() != null
                    && transition.reason().startsWith(CAUSAL_REOPEN_REASON_PREFIX))
        .count();
  }

  private static boolean ownerAlreadyHadCausalReopen(
      ProductPipelineRunDocument doc, String owner) {
    String reason = CAUSAL_REOPEN_REASON_PREFIX + owner;
    return doc.transitions().stream().anyMatch(transition -> reason.equals(transition.reason()));
  }

  private Multi<PipelineSignal> causalReopenOwner(
      ProductPipelineRunDocument doc, AcceptInputCommand command, String owner) {
    ProductPipelineProfile profile = profilesByRun.get(command.runId());
    StageSnapshot ownerSnapshot =
        doc.run().stages().stream()
            .filter(stage -> owner.equals(stage.stageId()))
            .findFirst()
            .orElse(null);
    Reference prior = ownerSnapshot == null ? null : resolveReopenApprovable(ownerSnapshot);
    if (profile == null || ownerSnapshot == null || prior == null) {
      return stayWaitingForInput(doc, command);
    }
    attributesByRun
        .computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>())
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
        CAUSAL_REOPEN_REASON_PREFIX + owner,
        command.commandId(),
        command.commandPayloadHash());
    return Multi.createFrom().empty();
  }

  private Multi<PipelineSignal> stayWaitingForInput(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    String prompt = latestWaitingForInputPrompt(doc);
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

  private Multi<PipelineSignal> recordOwnerChoice(
      ProductPipelineRunDocument doc, AcceptInputCommand command) {
    Map<String, Object> attributes =
        attributesByRun.computeIfAbsent(command.runId(), ignored -> new ConcurrentHashMap<>());
    attributes.put(DIAGNOSED_OWNER_STAGE_ATTR, command.text());
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
    if (!PipelineGates.OWNER_CHOICE.equals(PipelineGates.gateOf(prompt).orElse(""))) {
      return false;
    }
    return PipelineGates.ownerCandidatesOf(prompt).contains(text);
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
    return PipelineGates.isRecoverableHaltGate(
        PipelineGates.gateOf(latestWaitingForInputPrompt(doc)).orElse(""));
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
              List<Reference> approvedCandidates =
                  approvedCandidates(stage.outputRefs(), stageProfile.approval());
              boolean multiItemApproval =
                  stageProfile.approval() != null
                      && stageProfile.approval().candidateSet().size() > 1;
              Revision approvalRevision;
              if (multiItemApproval) {
                ApprovalPolicy approvalPolicy = stageProfile.approval();
                approvalRevision =
                    artifactStore.append(
                        new AppendCommand(
                            command.runId(),
                            Kind.APPROVAL_RECORD,
                            "2",
                            "product-pipeline-runtime",
                            "1",
                            new ApprovalRecordV2(
                                target,
                                target.contentHash(),
                                approvedCandidates,
                                "user",
                                null,
                                clock.instant(),
                                approvalPolicy.bindingResolutionPolicy(),
                                approvalPolicy.bindingResolutionPolicyHash()),
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
      case StageDecision.ReopenApproval reopen ->
          applyReopenApproval(runId, reopen, result.signals());
      case StageDecision.WaitForApproval wait ->
          applyWaitForApproval(runId, wait, result.signals());
      case StageDecision.WaitForInput ignored -> Multi.createFrom().iterable(result.signals());
      case StageDecision.WaitForImplementation ignored ->
          Multi.createFrom().iterable(result.signals());
      case StageDecision.Fail ignored -> Multi.createFrom().iterable(result.signals());
      case StageDecision.Complete ignored -> Multi.createFrom().iterable(result.signals());
    };
  }

  private Multi<PipelineSignal> applyContinue(
      String runId, StageDecision.Continue decision, List<PipelineSignal> signals) {
    ProductPipelineProfile profile = profilesByRun.get(runId);
    ProductPipelineRunDocument doc = requireRun(runId);
    String next = nextStageId(profile, decision.stageId());
    commitMove(doc, next, markStageRunning(doc, next), "advance after success");
    return Multi.createFrom().iterable(signals);
  }

  private Multi<PipelineSignal> applyReopenApproval(
      String runId, StageDecision.ReopenApproval reopen, List<PipelineSignal> live) {
    ProductPipelineRunDocument doc = requireRun(runId);
    ProductPipelineProfile profile = profilesByRun.get(runId);
    ProfileStage failedStage = currentStage(doc);
    List<PipelineSignal> emitted = new ArrayList<>(live);
    return reopenPreviousApprovalAfterValidationFailure(
        doc,
        profile,
        failedStage,
        reopen.evidenceRefs(),
        reopen.approvalStageId(),
        reopen.message(),
        emitted);
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
    commitMove(doc, nextStageId, stages, reason, null, null);
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
                null),
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

  private static List<StageSnapshot> markStageOutputs(
      ProductPipelineRunDocument doc,
      String stageId,
      List<Reference> refs,
      StageStatus status) {
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

  private Multi<PipelineSignal> reopenPreviousApprovalAfterValidationFailure(
      ProductPipelineRunDocument doc,
      ProductPipelineProfile profile,
      ProfileStage failedStage,
      List<Reference> failedStageRefs,
      String reopenStageId,
      String failureMessage,
      List<PipelineSignal> emitted) {
    StageSnapshot reopenSnapshot =
        doc.run().stages().stream()
            .filter(snapshot -> reopenStageId.equals(snapshot.stageId()))
            .findFirst()
            .orElse(null);
    if (reopenSnapshot == null) {
      return terminalValidationFailure(doc, failedStage, failedStageRefs, failureMessage, emitted);
    }
    Reference approvable = resolveReopenApprovable(reopenSnapshot);
    if (approvable == null) {
      return terminalValidationFailure(doc, failedStage, failedStageRefs, failureMessage, emitted);
    }

    Set<String> stagesAfterReopen = stageIdsAfter(profile, reopenStageId);
    List<StageSnapshot> updated = new ArrayList<>();
    for (StageSnapshot snapshot : doc.run().stages()) {
      if (reopenStageId.equals(snapshot.stageId())) {
        List<Reference> candidates =
            snapshot.candidateReferences().isEmpty()
                ? List.of(approvable)
                : snapshot.candidateReferences();
        List<Reference> outputs =
            snapshot.outputRefs().stream()
                .filter(ref -> ref.kind() != Kind.APPROVAL_RECORD)
                .toList();
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.WAITING_FOR_APPROVAL,
                outputs,
                null,
                candidates,
                approvable,
                snapshot.candidateRevision()));
      } else if (failedStage.stageId().equals(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(),
                StageStatus.PENDING,
                failedStageRefs,
                null,
                List.of(),
                null,
                null));
      } else if (stagesAfterReopen.contains(snapshot.stageId())) {
        updated.add(
            new StageSnapshot(
                snapshot.stageId(), StageStatus.PENDING, List.of(), null, List.of(), null, null));
      } else {
        updated.add(snapshot);
      }
    }

    String evidence = StageOutcomeClass.VALIDATION_FAILURE.name() + ": " + failureMessage;
    long expected = doc.run().runRevision();
    runStore.commit(
        expected,
        new LogicalCommit(
            doc.run().runId(),
            expected,
            RunStatus.WAITING_FOR_APPROVAL,
            reopenStageId,
            updated,
            new StageAttempt(
                UUID.randomUUID().toString(),
                failedStage.stageId(),
                expected + 1L,
                StageStatus.FAILED,
                clock.instant(),
                clock.instant(),
                failedStageRefs,
                evidence),
            new RunTransition(
                expected,
                expected + 1L,
                doc.run().status(),
                RunStatus.WAITING_FOR_APPROVAL,
                reopenStageId,
                clock.instant(),
                "reopen previous approval after validation failure")));

    String chatMessage =
        failureMessage
            + " Rolled back to approval of stage "
            + reopenStageId
            + ". Revise the brief if needed, then reply Agree to retry planning.";
    emitted.add(
        new PipelineSignal.Failed(
            failedStage.stageId(), StageOutcomeClass.VALIDATION_FAILURE, chatMessage));
    emitted.add(
        new PipelineSignal.WaitingForApproval(
            reopenStageId, approvable, approvalPromptFor(doc.run().runId(), reopenStageId)));
    return Multi.createFrom().iterable(emitted);
  }

  private Multi<PipelineSignal> terminalValidationFailure(
      ProductPipelineRunDocument doc,
      ProfileStage failedStage,
      List<Reference> failedStageRefs,
      String failureMessage,
      List<PipelineSignal> emitted) {
    List<StageSnapshot> failedStages =
        failedStageRefs.isEmpty()
            ? doc.run().stages()
            : markStageOutputs(doc, failedStage.stageId(), failedStageRefs, StageStatus.FAILED);
    String evidence = StageOutcomeClass.VALIDATION_FAILURE.name() + ": " + failureMessage;
    commitStatus(
        doc, RunStatus.FAILED, StageStatus.FAILED, failedStages, evidence, evidence);
    emitted.add(
        new PipelineSignal.Failed(
            failedStage.stageId(), StageOutcomeClass.VALIDATION_FAILURE, failureMessage));
    return Multi.createFrom().iterable(emitted);
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
    if (profilesByRun.containsKey(runId) && manifestsByRun.containsKey(runId)) {
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
      if ("design-input".equals(doc.run().currentStageId())) {
        for (int i = requirementInputs.size() - 1; i >= 0; i--) {
          DesignMode idsPathChoice =
              DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords(
                  requirementInputs.get(i).text());
          if (idsPathChoice == DesignMode.GENERATE || idsPathChoice == DesignMode.DERIVE) {
            attributes.put(DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR, idsPathChoice);
            break;
          }
        }
      }
    }
    if (!followUps.isEmpty()) {
      attributes.put(HALT_FOLLOW_UP_TEXT_ATTR, followUps.get(followUps.size() - 1).text());
    }
    // Rehydrate only this run's counters. Clearing the whole map would drop retries for
    // other in-memory runs that share the same runtime bean.
    technicalRetriesByStage.keySet().removeIf(key -> key.startsWith(runId + ":"));
    for (StageAttempt attempt : doc.attempts()) {
      if (attempt.outcome() == StageStatus.FAILED) {
        String key = stageRetryKey(runId, attempt.stageId());
        technicalRetriesByStage.put(key, technicalRetriesByStage.getOrDefault(key, 0) + 1);
      }
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
    attributes.remove(DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR);
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
  private void emitIdsDocumentForReview(
      String runId, Reference approvable, List<PipelineSignal> emitted) {
    if (approvable == null || approvable.kind() != Kind.IDS_DOCUMENT) {
      return;
    }
    Optional<Revision> revision = artifactStore.get(runId, approvable);
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
