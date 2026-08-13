package org.qubership.integration.platform.ai.productpipeline.runtime;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.net.URLEncoder;
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
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.profile.BypassPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ImplementationGatePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.SkipPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanChatView;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputIdsPathPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.storage.S3Service;

/**
 * Profile-neutral durable sequential runtime. Advances until a wait, failure, or terminal state.
 */
public final class ProductPipelineRuntime {

  private static final Logger LOG = Logger.getLogger(ProductPipelineRuntime.class);

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

  public ProductPipelineRuntime(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      Clock clock) {
    this(runStore, artifactStore, capabilities, null, null, clock, null);
  }

  public ProductPipelineRuntime(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      Clock clock) {
    this(runStore, artifactStore, capabilities, profileCatalog, null, clock, null, null);
  }

  public ProductPipelineRuntime(
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

  public ProductPipelineRuntime(
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

  public ProductPipelineRuntime(
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

  public ProductPipelineRuntime(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      Clock clock,
      DesignInputIdsPathPrompts idsPathPrompts,
      ApprovalPrompts approvalPrompts,
      S3Service s3Service) {
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
  }

  public Multi<PipelineSignal> startOrResume(StartOrResumeCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              Optional<ProductPipelineRunDocument> existing =
                  runStore.loadByConversation(command.conversationId());
              if (existing.isPresent()) {
                ProductPipelineRunDocument doc = existing.get();
                hydrateCaches(doc, command);
                verifyCompilerPin(manifestsByRun.get(doc.run().runId()));
                if (isTerminalRunStatus(doc.run().status())) {
                  return Multi.createFrom()
                      .item(new PipelineSignal.Completed(doc.run().status()));
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
                return advance(doc.run().runId());
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
                              new StageSnapshot(
                                  stage.stageId(), StageStatus.PENDING, List.of(), null))
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
                      manifestRevision.reference());
              runStore.create(snapshot);
              return advance(command.runId());
            });
  }

  public Multi<PipelineSignal> acceptInput(AcceptInputCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return advance(command.runId());
              }
              if (doc.run().status() != RunStatus.WAITING_FOR_INPUT
                  && doc.run().status() != RunStatus.WAITING_FOR_APPROVAL) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "run is not waiting for input or approval: " + doc.run().status()));
              }
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
              Map<String, Object> attributes =
                  attributesByRun.computeIfAbsent(
                      command.runId(), ignored -> new ConcurrentHashMap<>());
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
              commitStatus(
                  doc,
                  RunStatus.RUNNING,
                  StageStatus.RUNNING,
                  doc.run().stages(),
                  "accepted input",
                  null,
                  command.commandId(),
                  command.commandPayloadHash());
              return advance(command.runId());
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

  public Multi<PipelineSignal> approve(ApproveCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return advance(command.runId());
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
              return advance(command.runId());
            });
  }

  public Multi<PipelineSignal> implement(ImplementCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              if (doc.appliedCommand(command.commandId(), command.commandPayloadHash())
                  .isPresent()) {
                return advance(command.runId());
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
              return advance(command.runId());
            });
  }

  public Multi<PipelineSignal> derive(DeriveRunCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              RunManifest parentManifest = manifestsByRun.get(command.parentRunId());
              if (parentManifest == null) {
                parentManifest = command.runManifest();
              }
              RunManifest derivedManifest =
                  new RunManifest(
                      command.newRunId(),
                      command.parentRunId(),
                      command.sourceReferences(),
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
                      parentManifest.compilerRunPin());
              return startOrResume(
                  new StartOrResumeCommand(
                      command.conversationId(),
                      command.newRunId(),
                      command.profile(),
                      derivedManifest));
            });
  }

  public Multi<PipelineSignal> retry(RetryStageCommand command) {
    Objects.requireNonNull(command, "command");
    return Multi.createFrom()
        .deferred(
            () -> {
              ProductPipelineRunDocument doc = requireRun(command.runId());
              if (doc.run().status() != RunStatus.FAILED) {
                return Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "retry is valid only for FAILED runs, was " + doc.run().status()));
              }
              technicalRetriesByStage.remove(stageRetryKey(command.runId(), doc.run().currentStageId()));
              commitStatus(
                  doc,
                  RunStatus.RUNNING,
                  StageStatus.RUNNING,
                  doc.run().stages(),
                  "manual retry");
              return advance(command.runId());
            });
  }

  private Multi<PipelineSignal> advance(String runId) {
    ProductPipelineRunDocument doc = requireRun(runId);
    if (isTerminalRunStatus(doc.run().status())) {
      return Multi.createFrom().item(new PipelineSignal.Completed(doc.run().status()));
    }
    if (doc.run().status() == RunStatus.WAITING_FOR_INPUT
        || doc.run().status() == RunStatus.WAITING_FOR_APPROVAL
        || doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT
        || doc.run().status() == RunStatus.FAILED) {
      return Multi.createFrom().empty();
    }

    ProductPipelineProfile profile = profilesByRun.get(runId);
    ProfileStage stage = currentStage(doc);
    if (stage.bypass() != null) {
      return executeBypass(runId, doc, stage);
    }
    List<Reference> committed = committedInputs(doc);
    Map<String, Object> attributes =
        enrichAttributesFromCommittedInputs(
            runId, committed, attributesByRun.getOrDefault(runId, Map.of()));
    Optional<SkipPolicy.SkipAction> skipAction = evaluateSkip(stage, attributes);
    if (skipAction.isPresent()) {
      return switch (skipAction.get()) {
        case NO_OUTPUT -> executeNoOutputSkip(runId, doc, stage);
        case REQUIREMENT_DRAFT_PASSTHROUGH -> executeSkip(runId, doc, stage, attributes);
      };
    }
    DeclaredInputResolution inputResolution = resolveDeclaredInputs(profile, stage, committed);
    if (inputResolution.missingRequired() != null) {
      // v2 checks required consumes before the capability runs. Missing runInputs (user-input@1)
      // must wait, not fail — otherwise startOrResume cannot accept the first turn. Keep the wait
      // silent for profile runInputs so chat does not leak the machine "missing required input …"
      // string ahead of the real brief / IDS CTA (coordinator suppresses blank WaitingForInput).
      ArtifactTypeRef missing = inputResolution.missingRequired();
      String prompt =
          isProfileRunInput(profile, missing)
              ? ""
              : "missing required input " + missing.type() + "@" + missing.schemaVersion();
      return handleCapabilityResult(
          runId,
          stage,
          UUID.randomUUID().toString(),
          List.of(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, prompt))));
    }
    List<Reference> inputs = inputResolution.inputs();
    StageCapability capability = capabilities.require(stage.capabilityId());
    String attemptId = UUID.randomUUID().toString();
    String executionKey = executionKey(runId, stage.stageId());
    StageExecutionContext context =
        new StageExecutionContext(
            runId,
            doc.run().conversationId(),
            stage.stageId(),
            executionKey,
            attemptId,
            profile,
            manifestsByRun.get(runId),
            inputs,
            attributes);

    return capability
        .execute(context)
        .onItem()
        .transformToMultiAndConcatenate(
            signal -> {
              if (signal instanceof CapabilitySignal.SkillProgress skillProgress) {
                return Multi.createFrom()
                    .item(
                        new PipelineSignal.SkillProgress(
                            skillProgress.skillId(), skillProgress.status()));
              }
              if (signal instanceof CapabilitySignal.Progress progress) {
                return Multi.createFrom()
                    .item(new PipelineSignal.Progress(stage.stageId(), progress.label()));
              }
              if (signal instanceof CapabilitySignal.Message message) {
                return Multi.createFrom().item(new PipelineSignal.Message(message.text()));
              }
              if (signal instanceof CapabilitySignal.Completed) {
                return handleCapabilityResult(runId, stage, attemptId, List.of(signal));
              }
              return Multi.createFrom().empty();
            });
  }

  /**
   * Downstream stages read approved artifacts from attributes ({@code approvedDraft},
   * {@code requirementBrief}). Hydrate them from committed input refs so analysis/planning do not
   * depend on ThreadLocal capture state from a previous stage.
   *
   * <p>{@code requirement-draft} is mutated in place across discovery → import (ADR 0001). Later
   * committed drafts must overwrite {@code approvedDraft}; first-write-wins left analysis on the
   * pre-import {@code NEEDS_INPUT} snapshot after a successful specification-import.
   */
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
        // Last committed draft wins (import-stage output after discovery).
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

  /** Marks a stage succeeded with no artifact candidates (route activation). */
  private Multi<PipelineSignal> executeNoOutputSkip(
      String runId, ProductPipelineRunDocument doc, ProfileStage stage) {
    return handleCapabilityResult(
        runId,
        stage,
        UUID.randomUUID().toString(),
        List.of(
            new CapabilitySignal.Completed(
                new StageOutcome(
                    StageOutcomeClass.SUCCEEDED,
                    List.of(),
                    "stage skipped with no output by profile skip policy",
                    null))));
  }

  /**
   * V1 keeps all committed inputs. V2 ({@code profileVersion=2}) requires every {@code consumes}
   * kind, includes present {@code optionalConsumes}, and ignores unrelated committed kinds.
   */
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
    // Prefer the latest match: GENERATE/DERIVE accumulate multiple APPROVAL_RECORD refs
    // (brief, IDS, implementation). design-execution must consume the implementation approval.
    return committed.stream()
        .filter(ref -> typeRef.matches(ref.kind()))
        .reduce((first, second) -> second)
        .orElse(null);
  }

  /** True when the missing consume is a profile {@code runInputs} bootstrap artifact. */
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

  private record DeclaredInputResolution(List<Reference> inputs, ArtifactTypeRef missingRequired) {
    private DeclaredInputResolution {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
  }

  private Multi<PipelineSignal> executeBypass(
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
      return Multi.createFrom().item(new PipelineSignal.Completed(terminalStatus));
    }
    String next = nextStageId(profile, stage.stageId());
    commitStatus(doc, RunStatus.RUNNING, StageStatus.SUCCEEDED, updated, "bypass committed");
    ProductPipelineRunDocument after = requireRun(runId);
    commitMove(after, next, markStageRunning(after, next), "advance after bypass");
    return advance(runId);
  }

  /**
   * Passthrough for conditional skip: re-commit the consumed requirement-draft without calling the
   * capability (no external API).
   */
  private Multi<PipelineSignal> executeSkip(
      String runId,
      ProductPipelineRunDocument doc,
      ProfileStage stage,
      Map<String, Object> attributes) {
    RequirementDraft draft =
        attributes.get("approvedDraft") instanceof RequirementDraft requirementDraft
            ? requirementDraft
            : null;
    if (draft == null) {
      return handleCapabilityResult(
          runId,
          stage,
          UUID.randomUUID().toString(),
          List.of(
              new CapabilitySignal.Completed(
                  StageOutcome.of(
                      StageOutcomeClass.MISSING_MANDATORY_INPUT,
                      "skip requires committed requirement-draft"))));
    }
    return handleCapabilityResult(
        runId,
        stage,
        UUID.randomUUID().toString(),
        List.of(
            new CapabilitySignal.Completed(
                new StageOutcome(
                    StageOutcomeClass.SUCCEEDED,
                    List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                    "stage skipped by profile skip policy",
                    null))));
  }

  private Multi<PipelineSignal> handleCapabilityResult(
      String runId, ProfileStage stage, String attemptId, List<CapabilitySignal> signals) {
    List<PipelineSignal> emitted = new ArrayList<>();
    StageOutcome outcome = requireSingleCompleted(signals);

    ProductPipelineRunDocument doc = requireRun(runId);
    return switch (outcome.outcomeClass()) {
      case NEEDS_INPUT -> {
        commitStatus(
            doc,
            RunStatus.WAITING_FOR_INPUT,
            StageStatus.WAITING_FOR_INPUT,
            doc.run().stages(),
            outcome.message());
        emitted.add(
            new PipelineSignal.WaitingForInput(
                stage.stageId(), outcome.message() == null ? "" : outcome.message()));
        yield Multi.createFrom().iterable(emitted);
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
                  stage.stageId(), StageOutcomeClass.CONTRACT_FAILURE, resolution.contractFailure()));
          yield Multi.createFrom().iterable(emitted);
        }
        List<Reference> refs =
            appendCandidates(
                runId, stage, resolution.resolvedCandidates(), committedInputs(doc));
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
        emitImplementationPlanForReview(runId, approvable, emitted);
        emitIdsDocumentForReview(runId, approvable, emitted);
        emitRequirementBriefForReview(runId, approvable, emitted);
        emitted.add(
            new PipelineSignal.WaitingForApproval(
                stage.stageId(), approvable, approvalPromptFor(runId, stage.stageId())));
        yield Multi.createFrom().iterable(emitted);
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
          yield Multi.createFrom().iterable(emitted);
        }
        String next = nextStageId(profile, stage.stageId());
        commitStatus(doc, RunStatus.RUNNING, StageStatus.SUCCEEDED, updated, outcome.message());
        ProductPipelineRunDocument after = requireRun(runId);
        commitMove(after, next, markStageRunning(after, next), "advance after success");
        yield Multi.createFrom()
            .iterable(emitted)
            .onCompletion()
            .switchTo(() -> advance(runId));
      }
      case RETRYABLE_TECHNICAL_FAILURE -> {
        String key = stageRetryKey(runId, stage.stageId());
        int used = technicalRetriesByStage.getOrDefault(key, 0);
        int max = stage.retry().maxTechnicalRetries();
        if (used >= max) {
          commitStatus(
              doc, RunStatus.FAILED, StageStatus.FAILED, doc.run().stages(), outcome.message());
          emitted.add(
              new PipelineSignal.Failed(
                  stage.stageId(), outcome.outcomeClass(), outcome.message()));
          yield Multi.createFrom().iterable(emitted);
        }
        technicalRetriesByStage.put(key, used + 1);
        long delay =
            outcome.retryDelayMs() != null
                ? outcome.retryDelayMs()
                : stage.retry().defaultDelayMs();
        yield Multi.createFrom()
            .iterable(emitted)
            .onCompletion()
            .switchTo(
                () ->
                    Uni.createFrom()
                        .voidItem()
                        .onItem()
                        .delayIt()
                        .by(Duration.ofMillis(Math.max(delay, 0L)))
                        .onItem()
                        .transformToMulti(ignored -> advance(runId)));
      }
      case VALIDATION_FAILURE -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        String failureMessage =
            outcome.message() == null || outcome.message().isBlank()
                ? outcome.outcomeClass().name()
                : outcome.message();
        ProductPipelineProfile profile = profilesByRun.get(runId);
        Optional<String> reopenStageId =
            previousApprovalStageId(profile, stage.stageId());
        if (reopenStageId.isPresent()) {
          yield reopenPreviousApprovalAfterValidationFailure(
              doc, profile, stage, refs, reopenStageId.get(), failureMessage, emitted);
        }
        List<StageSnapshot> failedStages =
            refs.isEmpty()
                ? doc.run().stages()
                : markStageOutputs(doc, stage.stageId(), refs, StageStatus.FAILED);
        String evidence = outcome.outcomeClass().name() + ": " + failureMessage;
        commitStatus(
            doc,
            RunStatus.FAILED,
            StageStatus.FAILED,
            failedStages,
            evidence,
            evidence);
        emitted.add(
            new PipelineSignal.Failed(stage.stageId(), outcome.outcomeClass(), failureMessage));
        yield Multi.createFrom().iterable(emitted);
      }
      case CONTRACT_FAILURE,
          POLICY_FAILURE,
          DOMAIN_FAILURE,
          MISSING_MANDATORY_INPUT -> {
        List<Reference> refs =
            appendCandidates(runId, stage, resolveProducedCandidates(stage, outcome.candidates()));
        List<StageSnapshot> failedStages =
            refs.isEmpty()
                ? doc.run().stages()
                : markStageOutputs(doc, stage.stageId(), refs, StageStatus.FAILED);
        String evidence =
            outcome.outcomeClass().name()
                + (outcome.message() == null || outcome.message().isBlank()
                    ? ""
                    : ": " + outcome.message());
        commitStatus(
            doc,
            RunStatus.FAILED,
            StageStatus.FAILED,
            failedStages,
            evidence,
            evidence);
        emitted.add(
            new PipelineSignal.Failed(stage.stageId(), outcome.outcomeClass(), outcome.message()));
        yield Multi.createFrom().iterable(emitted);
      }
    };
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
      String typeName =
          matched == null ? candidate.kind().name() : matched.type();
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

  /**
   * Candidate outcomes may emit produced artifacts plus immutable consumed refs that the approval
   * candidate set reuses without appending duplicate revisions.
   */
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

  private StageSnapshot currentStageSnapshot(ProductPipelineRunDocument doc, String stageId) {
    return doc.run().stages().stream()
        .filter(snapshot -> snapshot.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private static ProfileStage stageById(ProductPipelineProfile profile, String stageId) {
    return profile.stages().stream()
        .filter(snapshot -> snapshot.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private record ResolvedCandidate(ArtifactCandidate candidate, ArtifactTypeRef typeRef) {}

  private record CandidateResolution(
      List<ResolvedCandidate> resolvedCandidates, String contractFailure) {

    private CandidateResolution {
      resolvedCandidates =
          resolvedCandidates == null ? List.of() : List.copyOf(resolvedCandidates);
    }
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
    // User turns are stored as USER_INPUT artifacts, not stage outputs. v2 required-consume
    // checks need them in the committed set or start/accept loops forever at NEEDS_INPUT.
    artifactStore.history(doc.run().runId(), Kind.USER_INPUT).stream()
        .map(Revision::reference)
        .forEach(refs::add);
    return List.copyOf(refs);
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

  /**
   * Last profile stage before {@code failedStageId} that declares an approval policy. Used to
   * reopen brief approval after planning VALIDATION_FAILURE instead of leaving the run terminal.
   */
  static Optional<String> previousApprovalStageId(
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
            .orElse(command.runManifest());
    ProductPipelineProfile profile;
    if (profileCatalog != null
        && manifest.profileId() != null
        && manifest.profileVersion() != null) {
      profile = profileCatalog.require(manifest.profileId(), manifest.profileVersion());
    } else {
      profile = command.profile();
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
    if (!stageInputs.isEmpty()) {
      attributes.put("userText", stageInputs.get(stageInputs.size() - 1).text());
      attributes.put("discoveryUserText", stageInputs.get(0).text());
      if ("design-input".equals(doc.run().currentStageId())) {
        for (int i = stageInputs.size() - 1; i >= 0; i--) {
          DesignMode idsPathChoice =
              DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords(stageInputs.get(i).text());
          if (idsPathChoice == DesignMode.GENERATE || idsPathChoice == DesignMode.DERIVE) {
            attributes.put(DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR, idsPathChoice);
            break;
          }
        }
      }
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
    return approvalPrompts.stageApprovalPrompt(stageId, languageReferenceFor(runId));
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
