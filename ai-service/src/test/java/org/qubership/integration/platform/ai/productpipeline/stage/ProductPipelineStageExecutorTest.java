package org.qubership.integration.platform.ai.productpipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.exception.ToolArgumentsException;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.MappingTurnAdapter;
import org.qubership.integration.platform.ai.plan.MappingTurnResult;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
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
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningDegradations;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.BypassPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.SkipPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.FakeStageCapabilities;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.runtime.SemanticRecoveryState;
import org.qubership.integration.platform.ai.productpipeline.runtime.StaleApprovalException;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.recovery.ProposedBriefChange;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class ProductPipelineStageExecutorTest {

  private static final Instant FIXED = Instant.parse("2026-08-14T00:00:00Z");
  private static final String CONVERSATION = "conversation-stage-executor-1";
  private static final String RUN_ID = "run-stage-executor-1";
  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private InMemoryArtifactBlobStore blobStore;
  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore =
        new ProductPipelineArtifactStore(new CompilationArtifacts(blobStore, mapper, clock));
  }

  @ParameterizedTest
  @ValueSource(strings = {"requirement-discovery", "requirement-analysis", "design-input", "design-planning", "design-execution"})
  void everyProducerCanUseTwoTargetedAndTwoCleanAttemptsWithoutANewSourceMessage(String stageId) {
    var calls = new AtomicInteger();
    var rejected = capability(stageId, context -> {
      calls.incrementAndGet();
      assertEquals("provided input", context.attributeAsString("userText"));
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, "Generated shape is invalid.",
          RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE))));
    });
    var input = new ArtifactTypeRef("user-input", 1);
    var graph = new ArtifactTypeRef("chain-plan-graph", 1);
    var profile = new ProductPipelineProfile(1, "all-producer-repair", "2", List.of(input),
        List.of(new ProfileStage(stageId, stageId, List.of(input), List.of(graph), null, null, new RetryPolicy(0, 0L))),
        new TerminalPolicy(stageId, "CHAIN_MATERIALIZED"), List.of(stageId));
    var runtime = newRuntime(2, new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(2, 2, 12, 2)),
        profile, rejected);
    startAndRecordInput(runtime, profile);
    for (int attempt = 0; attempt < 4; attempt++) {
      var correction = execute(runtime, stageId);
      assertInstanceOf(StageDecision.Retry.class, correction.decision());
      applyLifecycle(runtime, correction);
    }
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, stageId).decision());
    assertEquals(PipelineGates.RECOVERY_REPAIR_ALLOWANCE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(5, calls.get());
    assertEquals(RUN_ID, requireRun().run().runId());
  }

  @Test
  void aPreparedPlanningFailureWithoutCaptureCannotJournalTheAcceptedPlanAsRejected() {
    var planning = capability("planning-cap", context -> !org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence.isRepairTurn(context)
        ? planningRepairCandidate(new AtomicReference<>(), new AtomicReference<>()).execute(context)
        : Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
            "No generated plan was captured.", RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE)))));
    var profile = planningThenExecutionProfile();
    var runtime = newRuntime(new FailureNarrative(FakeFailureNarrativeAgent.owner("Plan omitted RBAC.", "design-planning")),
        profile, planning, executionMissingBriefFactsValidationFailure(new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "design-planning");
    var accepted = snapshot(requireRun(), "design-planning");
    applyLifecycle(runtime, execute(runtime, "design-execution"));
    execute(runtime, "design-planning");
    var failed = requireRun().attempts().stream().filter(attempt -> "design-planning".equals(attempt.stageId())
        && attempt.outcome() == StageStatus.FAILED).reduce((first, second) -> second).orElseThrow();
    assertTrue(failed.outputs().isEmpty());
    assertEquals(accepted.outputRefs(), snapshot(requireRun(), "design-planning").outputRefs());
    assertEquals(accepted.approvedArtifactId(), snapshot(requireRun(), "design-planning").approvedArtifactId());
  }

  @Test
  void verifyApprovalAcceptsUnchangedRevision() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    stageExecutor().verifyApproval(approval, revision);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void typedRepairBudgetRemainsSpentInRuntimeStateAndAfterReconstruction(boolean restart) {
    var cause = new RecoveryCause(RecoveryCauseCode.UNKNOWN_PROPERTY,
        List.of(new PlanValidationFinding("UNKNOWN_PROPERTY", "Use the supported property.", true,
            null, "trigger-A", "/properties/method")), "");
    var rejecting = capability("execution-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.VALIDATION_FAILURE,
            "The generated property is invalid.", cause))));
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(profile, analysisCandidate(), planningAlwaysCandidate(), rejecting);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    if (restart) {
      runtime = newRuntime(profile, analysisCandidate(), planningAlwaysCandidate(), rejecting);
      runtime.startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
          .collect().asList().await().indefinitely();
    }
    assertEquals(0, runtime.support().captureSemanticRecoveryState(RUN_ID).remaining().semanticRepairsRemaining());
    if (!restart) {
      StageExecutionResult clean = execute(runtime, "design-execution");
      assertInstanceOf(StageDecision.Retry.class, clean.decision());
      applyLifecycle(runtime, clean);
    }
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "requirement-analysis").status());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "design-planning").status());
  }

  @Test
  void verifyApprovalRejectsChangedMapping() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    ChainSemanticRevision changed = withChangedMapping(revision);
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, changed));
    assertTrue(error.getMessage().contains("Approved semantic revision digest does not match"));
  }

  @Test
  void verifyApprovalRejectsChangedEntryPoint() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    ChainSemanticRevision changed = withChangedEntryPoint(revision);
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, changed));
    assertTrue(error.getMessage().contains("Approved semantic revision digest does not match"));
  }

  @Test
  void verifyApprovalRejectsChangedEdge() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    ChainSemanticRevision changed = withChangedEdge(revision);
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, changed));
    assertTrue(error.getMessage().contains("Approved semantic revision digest does not match"));
  }

  @Test
  void verifyApprovalRejectsChangedProvenanceCitation() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    ChainSemanticRevision changed = withChangedProvenanceCitation(revision);
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, changed));
    assertTrue(error.getMessage().contains("Approved semantic revision digest does not match"));
  }

  @Test
  void verifyApprovalRejectsPinnedSchemaVersionMismatch() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval =
        withSubjectSchemaVersion(approve(revision), "chain-semantic-revision/v0");
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, revision));
    assertTrue(error.getMessage().contains("Approved semantic schema version does not match"));
  }

  @Test
  void verifyApprovalAcceptsMatchingContract() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    stageExecutor().verifyApproval(approval, revision, CONTRACT);
  }

  @Test
  void verifyApprovalRejectsChangedContractDigest() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval =
        withCompilerContractSha256(approve(revision), "aa".repeat(32));
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> executor.verifyApproval(approval, revision, CONTRACT));
    assertTrue(error.getMessage().contains("Approved compiler contract digest does not match"));
  }

  @Test
  void verifyApprovalRejectsNullContractDigest() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = withCompilerContractSha256(approve(revision), null);
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, revision));
    assertTrue(error.getMessage().contains("Approved compiler contract digest does not match"));
  }

  @Test
  void verifyApprovalRejectsBlankContractDigest() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = withCompilerContractSha256(approve(revision), "  ");
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, revision));
    assertTrue(error.getMessage().contains("Approved compiler contract digest does not match"));
  }

  @Test
  void verifyApprovalRejectsArtifactKindMismatch() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = withSubjectArtifactKind(approve(revision), Kind.IDS_DOCUMENT.name());
    ProductPipelineStageExecutor executor = stageExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> executor.verifyApproval(approval, revision));
    assertTrue(error.getMessage().contains("Approved semantic artifact kind does not match"));
  }

  @Test
  void typedDecisionCoversEveryLifecycleOutcome() {
    Set<String> kinds =
        Arrays.stream(StageDecision.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    assertEquals(
        Set.of(
            "Continue",
            "WaitForInput",
            "WaitForApproval",
            "WaitForImplementation",
            "Retry",
            "ReopenProducer",
            "Fail",
            "Complete"),
        kinds);
  }

  @Test
  void executesAtMostOneStageAndDoesNotSelectTheNextStage() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    ProductPipelineProfile profile = twoStageSuccessProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            succeeding("flow-first", firstCalls),
            succeeding("flow-second", secondCalls));
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "first");

    assertInstanceOf(StageDecision.Continue.class, result.decision());
    assertEquals("first", result.decision().stageId());
    assertEquals(1, firstCalls.get());
    assertEquals(0, secondCalls.get());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals("first", doc.run().currentStageId());
    assertEquals(RunStatus.RUNNING, doc.run().status());
    assertEquals(StageStatus.SUCCEEDED, stageStatus(doc, "first"));
    assertFalse(result.decision() instanceof StageDecision.Complete);
  }

  @Test
  void skillProgressReachesTheLiveSinkBeforeTheStageFinishes() throws Exception {
    CountDownLatch liveRunning = new CountDownLatch(1);
    AtomicBoolean stageFinished = new AtomicBoolean(false);
    AtomicReference<StageExecutionResult> result = new AtomicReference<>();
    List<PipelineSignal> live = new CopyOnWriteArrayList<>();
    StageCapability slow =
        capability(
            "only-cap",
            context ->
                Multi.createFrom()
                    .emitter(
                        emitter -> {
                          emitter.emit(
                              new CapabilitySignal.SkillProgress("cip-http-generator", "running"));
                          try {
                            Thread.sleep(250);
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                          emitter.emit(
                              new CapabilitySignal.Completed(
                                  StageOutcome.of(StageOutcomeClass.SUCCEEDED, "done")));
                          emitter.complete();
                        }));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "live-skill",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "only",
                    "only-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("only", "CHAIN_MATERIALIZED"),
            List.of("only-cap"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, slow);
    startAndRecordInput(runtime, profile);
    PipelineSignalLiveSink.bind(
        RUN_ID,
        signal -> {
          live.add(signal);
          if (signal instanceof PipelineSignal.SkillProgress progress
              && "cip-http-generator".equals(progress.skillId())
              && "running".equals(progress.status())) {
            assertFalse(stageFinished.get());
            liveRunning.countDown();
          }
        });
    try {
      Thread runner =
          new Thread(
              () -> {
                result.set(execute(runtime, "only"));
                stageFinished.set(true);
              });
      runner.start();
      assertTrue(liveRunning.await(5, TimeUnit.SECONDS));
      runner.join(10_000);
      assertTrue(stageFinished.get());
      assertTrue(
          live.stream().anyMatch(PipelineSignal.SkillProgress.class::isInstance),
          "live sink should receive skill progress while the stage is running");
      assertTrue(
          result.get().signals().stream().noneMatch(PipelineSignal.SkillProgress.class::isInstance),
          "drain batch must not replay skill progress that already went live");
    } finally {
      PipelineSignalLiveSink.unbind(RUN_ID);
    }
  }

  @Test
  void executionHaltRecordsTheFailedChildInsteadOfItsOrchestrator() {
    StageCapability failing = capability("fail-cap", context -> Multi.createFrom().items(
        new CapabilitySignal.SkillProgress("cip-design-executor", "running"),
        new CapabilitySignal.SkillProgress("cip-error-handling-generator", "running"),
        new CapabilitySignal.SkillProgress("cip-error-handling-generator", "error"),
        new CapabilitySignal.SkillProgress("cip-design-executor", "error"),
        new CapabilitySignal.Completed(StageOutcome.of(
            StageOutcomeClass.INTERNAL_FAILURE, "capture failed"))));
    ProductPipelineProfile profile = retryProfile("design-execution", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    execute(runtime, "design-execution");

    assertTrue(requireRun().attempts().getLast().failureEvidence().contains(
        "\"stageErrorFailedSkillId\":\"cip-error-handling-generator\""));
  }

  @Test
  void lateValidationRepairReachesOnlyItsProducerOnTheNextAttempt() {
    List<String> repairPrompts = new ArrayList<>();
    StageCapability cap = capability("fail-cap", context -> {
      StageRepairEvidence repair = StageRepairEvidence.from(context);
      if (repair != null) {
        repairPrompts.add(repair.repairTextForSkill("cip-service-call-generator"));
        assertEquals("", repair.repairTextForSkill("cip-http-generator"));
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "repaired")));
      }
      return Multi.createFrom().items(
          new CapabilitySignal.SkillProgress("cip-design-executor", "running"),
          new CapabilitySignal.SkillProgress("cip-quality-validator", "running"),
          new CapabilitySignal.SkillProgress("cip-quality-validator", "completed"),
          new CapabilitySignal.SkillProgress("cip-service-call-generator", "running"),
          new CapabilitySignal.SkillProgress("cip-service-call-generator", "error"),
          new CapabilitySignal.SkillProgress("cip-design-executor", "error"),
          new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.VALIDATION_FAILURE,
              "Missing approved before.script", new RecoveryCause(RecoveryCauseCode.MISSING_REQUIRED_PROPERTY,
                  List.of(new PlanValidationFinding("MISSING_REQUIRED_PROPERTY", "Restore before.script", true)), ""))));
    });
    ProductPipelineProfile profile = retryProfile("design-execution", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, cap);
    startAndRecordInput(runtime, profile);

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    execute(runtime, "design-execution");

    assertEquals(1, repairPrompts.size());
    assertTrue(repairPrompts.getFirst().contains("before.script"));
    assertTrue(repairPrompts.getFirst().contains("preserving the approved requirements"));
  }

  @Test
  void skillProgressStaysInTheDrainBatchWhenTheLiveSinkIsUnbound() {
    StageCapability cap =
        capability(
            "only-cap",
            context ->
                Multi.createFrom()
                    .items(
                        new CapabilitySignal.SkillProgress("cip-http-generator", "running"),
                        new CapabilitySignal.SkillProgress("cip-http-generator", "completed"),
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "done"))));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "drain-skill",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "only",
                    "only-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("only", "CHAIN_MATERIALIZED"),
            List.of("only-cap"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, cap);
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "only");

    assertTrue(
        result.signals().stream().anyMatch(PipelineSignal.SkillProgress.class::isInstance),
        "without a live sink, skill progress must still reach the drain batch");
  }

  @Test
  void generatedContractFailureCannotBecomeAnAuthorInputQuestion() {
    var profile = retryProfile("design-input", "question-cap", new RetryPolicy(0, 1L));
    var cap = capability("question-cap", context -> Multi.createFrom().item(new CapabilitySignal.Completed(
        context.attributeAsString("userText") == null
            ? StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")
            : StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "Supply the missing generated property.",
                new org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause(
                    RecoveryCauseCode.GRAPH_STRUCTURE,
                    List.of(new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
                        "ELEMENT_INTENT_ORDER", "Restore the accepted element order.", true)), "")))));
    var runtime = newRuntime(profile, cap);
    startAndRecordInput(runtime, profile);

    var result = execute(runtime, "design-input");

    assertInstanceOf(StageDecision.Retry.class, result.decision());
    assertEquals(StageStatus.FAILED, requireRun().attempts().getLast().outcome());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertFalse(result.signals().stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance));
  }

  @Test
  void missingRequiredInputReturnsWaitForInputWithoutInvokingTheCapability() {
    AtomicInteger capabilityCalls = new AtomicInteger();
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "missing-input",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "collect",
                    "needs-route",
                    List.of(
                        new ArtifactTypeRef("user-input", 1),
                        new ArtifactTypeRef("chain-semantic-revision", 1)),
                    List.of(new ArtifactTypeRef("requirement-brief", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("collect", "PLAN_APPROVED"),
            List.of("needs-route"));
    StageCapability capability = succeeding("needs-route", capabilityCalls);
    CreateChainTestOrchestrator runtime = newRuntime(profile, capability);
    runtime
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "hello"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    StageExecutionResult result = execute(runtime, "collect");

    assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
    StageDecision.WaitForInput wait = (StageDecision.WaitForInput) result.decision();
    assertEquals("collect", wait.stageId());
    assertTrue(wait.prompt().contains("chain-semantic-revision"));
    assertEquals(0, capabilityCalls.get());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    assertEquals(StageStatus.WAITING_FOR_INPUT, stageStatus(doc, "collect"));
    assertFalse(doc.attempts().isEmpty());
  }

  @Test
  void replayingAWaitingInputStageMustNotAdvanceToTheNextStage() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    ProductPipelineProfile profile = twoStageSuccessProfile();
    StageCapability waitingFirst =
        capability(
            "flow-first",
            context -> {
              firstCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.NEEDS_INPUT,
                              "__GATE:ids-path-choice__choose IDS")));
            });
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, waitingFirst, succeeding("flow-second", secondCalls));
    startAndRecordInput(runtime, profile);

    StageExecutionResult first = execute(runtime, "first");
    assertInstanceOf(StageDecision.WaitForInput.class, first.decision());
    assertEquals("first", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(1, firstCalls.get());

    StageExecutionResult replay = execute(runtime, "first");
    runtime
        .support()
        .applyStageLifecycle(RUN_ID, replay)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertInstanceOf(StageDecision.WaitForInput.class, replay.decision());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals("first", doc.run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    assertEquals(1, firstCalls.get());
    assertEquals(0, secondCalls.get());
  }

  @Test
  void candidateWritesProvenanceAttemptEvidenceAndWaitForApproval() {
    ProductPipelineProfile profile = twoStageApprovalProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile, FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "collect");

    StageDecision.WaitForApproval wait =
        assertInstanceOf(StageDecision.WaitForApproval.class, result.decision());
    assertEquals("collect", wait.stageId());
    assertNotNull(wait.candidate());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, doc.run().status());
    assertEquals("collect", doc.run().currentStageId());
    StageAttempt latest = doc.attempts().get(doc.attempts().size() - 1);
    assertEquals("collect", latest.stageId());
    assertEquals(StageStatus.WAITING_FOR_APPROVAL, latest.outcome());
    assertFalse(latest.outputs().isEmpty());
    Revision brief = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow();
    assertEquals(RUN_ID, brief.provenance().runId());
    assertEquals("collect", brief.provenance().stageId());
    assertEquals("fake-collector", brief.provenance().capabilityId());
    assertEquals(profile.profileId(), brief.provenance().profileId());
  }

  @Test
  void skipPolicyReturnsContinueWithoutInvokingTheSkippedCapability() {
    AtomicInteger routeCalls = new AtomicInteger();
    AtomicInteger skippedCalls = new AtomicInteger();
    AtomicInteger doneCalls = new AtomicInteger();
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "skip-route",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "route",
                    "route-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "skipped",
                    "skipped-cap",
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    new SkipPolicy(List.of(SkipPolicy.NO_APIHUB_CANDIDATE))),
                new ProfileStage(
                    "done",
                    "done-cap",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("done", "PLAN_APPROVED"),
            List.of("route-cap", "skipped-cap", "done-cap"));
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            routeDraft("route-cap", routeCalls),
            succeeding("skipped-cap", skippedCalls),
            succeeding("done-cap", doneCalls));
    startAndRecordInput(runtime, profile);
    runtime.executeStage(RUN_ID, "route").collect().asList().await().indefinitely();
    assertEquals("skipped", requireRun().run().currentStageId());
    Reference sourceDraft = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_DRAFT).orElseThrow().reference();

    StageExecutionResult result = execute(runtime, "skipped");

    assertInstanceOf(StageDecision.Continue.class, result.decision());
    assertEquals(1, routeCalls.get());
    assertEquals(0, skippedCalls.get());
    assertEquals(0, doneCalls.get());
    assertEquals("skipped", requireRun().run().currentStageId());
    assertEquals(StageStatus.SUCCEEDED, stageStatus(requireRun(), "skipped"));
    assertEquals(List.of(sourceDraft), artifactStore.latest(RUN_ID, Kind.REQUIREMENT_DRAFT).orElseThrow().inputs());
  }

  @Test
  void bypassWritesArtifactAndReturnsContinueWithoutSelectingTheNextStage() {
    AtomicInteger waitCalls = new AtomicInteger();
    AtomicInteger nextCalls = new AtomicInteger();
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "bypass-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "wait",
                    "wait-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "bypass",
                    null,
                    List.of(),
                    List.of(new ArtifactTypeRef("ids-bypass", 1)),
                    null,
                    new BypassPolicy(new ArtifactTypeRef("ids-bypass", 1)),
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "after",
                    "after-cap",
                    List.of(new ArtifactTypeRef("ids-bypass", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("after", "PLAN_APPROVED"),
            List.of("wait-cap", "after-cap"));
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, succeeding("wait-cap", waitCalls), succeeding("after-cap", nextCalls));
    startAndRecordInput(runtime, profile);
    runtime.executeStage(RUN_ID, "wait").collect().asList().await().indefinitely();
    assertEquals("bypass", requireRun().run().currentStageId());

    StageExecutionResult result = execute(runtime, "bypass");

    assertInstanceOf(StageDecision.Continue.class, result.decision());
    assertEquals(1, waitCalls.get());
    assertEquals(0, nextCalls.get());
    assertEquals("bypass", requireRun().run().currentStageId());
    Revision bypass = artifactStore.latest(RUN_ID, Kind.IDS_BYPASS).orElseThrow();
    assertEquals(RUN_ID, bypass.provenance().runId());
    assertEquals("bypass", bypass.provenance().stageId());
    assertEquals(StageStatus.SUCCEEDED, stageStatus(requireRun(), "bypass"));
  }

  @Test
  void interruptedAnalysisUsesTheExistingRetryBudgetAndThenGetsANewAttempt() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability analysis = capability("requirement-analysis", context -> {
      calls.incrementAndGet();
      assertNotEquals("interrupted", context.attemptId());
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "review requirements")));
    });
    var profile = retryProfile("analysis", "requirement-analysis", new RetryPolicy(1, 1L));
    var runtime = newRuntime(profile, analysis);
    startAndRecordInput(runtime, profile);
    var doc = requireRun();
    long revision = doc.run().runRevision();
    runStore.commit(revision, new org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit(
        RUN_ID, revision, RunStatus.RUNNING, "analysis", doc.run().stages(),
        new StageAttempt("interrupted", "analysis", revision + 1, StageStatus.RUNNING,
            FIXED, null, List.of(), null),
        new org.qubership.integration.platform.ai.productpipeline.store.RunTransition(revision, revision + 1,
            RunStatus.RUNNING, RunStatus.RUNNING, "analysis", FIXED, "analysis attempt started")));
    var interrupted = execute(runtime, "analysis");
    assertInstanceOf(StageDecision.Retry.class, interrupted.decision());
    assertEquals(0, calls.get());
    applyLifecycle(runtime, interrupted);
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "analysis").decision());
    assertEquals(1, calls.get());
  }

  @Test
  void retryableFailureReturnsRetryWithoutSleepingOrReinvoking() {
    AtomicInteger attempts = new AtomicInteger();
    StageCapability flaky =
        capability(
            "retry-cap",
            context -> {
              attempts.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                              List.of(),
                              "temporary failure",
                              5_000L)));
            });
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "retry-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "work",
                    "retry-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(1, 5_000L))),
            new TerminalPolicy("work", "PLAN_APPROVED"),
            List.of("retry-cap"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, flaky);
    startAndRecordInput(runtime, profile);

    Instant started = Instant.now();
    StageExecutionResult result = execute(runtime, "work");
    Duration elapsed = Duration.between(started, Instant.now());

    StageDecision.Retry retry = assertInstanceOf(StageDecision.Retry.class, result.decision());
    assertEquals("work", retry.stageId());
    assertEquals(Duration.ofMillis(5_000L), retry.delay());
    assertEquals(1, attempts.get());
    assertTrue(elapsed.toMillis() < 1_000L, "stage module must not sleep for retry");
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("work", requireRun().run().currentStageId());
    assertEquals(5_000L, profile.stages().get(0).retry().defaultDelayMs());
  }

  @Test
  void outcomeProvidedRetryDelayOverridesProfileDefaultWithoutChangingThePinnedProfile() {
    RetryPolicy retry = new RetryPolicy(1, 5_000L);
    StageCapability flaky =
        capability(
            "retry-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                List.of(),
                                "temporary failure",
                                50L))));
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", retry);
    CreateChainTestOrchestrator runtime = newRuntime(profile, flaky);
    startAndRecordInput(runtime, profile);

    StageDecision.Retry result =
        assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());

    assertEquals(Duration.ofMillis(50L), result.delay());
    assertEquals(5_000L, profile.stages().get(0).retry().defaultDelayMs());
    assertEquals(1, profile.stages().get(0).retry().maxTechnicalRetries());
  }

  @Test
  void retryableFailuresAreDurableWhileTheRunStaysRecoverable() {
    AtomicInteger attempts = new AtomicInteger();
    StageCapability alwaysFail =
        capability(
            "retry-cap",
            context -> {
              attempts.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                              List.of(),
                              "persistent transport failure",
                              1L)));
            });
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, alwaysFail);
    startAndRecordInput(runtime, profile);

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());
    ProductPipelineRunDocument afterFirstRetry = requireRun();
    assertEquals(RunStatus.RUNNING, afterFirstRetry.run().status());
    assertEquals(StageStatus.RUNNING, stageStatus(afterFirstRetry, "work"));
    assertEquals(StageStatus.FAILED, afterFirstRetry.attempts().getLast().outcome());
    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(2, attempts.get());
    assertEquals("work", wait.stageId());
    assertEquals(
        PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("persistent transport failure", PipelineGates.strip(wait.prompt()));
    assertEquals(
        "persistent transport failure",
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow());
    assertEquals(1L, PipelineGates.recoveryRetryDelayMsOf(wait.prompt()).orElseThrow());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    StageAttempt latest = doc.attempts().get(doc.attempts().size() - 1);
    assertEquals(StageStatus.WAITING_FOR_INPUT, latest.outcome());
    assertEquals(
        2,
        doc.attempts().stream().filter(attempt -> attempt.outcome() == StageStatus.FAILED).count());
    assertEquals(
        List.of("persistent transport failure", "persistent transport failure"),
        doc.attempts().stream()
            .filter(attempt -> attempt.outcome() == StageStatus.FAILED)
            .map(StageAttempt::failureEvidence)
            .toList());
  }

  @Test
  void restartRestoresTheDurableTechnicalRetryCountBeforeExhaustingIt() {
    StageCapability alwaysFail =
        capability(
            "retry-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                List.of(),
                                "persistent transport failure",
                                1L))));
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator first = newRuntime(profile, alwaysFail);
    startAndRecordInput(first, profile);

    assertInstanceOf(StageDecision.Retry.class, execute(first, "work").decision());

    CreateChainTestOrchestrator restarted = newRuntime(profile, alwaysFail);
    restarted
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertInstanceOf(StageDecision.WaitForInput.class, execute(restarted, "work").decision());
    assertEquals(
        2,
        requireRun().attempts().stream()
            .filter(attempt -> attempt.outcome() == StageStatus.FAILED)
            .count());
  }

  @Test
  void contractPolicyDomainValidationAndMissingInputDoNotEnterTechnicalRetry() {
    for (StageOutcomeClass outcomeClass :
        List.of(
            StageOutcomeClass.CONTRACT_FAILURE,
            StageOutcomeClass.POLICY_FAILURE,
            StageOutcomeClass.DOMAIN_FAILURE,
            StageOutcomeClass.VALIDATION_FAILURE,
            StageOutcomeClass.MISSING_MANDATORY_INPUT)) {
      blobStore = new InMemoryArtifactBlobStore();
      Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
      runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
      artifactStore =
          new ProductPipelineArtifactStore(new CompilationArtifacts(blobStore, mapper, clock));
      StageCapability failing =
          capability(
              "fail-cap",
              context ->
                  Multi.createFrom()
                      .item(
                          new CapabilitySignal.Completed(
                              StageOutcome.of(outcomeClass, outcomeClass.name() + " closed"))));
      ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
      CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
      startAndRecordInput(runtime, profile);

      StageExecutionResult result = execute(runtime, "work");

      if (outcomeClass == StageOutcomeClass.CONTRACT_FAILURE
          || outcomeClass == StageOutcomeClass.VALIDATION_FAILURE) {
        StageDecision.Retry retry =
            assertInstanceOf(StageDecision.Retry.class, result.decision());
        assertEquals(
            Duration.ZERO,
            retry.delay(),
            "Semantic repair is not a technical retry");
        assertFalse(retry.technicalRetry());
        continue;
      }
      assertFalse(
          result.decision() instanceof StageDecision.Retry,
          outcomeClass + " must not enter technical retry");
      StageDecision.WaitForInput wait =
          assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
      String gate = PipelineGates.gateOf(wait.prompt()).orElseThrow();
      if (outcomeClass == StageOutcomeClass.DOMAIN_FAILURE) {
        assertEquals(PipelineGates.STAGE_REVISE, gate, outcomeClass.name());
      } else if (outcomeClass == StageOutcomeClass.POLICY_FAILURE) {
        assertEquals(PipelineGates.RECOVERY_ENVIRONMENT, gate, outcomeClass.name());
      } else if (outcomeClass == StageOutcomeClass.VALIDATION_FAILURE) {
        assertEquals(PipelineGates.RECOVERY_UNCLASSIFIED, gate, outcomeClass.name());
      } else {
        assertEquals(PipelineGates.STAGE_RETRY, gate, outcomeClass.name());
      }
      if (outcomeClass == StageOutcomeClass.VALIDATION_FAILURE) {
        assertEquals(
            ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
            PipelineGates.strip(wait.prompt()),
            wait.prompt());
      } else {
        assertTrue(
            PipelineGates.strip(wait.prompt()).contains(outcomeClass.name() + " closed"),
            wait.prompt());
      }
      assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    }
  }

  /** Nothing classified the throwable, so it is an internal defect the author cannot repair. */
  @Test
  void aCapabilityThatFailsItsStreamHaltsAsAnInternalFailureInsteadOfThrowing() {
    StageCapability failing =
        capability(
            "fail-cap",
            context -> Multi.createFrom().failure(new IllegalStateException("catalog lookup broke")));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "work");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
    assertEquals("work", wait.stageId());
    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.INTERNAL_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("catalog lookup broke"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains(RUN_ID));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("catalog lookup broke"));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow().contains(RUN_ID));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("progress=halted"));
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_INTERNAL));
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    StageAttempt latest = doc.attempts().get(doc.attempts().size() - 1);
    assertEquals("work", latest.stageId());
    assertEquals(StageStatus.WAITING_FOR_INPUT, latest.outcome());
    assertEquals(
        1,
        doc.attempts().stream().filter(attempt -> attempt.outcome() == StageStatus.FAILED).count());
  }

  /** Retry re-enters the same defect, so the internal-failure card offers no Retry. */
  @Test
  void aCapabilityEmittingTwoCompletedSignalsHaltsWithoutOfferingRetry() {
    StageCapability doubleCompleted =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .items(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "first")),
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "second"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, doubleCompleted);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_INTERNAL));
    assertEquals(ProductPipelineStageExecutor.INTERNAL_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("exactly one Completed signal"));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow().contains(RUN_ID));
    assertTrue(PipelineGates.isRecoverableHaltGate(PipelineGates.RECOVERY_INTERNAL));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
  }

  @Test
  void missingMappingSchemaHaltsAsAnInternalFailureWithoutRetry() {
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .failure(
                        new IllegalStateException(
                            "No persisted mapping schema for kafka-trigger-1 REQUEST")));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.INTERNAL_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("IllegalStateException"));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("No persisted mapping schema"));
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_INTERNAL)
            .contains(ChatEvent.RETRY_CREATION_ACTION));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.RECOVERY_INTERNAL,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
  }

  @Test
  void anInternalFailureDoesNotOfferStageSelectionOrReopen() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("A step inside the service broke.", "analysis");
    StageCapability failing =
        capability(
            "planning-cap",
            context -> Multi.createFrom().failure(new IllegalStateException("catalog lookup broke")));
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), failing);
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("analysis"), wait.prompt());
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_INTERNAL));
  }

  @Test
  void anAmbiguousInternalFailureDoesNotKeepUpstreamStagesOnTheCard() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.ask("Either upstream artifact could route around the defect.");
    StageCapability failing =
        capability(
            "planning-cap",
            context -> Multi.createFrom().failure(new IllegalStateException("catalog lookup broke")));
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenDesignThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            designCandidate(),
            failing);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "analysis");
    approveStage(runtime, "design");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("analysis"), wait.prompt());
    assertFalse(wait.prompt().contains("design"), wait.prompt());
  }

  /**
   * A contract rejection still diagnoses the current owner. After the automatic semantic repair
   * spends the budget, Retry is no longer offered.
   */
  @Test
  void aContractRejectionKeepsItsClassAndDropsRetryWhenRepairsAreSpent() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The reply did not match the contract.", "work");
    StageCapability rejecting =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE, "reply is missing a field"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(1, 25L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, rejecting);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait = waitAfterOptionalSemanticRepair(runtime, "work");

    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE.name(), agent.lastOutcome.get());
    assertFalse(agent.lastException.get().contains(RUN_ID));
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED)
            .contains(PipelineGates.RETRY_ACTION));
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED)
            .contains(ChatEvent.RETRY_CREATION_ACTION));
  }

  @Test
  void contextOverflowGetsOneFreshTechnicalAttemptBeforeRecoverableHalt() {
    StageCapability failing = capability("fail-cap", context -> Multi.createFrom().failure(
        new org.qubership.integration.platform.ai.llm.ratelimit.LlmRequestBudget.Overflow(
            "LLM_CONTEXT_OVERFLOW: hash=original-packet")));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 25L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());
    StageDecision.WaitForInput halted =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(halted.prompt()).orElseThrow());
    assertTrue(halted.prompt().contains("LLM_CONTEXT_OVERFLOW"));
  }

  @Test
  void resourceExhaustionPreservesTheRunAndRequiresAnExplicitAdditionalAllowance() {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var capability = capability("budget-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      budget.reserve(context.runId(), 10, 10);
      budget.reserve(context.runId(), 10, 10);
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.SUCCEEDED, "")));
    });
    var profile = retryProfile("work", "budget-cap", new RetryPolicy(2, 1L));
    var runtime = newRuntime(profile, capability);
    runtime.support().withLlmBudget(budget);
    startAndRecordInput(runtime, profile);
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(wait.prompt().contains("additional model allowance"));
    assertEquals(1, budget.state(RUN_ID).requests());
    runtime.recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect().asList().await().indefinitely();
    assertEquals(0, budget.state(RUN_ID).grants());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    var retry = new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION, "author-allowance-1", "grant-payload",
        org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED);
    runtime.recordInput(retry).collect().asList().await().indefinitely();
    runtime.recordInput(retry).collect().asList().await().indefinitely();
    assertEquals(1, budget.state(RUN_ID).grants());
    assertEquals(RunStatus.RUNNING, runStore.load(RUN_ID).orElseThrow().run().status());
    assertEquals(1, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
  }

  @Test
  void exhaustedBudgetStopsTheNextCapabilityBeforeAnyCatalogEffect() {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    AtomicInteger effects = new AtomicInteger();
    var capability = capability("budget-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      effects.incrementAndGet();
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.SUCCEEDED, "")));
    });
    var profile = retryProfile("work", "budget-cap", new RetryPolicy(2, 1L));
    var runtime = newRuntime(profile, capability);
    runtime.support().withLlmBudget(budget);
    startAndRecordInput(runtime, profile);
    budget.reserve(RUN_ID, 10, 10);
    assertThrows(org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget.Exhausted.class,
        () -> budget.reserve(RUN_ID, 10, 10));
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(0, effects.get());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(1, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
  }

  @Test
  void narrativeExhaustionProducesTheResourceGateInsteadOfAnAuthorFault() {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
          budget.reserve(RUN_ID, 1, 1);
          budget.reserve(RUN_ID, 1, 1);
          return null;
        });
    var capability = capability("budget-cap", context -> Multi.createFrom().item(new CapabilitySignal.Completed(
        StageOutcome.of(context.attributeAsString("userText") == null ? StageOutcomeClass.NEEDS_INPUT
            : StageOutcomeClass.CONTRACT_FAILURE, "Invalid generated shape"))));
    var profile = retryProfile("design-planning", "budget-cap", new RetryPolicy(0, 1L));
    var telemetry = new org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry();
    var runtime = new CreateChainTestOrchestrator(ProductPipelineRunSupport.builder(runStore, artifactStore,
        new StageCapabilityRegistry(List.of(capability)), Clock.fixed(FIXED, ZoneOffset.UTC))
        .failureNarrative(new FailureNarrative(agent)).recoveryTelemetry(telemetry).build(), runStore);
    runtime.support().withLlmBudget(budget);
    startAndRecordInput(runtime, profile);
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-planning").decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(wait.prompt().contains("additional model allowance"));
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.atLeastOnce()).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    assertEquals(1, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
    var halt = ProductPipelineRunSupport.readHaltEvidence(requireRun().attempts().getLast().failureEvidence());
    String currentReason = PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow();
    assertEquals(currentReason, halt.get(ProductPipelineRunSupport.CURRENT_HALT_REASON_ATTR));
    assertTrue(halt.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR).toString()
        .contains("Invalid generated shape"));
    assertEquals(org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints.failureSignature(currentReason),
        telemetry.events().getLast().failureIdentity());
  }

  @Test
  void approvalNarrationExhaustionKeepsAcceptedArtifactsAndTheNewCandidate() {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var prompts = org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts.withFixedPrompts(
        (stage, reference) -> {
          if ("design-planning".equals(stage)) {
            budget.reserve(RUN_ID, 1, 1);
            budget.reserve(RUN_ID, 1, 1);
          }
          return "Approve the saved candidate.";
        }, reference -> "Create the chain.");
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = new CreateChainTestOrchestrator(ProductPipelineRunSupport.builder(runStore, artifactStore,
        new StageCapabilityRegistry(List.of(analysisCandidate(), planningAlwaysCandidate())),
        Clock.fixed(FIXED, ZoneOffset.UTC)).approvalPrompts(prompts).build(), runStore);
    runtime.support().withLlmBudget(budget);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    var approved = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow();
    var approval = artifactStore.latest(RUN_ID, Kind.APPROVAL_RECORD).orElseThrow();
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-planning").decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    var candidate = artifactStore.latest(RUN_ID, Kind.IMPLEMENTATION_PLAN).orElseThrow();
    var snapshot = snapshot(requireRun(), "design-planning");
    assertTrue(snapshot.candidateReferences().contains(candidate.reference()));
    assertEquals(candidate.reference(), snapshot.approvableReference());
    var retry = new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION, "grant-after-approval-narration",
        "resource-grant", org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED);
    runtime.recordInput(retry).collect().asList().await().indefinitely();
    assertEquals(approved, artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow());
    assertEquals(approval, artifactStore.latest(RUN_ID, Kind.APPROVAL_RECORD).orElseThrow());
    assertEquals(candidate, artifactStore.latest(RUN_ID, Kind.IMPLEMENTATION_PLAN).orElseThrow());
    assertEquals(1, budget.state(RUN_ID).grants());
  }

  @Test
  void discoveryContextOverflowUsesOneBoundedRetryUnderTheActualProfile() throws Exception {
    ProductPipelineProfile production;
    try (var input = getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      production = ProductPipelineProfileParser.parse(input);
    }
    ProfileStage discovery = production.stages().stream()
        .filter(stage -> "requirement-discovery".equals(stage.stageId())).findFirst().orElseThrow();
    assertEquals(2, discovery.retry().maxTechnicalRetries());
    ProductPipelineProfile profile = retryProfile(
        discovery.stageId(), discovery.capabilityId(), discovery.retry());
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> authorText = new AtomicReference<>();
    StageCapability failing = capability(discovery.capabilityId(), context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      calls.incrementAndGet();
      authorText.set(context.attributeAsString("userText"));
      return Multi.createFrom().failure(
          new org.qubership.integration.platform.ai.llm.ratelimit.LlmRequestBudget.Overflow(
              "LLM_CONTEXT_OVERFLOW: hash=discovery-packet"));
    });
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    assertTrue(assertInstanceOf(StageDecision.Retry.class,
        execute(runtime, discovery.stageId()).decision()).technicalRetry());
    assertEquals(1, calls.get());
    CreateChainTestOrchestrator restarted = newRuntime(profile, failing);
    restarted.startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    StageDecision.WaitForInput halted = assertInstanceOf(StageDecision.WaitForInput.class,
        execute(restarted, discovery.stageId()).decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(halted.prompt()).orElseThrow());
    assertTrue(halted.prompt().contains("LLM_CONTEXT_OVERFLOW"));
    assertEquals(2, calls.get());
    assertEquals("provided input", authorText.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ids-entry", "requirement-discovery", "requirement-analysis",
      "design-input", "design-planning", "design-execution"})
  void preCatalogStagesRecoverTwoTemporaryFailuresUnderTheActualProfile(String stageId) throws Exception {
    ProductPipelineProfile production;
    try (var input = getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      production = ProductPipelineProfileParser.parse(input);
    }
    ProfileStage stage = production.stages().stream()
        .filter(candidate -> stageId.equals(candidate.stageId())).findFirst().orElseThrow();
    ProductPipelineProfile profile = retryProfile(stageId, stage.capabilityId(), stage.retry());
    AtomicInteger calls = new AtomicInteger();
    StageCapability flaky = capability(stage.capabilityId(), context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      assertEquals("provided input", context.attributeAsString("userText"));
      if (calls.incrementAndGet() <= 2) {
        return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Temporary failure recovered.")));
    });
    CreateChainTestOrchestrator first = newRuntime(profile, flaky);
    startAndRecordInput(first, profile);
    long inputRevision = requireRun().run().runRevision();
    assertTrue(assertInstanceOf(StageDecision.Retry.class,
        execute(first, stageId).decision()).technicalRetry());
    CreateChainTestOrchestrator second = newRuntime(profile, flaky);
    second.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    assertTrue(assertInstanceOf(StageDecision.Retry.class,
        execute(second, stageId).decision()).technicalRetry());
    CreateChainTestOrchestrator third = newRuntime(profile, flaky);
    third.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    assertInstanceOf(StageDecision.Complete.class, execute(third, stageId).decision());
    assertEquals(3, calls.get());
    assertEquals(RunStatus.PLAN_APPROVED, requireRun().run().status());
    assertTrue(requireRun().transitions().stream().noneMatch(transition ->
        transition.fromRevision() >= inputRevision && transition.toStatus() == RunStatus.WAITING_FOR_INPUT));
  }

  @Test
  void staleFlowTaskCannotRefundATechnicalRetryAfterRuntimeReconstruction() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability failing = capability("retry-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      calls.incrementAndGet();
      return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
    });
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator first = newRuntime(profile, failing);
    startAndRecordInput(first, profile);
    assertInstanceOf(StageDecision.Retry.class, execute(first, "work").decision());
    CreateChainTestOrchestrator restarted = newRuntime(profile, failing);
    restarted.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    var tasks = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks(
        restarted.support());
    var stale = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow.RunContext(
        RUN_ID, profile.profileId(), profile.profileVersion(), "manifest", "CONTINUE", 0, null);

    assertEquals("WAIT_FOR_INPUT", tasks.executeCurrentStage(stale).decision());
    assertEquals(2, calls.get());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
  }

  @Test
  void staleRetryCountFromThePreviousStageCannotConsumeTheNextStagesBudget() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    StageCapability firstCapability = capability("first-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      return firstCalls.incrementAndGet() == 1
          ? Multi.createFrom().failure(new java.net.ConnectException("Connection refused"))
          : Multi.createFrom().item(new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.SUCCEEDED, "First stage completed.")));
    });
    StageCapability secondCapability = capability("second-cap", context ->
        secondCalls.incrementAndGet() == 1
            ? Multi.createFrom().failure(new java.net.ConnectException("Connection refused"))
            : Multi.createFrom().item(new CapabilitySignal.Completed(
                StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Second stage completed."))));
    ProductPipelineProfile profile = new ProductPipelineProfile(1, "stage-replay", "1",
        List.of(new ArtifactTypeRef("user-input", 1)), List.of(
            new ProfileStage("first", "first-cap", List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(), null, null, new RetryPolicy(1, 1L)),
            new ProfileStage("second", "second-cap", List.of(), List.of(), null, null, new RetryPolicy(1, 1L))),
        new TerminalPolicy("second", "PLAN_APPROVED"), List.of("first-cap", "second-cap"));
    CreateChainTestOrchestrator first = newRuntime(profile, firstCapability, secondCapability);
    startAndRecordInput(first, profile);
    assertInstanceOf(StageDecision.Retry.class, execute(first, "first").decision());
    applyLifecycle(first, execute(first, "first"));
    CreateChainTestOrchestrator restarted = newRuntime(profile, firstCapability, secondCapability);
    restarted.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    var tasks = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks(
        restarted.support());
    var stale = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow.RunContext(
        RUN_ID, profile.profileId(), profile.profileVersion(), "manifest", "CONTINUE", 1, "PT0.001S");

    var retry = tasks.executeCurrentStage(stale);
    assertEquals("RETRY", retry.decision());
    assertEquals(1, retry.technicalRetriesUsed());
    assertEquals("STOP", tasks.executeCurrentStage(tasks.restoreAfterRetry(retry)).decision());
    assertEquals(2, firstCalls.get());
    assertEquals(2, secondCalls.get());
    assertEquals(RunStatus.PLAN_APPROVED, requireRun().run().status());
  }

  @Test
  void completedStageReplaysItsTerminalDecisionWithoutAnotherLifecycleCommit() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability completing = capability("work-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      calls.incrementAndGet();
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Completed before Flow checkpoint.")));
    });
    ProductPipelineProfile profile = retryProfile("work", "work-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator first = newRuntime(profile, completing);
    startAndRecordInput(first, profile);
    assertInstanceOf(StageDecision.Complete.class, execute(first, "work").decision());
    ProductPipelineRunDocument committed = requireRun();
    CreateChainTestOrchestrator restarted = newRuntime(profile, completing);
    restarted.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    var tasks = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks(
        restarted.support());
    var stale = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow.RunContext(
        RUN_ID, profile.profileId(), profile.profileVersion(), "manifest", "CONTINUE", 0, null);

    assertEquals("STOP", tasks.executeCurrentStage(stale).decision());
    assertEquals(1, calls.get());
    assertEquals(committed.run(), requireRun().run());
    assertEquals(committed.attempts(), requireRun().attempts());
    assertEquals(committed.transitions(), requireRun().transitions());
  }

  @Test
  void acceptedTechnicalRetryStartsOneNewDurableCycleAfterReconstruction() {
    StageCapability failing = capability("retry-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
    });
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator first = newRuntime(profile, failing);
    startAndRecordInput(first, profile);
    assertInstanceOf(StageDecision.Retry.class, execute(first, "work").decision());
    assertInstanceOf(StageDecision.WaitForInput.class, execute(first, "work").decision());
    first.recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect().asList().await().indefinitely();
    CreateChainTestOrchestrator restarted = newRuntime(profile, failing);
    restarted.restoreForExternalWorkflow(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();
    var tasks = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks(
        restarted.support());
    var stale = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow.RunContext(
        RUN_ID, profile.profileId(), profile.profileVersion(), "manifest", "CONTINUE", 1, "PT0.001S");

    var retry = tasks.executeCurrentStage(stale);
    assertEquals("RETRY", retry.decision());
    assertEquals(1, retry.technicalRetriesUsed());
    assertEquals("WAIT_FOR_INPUT", tasks.executeCurrentStage(tasks.restoreAfterRetry(retry)).decision());
  }

  @Test
  void semanticRepairLeavesTransportCreditAvailableAfterRuntimeReconstruction() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability capability = capability("work-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      return switch (calls.incrementAndGet()) {
        case 1 -> Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "The generated reply is missing a field.")));
        case 2 -> Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
        default -> Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Output repaired after transport recovery.")));
      };
    });
    ProductPipelineProfile profile = retryProfile("work", "work-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, capability);
    startAndRecordInput(runtime, profile);
    assertFalse(assertInstanceOf(StageDecision.Retry.class,
        execute(runtime, "work").decision()).technicalRetry());

    CreateChainTestOrchestrator restarted = newRuntime(profile, capability);
    restarted.startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect().asList().await().indefinitely();

    assertEquals(3, calls.get());
    assertEquals(RunStatus.PLAN_APPROVED, requireRun().run().status());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "work").status());
  }

  @Test
  void aStreamFailedWithBadToolArgumentsStillTakesTechnicalRetry() {
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .failure(
                        new ToolArgumentsException("cannot parse tool arguments", (Throwable) null)));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(1, 25L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.Retry retry =
        assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());

    assertEquals("work", retry.stageId());
    assertEquals(Duration.ofMillis(25L), retry.delay());
  }

  @Test
  void aStreamFailedWithConnectionRefusedRetriesThenWaitsWhenTheBudgetIsSpent() {
    StageCapability failing =
        capability(
            "fail-cap",
            context -> Multi.createFrom().failure(new java.net.ConnectException("Connection refused")));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(1, 25L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());
    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals("work", wait.stageId());
    assertEquals(
        PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("Connection refused", PipelineGates.strip(wait.prompt()));
    assertEquals(25L, PipelineGates.recoveryRetryDelayMsOf(wait.prompt()).orElseThrow());
  }

  @Test
  void aValidationOutcomeRepairsItsProducerWithoutOwnerGuessingOrTechnicalRetry() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.owner("Invalid plan.", "work");
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.VALIDATION_FAILURE, "invalid plan"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(1, 25L));
    CreateChainTestOrchestrator runtime = newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.Retry retry =
        assertInstanceOf(StageDecision.Retry.class, execute(runtime, "work").decision());
    assertEquals("work", retry.stageId());
    assertEquals(Duration.ZERO, retry.delay());
    assertFalse(retry.technicalRetry());
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
  }

  @Test
  void retriesShareTheSameExecutionKeyAndUseDistinctAttemptIds() {
    java.util.ArrayList<String> executionKeys = new java.util.ArrayList<>();
    java.util.ArrayList<String> attemptIds = new java.util.ArrayList<>();
    StageCapability flaky =
        capability(
            "retry-cap",
            context -> {
              executionKeys.add(context.executionKey());
              attemptIds.add(context.attemptId());
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                              List.of(),
                              "temporary failure",
                              1L)));
            });
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(1, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, flaky);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    execute(runtime, "work");

    assertEquals(2, executionKeys.size());
    assertEquals(executionKeys.get(0), executionKeys.get(1));
    assertNotEquals(attemptIds.get(0), attemptIds.get(1));
  }

  @Test
  void aSkippedGeneratorAndASubstitutedFallbackReachApprovalAsNonBlockerFindings() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenApprovedPlanningProfile(brief, plan, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), degradedPlanningCandidate());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageExecutionResult result = execute(runtime, "planning");

    StageDecision.WaitForApproval wait =
        assertInstanceOf(StageDecision.WaitForApproval.class, result.decision());
    assertEquals("planning", wait.stageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    PlanValidationResult approved =
        artifactStore.payload(
            artifactStore.latest(RUN_ID, Kind.PLAN_VALIDATION_RESULT).orElseThrow(),
            PlanValidationResult.class);
    assertTrue(approved.approvalEligible());
    assertEquals(
        List.of(PlanningDegradations.GENERATOR_SKIPPED, PlanningDegradations.FALLBACK_SUBSTITUTED),
        approved.findings().stream().map(PlanValidationFinding::code).toList());
    assertTrue(approved.findings().stream().noneMatch(PlanValidationFinding::blocker));
  }

  private StageCapability degradedPlanningCandidate() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.IMPLEMENTATION_PLAN, Map.of("plan", "ok"), List.of()),
                                new ArtifactCandidate(
                                    Kind.PLAN_VALIDATION_RESULT,
                                    new PlanValidationResult(
                                        List.of(
                                            PlanningDegradations.generatorSkipped(
                                                "cip-naming-generator"),
                                            PlanningDegradations.fallbackSubstituted(
                                                "cip-naming-generator", "NAMING_MANIFEST"))),
                                    List.of())),
                            "plan ready",
                            null))));
  }

  private static ProductPipelineProfile analysisThenApprovedPlanningProfile(
      ArtifactTypeRef brief, ArtifactTypeRef plan, ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "degraded-planning",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(plan, validation),
                new ApprovalPolicy(plan),
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("analysis-cap", "planning-cap"));
  }

  @Test
  void validationFailureRepairsWithoutReopeningThePreviousApproval() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    int briefCountBefore = artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size();
    StageExecutionResult result = execute(runtime, "planning");

    StageDecision.Retry retry = assertInstanceOf(StageDecision.Retry.class, result.decision());
    assertEquals("planning", retry.stageId());
    assertFalse(retry.technicalRetry());
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(briefCountBefore, artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size());
    assertTrue(artifactStore.latest(RUN_ID, Kind.PLAN_VALIDATION_RESULT).isPresent());
    StageAttempt haltAttempt = requireRun().attempts().get(requireRun().attempts().size() - 1);
    assertTrue(haltAttempt.failureEvidence().contains("\"stageErrorFailedStageId\":\"planning\""));
    assertTrue(haltAttempt.failureEvidence().contains("\"stageErrorOutcomeClass\":\"VALIDATION_FAILURE\""));
    assertTrue(haltAttempt.failureEvidence().contains("\"stageErrorContext\""));
    assertTrue(haltAttempt.failureEvidence().contains("\"stageErrorFindings\""));
    assertTrue(haltAttempt.failureEvidence().contains("\"diagnosedOwnerStageId\""));
    assertTrue(result.signals().stream().noneMatch(PipelineSignal.Failed.class::isInstance));
  }

  @Test
  void retryAtPlanningRewindsToAnalysisInsteadOfRerunningTheGeneratedPlan() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "retry-rewind",
            "1",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "analysis",
                    "analysis-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(brief),
                    new ApprovalPolicy(brief),
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "planning",
                    "planning-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(validation),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("planning", "PLAN_APPROVED"),
            List.of("analysis-cap", "planning-cap"));
    StageCapability failingPlanning =
        capability(
            "planning-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.MISSING_MANDATORY_INPUT,
                                "MISSING_MANDATORY_INPUT closed"))));
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), failingPlanning);
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    execute(runtime, "planning");
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.STAGE_RETRY,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "planning").status());
    assertTrue(snapshot(requireRun(), "planning").outputRefs().isEmpty());
  }

  @Test
  void retryWithALeftoverInlinedAttachmentRewindsLikeBareRetry() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "retry-rewind-attach",
            "1",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "analysis",
                    "analysis-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(brief),
                    new ApprovalPolicy(brief),
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "planning",
                    "planning-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(validation),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("planning", "PLAN_APPROVED"),
            List.of("analysis-cap", "planning-cap"));
    StageCapability failingPlanning =
        capability(
            "planning-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.MISSING_MANDATORY_INPUT,
                                "MISSING_MANDATORY_INPUT closed"))));
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), failingPlanning);
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    execute(runtime, "planning");
    assertEquals(
        PipelineGates.STAGE_RETRY,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());

    runtime
        .recordInput(
            new AcceptInputCommand(
                RUN_ID,
                "retry\n\n---\n\n- `Salesforce WFM.json` (inlined)\n\n{\"openapi\":\"3.0.0\"}"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
  }

  @Test
  void theSameNormalizedFailureEscalatesAfterTwoHaltsAndSurvivesRestart() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability repeating =
        capability(
            "fail-cap",
            context -> {
              String message =
                  calls.incrementAndGet() == 1
                      ? "invalid node 'first' [route-a]"
                      : "INVALID   NODE 'second' [route-b]";
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, message)));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, repeating);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput first =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(first.prompt()).orElseThrow());

    CreateChainTestOrchestrator restarted = newRuntime(profile, repeating);
    restarted
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();
    restarted
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    StageDecision.WaitForInput second =
        assertInstanceOf(
            StageDecision.WaitForInput.class,
            restarted
                .support()
                .stageExecutor()
                .execute(RUN_ID, "work")
                .await()
                .indefinitely()
                .decision());

    assertEquals(
        PipelineGates.RECOVERY_REPEATED,
        PipelineGates.gateOf(second.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(second.prompt()));
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED)
            .contains(PipelineGates.RETRY_ACTION));
    assertFalse(PipelineGates.dropElementAllowed(second.prompt()));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(second.prompt())
            .orElseThrow()
            .contains("progress=none"));
    assertTrue(
        requireRun().transitions().stream()
            .anyMatch(
                transition ->
                    PipelineGates.RECOVERY_REPEATED.equals(
                        PipelineGates.gateOf(transition.reason()).orElse(""))));
  }

  @Test
  void differentFailureSignaturesDoNotEscalate() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability changing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                calls.incrementAndGet() == 1
                                    ? "missing trigger"
                                    : "missing service call"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, changing);
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    StageDecision.WaitForInput second =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(second.prompt()).orElseThrow());
  }

  @Test
  void aChangedPlanningContractFailureGetsAnotherRepairAttempt() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability planning =
        capability(
            "planning-cap",
            context -> {
              int call = calls.incrementAndGet();
              if (call <= 2) {
                String message =
                    call == 1
                        ? "planner report missing producing step for serviceCallId=create-task"
                        : "serviceCallId=create-task has more than one producing step";
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, message)));
              }
              return planningAlwaysCandidate().execute(context);
            });
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            analysisCandidate(),
            planning,
            succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");

    StageExecutionResult first = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, first.decision());
    applyLifecycle(runtime, first);

    StageExecutionResult second = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, second.decision());
    applyLifecycle(runtime, second);

    assertInstanceOf(
        StageDecision.WaitForApproval.class, execute(runtime, "design-planning").decision());
    assertEquals(3, calls.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"stale", "untrusted", "different-run", "written", "ceiling"})
  void repairGrantsRespectTheirOfferAndOtherGuards(String guard) {
    var ledger = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(2, 2,
        "ceiling".equals(guard) ? 4 : 12, 2));
    StageCapability rejecting = capability("execution-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "Invalid capture shape."))));
    var profile = retryProfile("design-execution", "execution-cap", new RetryPolicy(0, 1L));
    var runtime = newRuntime(2, ledger, profile, rejecting);
    startAndRecordInput(runtime, profile);
    for (int correction = 0; correction < 4; correction++) {
      StageExecutionResult result = execute(runtime, "design-execution");
      assertInstanceOf(StageDecision.Retry.class, result.decision());
      applyLifecycle(runtime, result);
    }
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    var before = requireRun();
    if ("written".equals(guard)) {
      artifactStore.append(new CompilationArtifacts.AppendCommand(RUN_ID, Kind.CATALOG_CHAIN_SNAPSHOT, "1",
          "test-materializer", "1", new ChainCatalogFacts("written-chain", "Orders", "", 0, 0, "", List.of(),
              List.of(), "catalog"), List.of(), null,
          new org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance(
              RUN_ID, "catalog-materialization", profile.profileId(), profile.profileVersion(), "sha",
              "test-materializer", "1", "sha")));
    }
    String binding = "stale".equals(guard) || "different-run".equals(guard)
        ? PipelineGates.CONTINUE_REPAIR_ACTION + ":incorrect-binding"
        : ProductPipelineRunSupport.repairOfferCommand(before);
    runtime.support().recordInput(new AcceptInputCommand(RUN_ID, binding, "click", "request",
        "untrusted".equals(guard) ? InputOrigin.UNTRUSTED : InputOrigin.TRUSTED))
        .collect().asList().await().indefinitely();
    if ("written".equals(guard)) {
      assertEquals(before.transitions().size() + 1, requireRun().transitions().size());
      assertEquals(HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(),
          PipelineGates.guardOf(requireRun().transitions().getLast().reason()).orElseThrow());
      assertFalse(ChatEvent.actionsForGate(PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow())
          .contains(PipelineGates.CONTINUE_REPAIR_ACTION));
    } else {
      assertEquals(before.transitions(), requireRun().transitions());
    }
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
  }

  @Test
  void aResourceRefusalAtTheOfferRequiresSeparateResourceAndDefectGrants() {
    var ledger = new RecoveryAttemptLedger(RecoveryAttemptLedger.Limits.defaults());
    var calls = new AtomicInteger();
    StageCapability rejecting = capability("execution-cap", context -> {
      calls.incrementAndGet();
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "Invalid capture shape.")));
    });
    var profile = retryProfile("design-execution", "execution-cap", new RetryPolicy(0, 1L));
    var telemetry = new org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry();
    var runtime = new CreateChainTestOrchestrator(ProductPipelineRunSupport.builder(runStore, artifactStore,
        new StageCapabilityRegistry(List.of(rejecting)), Clock.fixed(FIXED, ZoneOffset.UTC))
        .repeatedFailureThreshold(2).recoveryLedger(ledger).recoveryTelemetry(telemetry).build(), runStore);
    startAndRecordInput(runtime, profile);
    for (int correction = 0; correction < 4; correction++) {
      StageExecutionResult result = execute(runtime, "design-execution");
      assertInstanceOf(StageDecision.Retry.class, result.decision());
      applyLifecycle(runtime, result);
    }
    execute(runtime, "design-execution");
    String offer = ProductPipelineRunSupport.repairOfferCommand(requireRun());
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    runtime.support().withLlmBudget(budget);
    budget.reserve(RUN_ID, 10, 10);

    runtime.support().recordInput(new AcceptInputCommand(RUN_ID, offer, "defect-click", "hash", InputOrigin.TRUSTED))
        .collect().asList().await().indefinitely();
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
    var halt = ProductPipelineRunSupport.readHaltEvidence(requireRun().attempts().getLast().failureEvidence());
    assertTrue(halt.get(ProductPipelineRunSupport.CURRENT_HALT_REASON_ATTR) instanceof String reason
        && reason.startsWith("LLM_RESOURCE_LIMIT:"));
    assertTrue(halt.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR).toString()
        .contains("Invalid capture shape"));
    String resourceIdentity = org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints
        .failureSignature(halt.get(ProductPipelineRunSupport.CURRENT_HALT_REASON_ATTR).toString());
    assertEquals(resourceIdentity, telemetry.events().getLast().failureIdentity());
    assertEquals("temporary-technical-failure", telemetry.events().getLast().category());
    assertEquals(resourceIdentity, PipelineGates.haltIdentityOf(
        requireRun().transitions().getLast().reason()).orElseThrow());
    runtime.support().recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION,
        "resource-click", "resource-hash", InputOrigin.TRUSTED)).collect().asList().await().indefinitely();
    assertEquals(PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
    String producerIdentity = org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints
        .failureSignature(halt.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR).toString());
    assertEquals(producerIdentity, telemetry.events().getLast().failureIdentity());
    assertEquals("repair-allowance-required", telemetry.events().getLast().category());
    assertEquals("presented", telemetry.events().getLast().kind());
    assertEquals(producerIdentity, PipelineGates.haltIdentityOf(
        requireRun().transitions().getLast().reason()).orElseThrow());
    assertEquals(5, calls.get());
    assertEquals(0, requireRun().transitions().stream().filter(t -> t.reason().startsWith(RecoveryAttemptLedger.GRANT_REASON_PREFIX)).count());
    assertEquals(1, budget.state(RUN_ID).grants());

    runtime.support().recordInput(new AcceptInputCommand(RUN_ID, ProductPipelineRunSupport.repairOfferCommand(requireRun()),
        "new-defect-click", "hash", InputOrigin.TRUSTED)).collect().asList().await().indefinitely();
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(1, requireRun().transitions().stream().filter(t -> t.reason().startsWith(RecoveryAttemptLedger.GRANT_REASON_PREFIX)).count());
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    assertEquals(6, calls.get());
  }

  @Test
  void aBoundedRepairOfferSurvivesDuplicateTransportIdsWithoutResettingHistory() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability rejecting = capability("execution-cap", context -> {
      calls.incrementAndGet();
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, "Invalid capture shape.")));
    });
    ProductPipelineProfile profile = retryProfile("design-execution", "execution-cap", new RetryPolicy(0, 1L));
    var ledger = new RecoveryAttemptLedger();
    CreateChainTestOrchestrator runtime = newRuntime(2, ledger, profile, rejecting);
    startAndRecordInput(runtime, profile);
    for (int correction = 0; correction < 4; correction++) {
      StageExecutionResult result = execute(runtime, "design-execution");
      assertInstanceOf(StageDecision.Retry.class, result.decision());
      applyLifecycle(runtime, result);
    }
    StageDecision.WaitForInput offer = assertInstanceOf(StageDecision.WaitForInput.class,
        execute(runtime, "design-execution").decision());
    assertEquals("recovery-repair-allowance", PipelineGates.gateOf(offer.prompt()).orElseThrow());
    assertTrue(PipelineGates.strip(offer.prompt()).contains("two targeted corrections"));
    assertTrue(PipelineGates.strip(offer.prompt()).contains("two clean regenerations"));
    String details = PipelineGates.recoveryTechnicalDetailsOf(offer.prompt()).orElseThrow();
    assertTrue(details.contains("Targeted corrections spent: 2."), details);
    assertTrue(details.contains("Clean regenerations spent: 2."), details);
    assertFalse(offer.prompt().contains("will not help"));
    String binding = ProductPipelineRunSupport.repairOfferCommand(requireRun());
    int history = requireRun().transitions().size();
    for (String transportId : List.of("click-one", "click-two")) {
      runtime.support().recordInput(new AcceptInputCommand(RUN_ID, binding, transportId,
          "same-bound-offer", InputOrigin.TRUSTED)).collect().asList().await().indefinitely();
    }
    assertEquals(history + 1, requireRun().transitions().size());
    assertEquals(1, requireRun().transitions().stream()
        .filter(t -> t.reason() != null && t.reason().startsWith("repair-grant:")) .count());
    assertEquals(5, calls.get());
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    assertEquals(6, calls.get());
    assertEquals(0, ledger.key("design-execution", RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE),
        RecoveryAttemptLedger.inputArtifactIdentity(requireRun(), "design-execution"),
        requireRun().transitions()).correctionEpoch());
  }

  @Test
  void executionDispatchesTwoTargetedAndTwoCleanCorrectionsBeforePausing() {
    AtomicInteger calls = new AtomicInteger();
    List<Boolean> cleanTurns = new ArrayList<>();
    StageCapability execution = capability("execution-cap", context -> {
      calls.incrementAndGet();
      StageRepairEvidence evidence = StageRepairEvidence.from(context);
      cleanTurns.add(evidence != null && evidence.recoveryEvidenceRef().equals(
          context.attributeAsString("producerCleanRebuildEvidenceRef")));
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, "The generated output could not be captured.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE,
              List.of(new PlanValidationFinding("CONTRACT_SHAPE", "Invalid capture shape.", true,
                  MappingValidationDetails.empty(), "http-trigger", "/properties")), ""))));
    });
    ProductPipelineProfile profile = retryProfile("design-execution", "execution-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(2, new RecoveryAttemptLedger(), profile, execution);
    startAndRecordInput(runtime, profile);

    for (int attempt = 0; attempt < 4; attempt++) {
      StageExecutionResult correction = execute(runtime, "design-execution");
      assertInstanceOf(StageDecision.Retry.class, correction.decision(), "Correction " + (attempt + 1));
      applyLifecycle(runtime, correction);
    }
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(5, calls.get());
    assertEquals(List.of(false, false, false, true, true), cleanTurns);
    assertEquals(2, requireRun().transitions().stream()
        .filter(t -> t.reason() != null && t.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX))
        .count());
    assertEquals(2, requireRun().transitions().stream()
        .filter(t -> t.reason() != null && t.reason().startsWith(RecoveryAttemptLedger.CLEAN_REBUILD_REASON_PREFIX))
        .count());
  }

  @Test
  void aRepeatedDesignInputFailureUsesOneCleanRebuildBeforeOfferingManualRecovery() {
    StageCapability designInput =
        capability(
            "design-input-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE,
                                "Unknown contract element: quartz-trigger"))));
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");

    StageExecutionResult localRepair = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.Retry.class, localRepair.decision());
    applyLifecycle(runtime, localRepair);

    StageExecutionResult cleanRebuild = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.Retry.class, cleanRebuild.decision());
    assertTrue(
        cleanRebuild.signals().stream()
            .filter(PipelineSignal.Progress.class::isInstance)
            .map(PipelineSignal.Progress.class::cast)
            .anyMatch(progress -> progress.label().contains("clean model turn")));
    applyLifecycle(runtime, cleanRebuild);

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class,
            execute(runtime, "design-input").decision());

    assertEquals(
        PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of(PipelineGates.CONTINUE_REPAIR_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPAIR_ALLOWANCE));
  }

  @Test
  void shrinkingContractFindingSetUsesTheCleanRebuildWithoutRefundingLocalRepair() {
    var first = new PlanValidationFinding("CONTRACT_SHAPE", "The generated method is invalid.", true,
        MappingValidationDetails.empty(), "semantic-A", "/method");
    var second = new PlanValidationFinding("CONTRACT_SHAPE", "The generated topic is invalid.", true,
        MappingValidationDetails.empty(), "semantic-B", "/topic");
    AtomicInteger calls = new AtomicInteger();
    StageCapability designInput = capability("design-input-cap", context -> {
      var findings = calls.incrementAndGet() == 1 ? List.of(first, second) : List.of(second);
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, "The captured contract is invalid.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, ""))));
    });
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    CreateChainTestOrchestrator runtime = newRuntime(profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    String approved = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();

    StageExecutionResult localRepair = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.Retry.class, localRepair.decision());
    applyLifecycle(runtime, localRepair);
    StageExecutionResult cleanRebuild = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.Retry.class, cleanRebuild.decision());
    assertTrue(cleanRebuild.signals().stream().filter(PipelineSignal.Progress.class::isInstance)
        .map(PipelineSignal.Progress.class::cast).anyMatch(progress -> progress.label().contains("clean model turn")));
    applyLifecycle(runtime, cleanRebuild);
    StageDecision.WaitForInput wait = assertInstanceOf(StageDecision.WaitForInput.class,
        execute(runtime, "design-input").decision());
    assertEquals(PipelineGates.RECOVERY_REPAIR_ALLOWANCE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(approved, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(3, calls.get());
  }

  @Test
  void oversizedPlannerRecoveryContextRepairsWithoutAnAuthorQuestionOrDiagnosticModelCall() {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    var narrative = new FailureNarrative(agent);
    var brief = new RequirementBrief("Goal", List.of(), List.of(), List.of(), List.of(), "Goal")
        .withApprovedDraftText("x".repeat(120_000));
    StageCapability analysis = capability("analysis-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())), "brief ready", null))));
    var calls = new AtomicInteger();
    StageCapability planning = capability("planning-cap", context -> {
      if (calls.incrementAndGet() > 1) {
        return planningAlwaysCandidate().execute(context);
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, "Planner report has an invalid output shape.")));
    });
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(narrative, profile, analysis, planning,
        succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    String approved = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();

    var repair = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    applyLifecycle(runtime, repair);
    assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, "design-planning").decision());
    assertEquals(approved, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(brief, artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow(), RequirementBrief.class));
    assertEquals(2, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"brief", "semantic"})
  void recoveryIgnoresAnOrphanArtifactAppendedAfterApproval(String kind) {
    var observed = new AtomicReference<RecoveryContext>();
    FailureNarrative narrative = org.mockito.Mockito.mock(FailureNarrative.class);
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      observed.set(call.getArgument(1));
      return FailureNarrative.RecoveryResult.reply(null);
    });
    StageCapability planning = capability("planning-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
            "Planner report has an invalid output shape."))));
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(narrative, profile, analysisCandidate(), planning,
        succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    var approved = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow();
    var brief = artifactStore.payload(approved, RequirementBrief.class);
    var orphanKind = "brief".equals(kind) ? Kind.REQUIREMENT_BRIEF : Kind.CHAIN_SEMANTIC_REVISION;
    var orphan = artifactStore.append(new CompilationArtifacts.AppendCommand(RUN_ID, orphanKind,
        "brief".equals(kind) ? "1" : ChainSemanticRevision.CURRENT_SCHEMA_VERSION,
        "uncommitted-worker", "1", "brief".equals(kind) ? brief.withApprovedDraftText("Unapproved replacement")
            : twoEntryRevision(), List.of(), null, approved.provenance()));

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-planning").decision());
    assertEquals(approved.reference(), observed.get().evidence().approvedBriefRef());
    assertEquals(brief, observed.get().approvedBrief());
    assertNull(observed.get().evidence().approvedSemanticRef());
    assertEquals(orphan.reference(), artifactStore.latest(RUN_ID, orphanKind).orElseThrow().reference());
    assertEquals(approved.artifactId(), snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ASK_USER", "REVISE_BRIEF", "PARK"})
  void plannerContractFailureRepairsWhenTheNarrativeChoosesAnInvalidRecoveryAction(String actionName) {
    FailureNarrative narrative = org.mockito.Mockito.mock(FailureNarrative.class);
    AtomicInteger decisions = new AtomicInteger();
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      decisions.incrementAndGet();
      RecoveryContext recovery = call.getArgument(1);
      var action = RecoveryAction.valueOf(actionName);
      return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(
          action == RecoveryAction.PARK ? RecoveryCauseClass.UNCLASSIFIED : RecoveryCauseClass.BRIEF_DEFECT,
          action == RecoveryAction.PARK ? null : recovery.evidence().approvedBriefRef(),
          List.of(recovery.evidence().failureId()), action, List.of(), "Which producing step should be used?",
          "A producing step is missing from the planner report."));
    });
    AtomicInteger planningCalls = new AtomicInteger();
    StageCapability planning = capability("planning-cap", context -> {
      if (planningCalls.incrementAndGet() > 1) {
        return planningAlwaysCandidate().execute(context);
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE,
          "Planner report is missing a producing step for serviceCallId=create-task.")));
    });
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime = newRuntime(narrative, profile, analysisCandidate(), planning,
        succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    String approved = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();

    StageExecutionResult repair = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    assertEquals(2, decisions.get());
    RecoveryEvidence retained = artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.RECOVERY_EVIDENCE).orElseThrow(), RecoveryEvidence.class);
    assertEquals(2, retained.findings().stream()
        .filter(finding -> "INVALID_RECOVERY_DECISION".equals(finding.code())).count());
    applyLifecycle(runtime, repair);
    assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, "design-planning").decision());
    assertEquals(approved, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(2, planningCalls.get());
  }

  @Test
  void aRepeatedPlanningContractFailureUsesOneCleanRebuildBeforeOfferingManualRecovery() {
    StageCapability planning =
        capability(
            "planning-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE,
                                "planner report missing producing step for serviceCallId=create-task"))));
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            analysisCandidate(),
            planning,
            succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");

    StageExecutionResult localRepair = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, localRepair.decision());
    applyLifecycle(runtime, localRepair);

    StageExecutionResult cleanRebuild = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, cleanRebuild.decision());
    assertTrue(
        cleanRebuild.signals().stream()
            .filter(PipelineSignal.Progress.class::isInstance)
            .map(PipelineSignal.Progress.class::cast)
            .anyMatch(progress -> progress.label().contains("clean model turn")));
    applyLifecycle(runtime, cleanRebuild);

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class,
            execute(runtime, "design-planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of(PipelineGates.CONTINUE_REPAIR_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPAIR_ALLOWANCE));
  }

  @Test
  void aRepeatedPlanningFailureDoesNotAutoRebuildAfterCatalogWrite() {
    StageCapability planning =
        capability(
            "planning-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE,
                                "planner report missing producing step for serviceCallId=create-task"))));
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            analysisCandidate(),
            planning,
            succeeding("execution-cap", new AtomicInteger()));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");

    StageExecutionResult localRepair = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, localRepair.decision());
    applyLifecycle(runtime, localRepair);
    artifactStore.append(
        new CompilationArtifacts.AppendCommand(
            RUN_ID,
            Kind.CATALOG_CHAIN_SNAPSHOT,
            "1",
            "test-materializer",
            "1",
            new ChainCatalogFacts(
                "written-chain", "Orders", "", 0, 0, "", List.of(), List.of(), "catalog"),
            List.of(),
            null,
            new org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance(
                RUN_ID,
                "materialization",
                profile.profileId(),
                profile.profileVersion(),
                "sha",
                "materialization",
                "1",
                "sha")));

    StageExecutionResult repeated = execute(runtime, "design-planning");
    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, repeated.decision());

    assertEquals(
        PipelineGates.RECOVERY_REPEATED,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(),
        PipelineGates.guardOf(wait.prompt()).orElseThrow());
    assertFalse(
        repeated.signals().stream()
            .filter(PipelineSignal.Progress.class::isInstance)
            .map(PipelineSignal.Progress.class::cast)
            .anyMatch(progress -> progress.label().contains("clean model turn")));
  }

  @Test
  void repeatedFailureThresholdIsConfigurable() {
    StageCapability repeating =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                "the same failure"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(
            3,
            new RecoveryAttemptLedger(
                new RecoveryAttemptLedger.Limits(
                    3, ProductPipelineRunSupport.MAX_CAUSAL_REOPENS, 12)),
            profile,
            repeating);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    StageDecision.WaitForInput second =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(second.prompt()).orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    StageDecision.WaitForInput third =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(third.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(third.prompt()));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(third.prompt())
            .orElseThrow()
            .contains("identity="));
  }

  @Test
  void escalatedStopWritesAFailureReportAndEndsTheRun() {
    StageCapability repeating =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "same failure"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, repeating);
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "work");

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertEquals(StageStatus.FAILED, stageStatus(requireRun(), "work"));
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
  }

  @Test
  void escalatedDropIsOfferedOnlyForASkippableStageAndReentersRepair() {
    StageCapability repeating =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "same failure"))));
    ProductPipelineProfile profile = skippableRetryProfile();
    CreateChainTestOrchestrator runtime = newRuntime(profile, repeating);
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    StageDecision.WaitForInput escalated =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(escalated.prompt()).orElseThrow());
    assertFalse(PipelineGates.dropElementAllowed(escalated.prompt()));
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED));
  }

  @Test
  void aHaltedAttemptOutputNeverSatisfiesADownstreamStageInput() {
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = planningThenPublishProfile(validation);
    List<List<Reference>> priorPerTurn = new CopyOnWriteArrayList<>();
    List<List<Reference>> inputsPerTurn = new CopyOnWriteArrayList<>();
    AtomicInteger publishCalls = new AtomicInteger();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            planningRecordingPriorOutputs(priorPerTurn, inputsPerTurn),
            succeeding("publish-cap", publishCalls));
    startAndRecordInput(runtime, profile);

    execute(runtime, "planning");
    Reference rejected =
        snapshot(requireRun(), "planning").outputRefs().stream()
            .filter(ref -> ref.kind() == Kind.PLAN_VALIDATION_RESULT)
            .findFirst()
            .orElseThrow();
    // The retry succeeds without producing anything, so the only plan-validation-result in the run
    // is the one the halted attempt left in the artifact store.
    List<PipelineSignal> afterRetry =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(StageStatus.SUCCEEDED, stageStatus(requireRun(), "planning"));
    assertEquals("publish", requireRun().run().currentStageId());
    assertTrue(
        afterRetry.stream()
            .filter(PipelineSignal.WaitingForInput.class::isInstance)
            .map(PipelineSignal.WaitingForInput.class::cast)
            .anyMatch(
                waiting ->
                    waiting.prompt().contains("missing required input plan-validation-result")),
        () -> "expected publish to report a missing declared input, got: " + afterRetry);
    assertEquals(0, publishCalls.get());
    assertTrue(artifactStore.get(RUN_ID, rejected).isPresent());
  }

  @Test
  void haltEvidenceHydratesAfterRestartForFollowUpAndRevise() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator first =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), planningDomainFailure());
    startAndRecordInput(first, profile);
    approveAnalysis(first);
    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(first, "planning").decision());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());

    CreateChainTestOrchestrator restarted =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), planningDomainFailure());
    restarted
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
  }

  @Test
  void validationHaltStoresProposedBriefChangesOnReviseBrief() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    Reference approvedBrief =
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference();
    ProposedBriefChange proposedChange =
        new ProposedBriefChange(
            "timeout-fact",
            "timeoutSeconds",
            "0",
            "",
            "CONFLICTING_TIMEOUT",
            true);
    agent.recoverReviseBrief(
        approvedBrief,
        List.of(proposedChange),
        "The approved requirements need correction.");

    execute(runtime, "planning");

    Map<String, Object> attributes = runtime.support().runAttributes(RUN_ID);
    assertTrue(attributes.get(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR) instanceof List<?>);
    List<?> changes = (List<?>) attributes.get(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR);
    assertEquals(1, changes.size());
    assertInstanceOf(ProposedBriefChange.class, changes.get(0));
    ProposedBriefChange stored = (ProposedBriefChange) changes.get(0);
    assertEquals("timeoutSeconds", stored.field());
    assertEquals("0", stored.previousValue());
    assertTrue(stored.authorDecisionRequired());
    assertNotNull(attributes.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR));
  }

  @Test
  void validationHaltRevisesTheBriefWithoutOwnerChoice() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    Reference approvedBrief =
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference();
    agent.recoverReviseBrief(
        approvedBrief, List.of(), "The approved requirements need correction.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_REVISE_BRIEF, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("The approved requirements need correction.", PipelineGates.strip(wait.prompt()));
    assertEquals(
        List.of(ChatEvent.EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REVISE_BRIEF));
    assertEquals(
        "requirement-analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertNull(agent.lastCandidateSet.get());
    assertFalse(wait.prompt().contains("requirement-analysis"), wait.prompt());
  }

  @Test
  void editRequirementsReopensRequirementAnalysisAndKeepsTheApprovedBrief() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = requirementAnalysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    String approvedHash =
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash();
    agent.recoverReviseBrief(
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference(),
        List.of(),
        "The approved requirements need correction.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "planning").decision());
    assertEquals(
        PipelineGates.RECOVERY_REVISE_BRIEF, PipelineGates.gateOf(wait.prompt()).orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(
        approvedHash,
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash());

    StageExecutionResult analysis = execute(runtime, "requirement-analysis");
    assertInstanceOf(StageDecision.WaitForApproval.class, analysis.decision());
    applyLifecycle(runtime, analysis);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
  }

  @Test
  void editRequirementsReopensRequirementAnalysisWhenMappingTurnWouldZeroMatch() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = requirementAnalysisThenPlanningProfile(brief, validation);
    MappingTurnAdapter zeroMatch =
        (current, message) -> new MappingTurnResult.Clarification("ZERO_MATCH", List.of());
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            zeroMatch,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    String approvedHash =
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash();
    agent.recoverReviseBrief(
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference(),
        List.of(),
        "The approved requirements need correction.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "planning").decision());
    assertEquals(
        PipelineGates.RECOVERY_REVISE_BRIEF, PipelineGates.gateOf(wait.prompt()).orElseThrow());

    List<PipelineSignal> signals =
        runtime
            .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .noneMatch(message -> message.text().contains("ZERO_MATCH")),
        signals.toString());
    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(
        approvedHash,
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash());
  }

  @Test
  void briefDefectEndWithReportKeepsTheFailureRecord() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    agent.recoverReviseBrief(
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference(),
        List.of(),
        "The approved requirements need correction.");

    execute(runtime, "planning");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
  }

  @Test
  void planDefectOffersRebuildPlanWithoutStageChoices() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionRejectedPlanFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverRegenerates(
        Kind.IMPLEMENTATION_PLAN, "The plan is missing information required to create the chain.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(
        PipelineGates.RECOVERY_REBUILD_PLAN, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of(ChatEvent.REBUILD_PLAN_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REBUILD_PLAN));
    assertEquals("design-planning", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertFalse(wait.prompt().contains("design-planning"), wait.prompt());
    assertFalse(wait.prompt().contains("design-execution"), wait.prompt());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void rebuildPlanReopensDesignPlanningAndKeepsTheApprovedBrief() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionRejectedPlanFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    String approvedBriefHash =
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash();
    agent.recoverRegenerates(
        Kind.IMPLEMENTATION_PLAN, "The plan is missing information required to create the chain.");

    execute(runtime, "design-execution");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "requirement-analysis").status());
    assertEquals(
        approvedBriefHash,
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().contentHash());

    StageExecutionResult planning = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.WaitForApproval.class, planning.decision());
    applyLifecycle(runtime, planning);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void planDefectEndWithReportKeepsTheFailureRecord() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionRejectedPlanFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverRegenerates(
        Kind.IMPLEMENTATION_PLAN, "The plan is missing information required to create the chain.");

    execute(runtime, "design-execution");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void unsupportedRegionOffersEndRunWithoutRetryOrStageChoices() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              calls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.POLICY_FAILURE,
                              "This region is not supported for chain creation.")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_ENVIRONMENT, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        "This region is not supported for chain creation.", PipelineGates.strip(wait.prompt()));
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_ENVIRONMENT));
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_ENVIRONMENT)
            .contains(ChatEvent.RETRY_CREATION_ACTION));
    assertFalse(wait.prompt().contains("work"), wait.prompt());
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow().contains(RUN_ID));
    assertEquals(1, calls.get());
  }

  @Test
  void sslHandshakeFailureOffersEndRunAndDoesNotEnterARetryLoop() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              calls.incrementAndGet();
              return Multi.createFrom()
                  .failure(new SSLHandshakeException("PKIX path building failed"));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(5, 5_000L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_ENVIRONMENT, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(TransientFailures.ENVIRONMENT_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("PKIX"), wait.prompt());
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("PKIX path building failed"));
    assertEquals(1, calls.get());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.RECOVERY_ENVIRONMENT,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
    assertEquals(1, calls.get());
  }

  @Test
  void environmentFailureEndWithReportKeepsTheFailureRecord() {
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.POLICY_FAILURE,
                                "The catalog refused this environment."))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
  }

  @Test
  void invalidRecoveryDecisionGetsOneCorrectionThenRepairsItsProducer() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    Reference invalidTarget =
        new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "plan-hash");
    RecoveryDecision invalid =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            invalidTarget,
            List.of("failure-1"),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "The requirements need correction.");
    agent.recoverReturns(invalid, invalid);

    StageDecision.Retry retry = assertInstanceOf(StageDecision.Retry.class, execute(runtime, "planning").decision());
    assertEquals("planning", retry.stageId());
    assertFalse(retry.technicalRetry());
    assertEquals(2, agent.calls.get());
    List<Revision> evidenceHistory = artifactStore.history(RUN_ID, Kind.RECOVERY_EVIDENCE);
    assertEquals(1, evidenceHistory.size());
    RecoveryEvidence parkedEvidence =
        artifactStore.payload(evidenceHistory.get(0), RecoveryEvidence.class);
    long invalidDecisionFindings =
        parkedEvidence.findings().stream()
            .filter(finding -> "INVALID_RECOVERY_DECISION".equals(finding.code()))
            .count();
    assertEquals(2, invalidDecisionFindings);
  }

  @Test
  void validationHaltStoresStructuredGraphFindingsInRecoveryEvidence() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionWithGraphProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionGraphSchemaValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverReturns(
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(),
            RecoveryAction.PARK,
            List.of(),
            "",
            "Park until recovery is clarified."));

    execute(runtime, "design-execution");

    List<Revision> evidenceHistory = artifactStore.history(RUN_ID, Kind.RECOVERY_EVIDENCE);
    assertEquals(1, evidenceHistory.size());
    RecoveryEvidence recoveryEvidence =
        artifactStore.payload(evidenceHistory.get(0), RecoveryEvidence.class);
    SemanticFinding graphFinding =
        recoveryEvidence.findings().stream()
            .filter(finding -> "service-call".equals(finding.elementType()))
            .findFirst()
            .orElseThrow();
    assertTrue(
        !graphFinding.missingKeys().isEmpty() || !graphFinding.oneOfBranchHints().isEmpty());
    assertTrue(graphFinding.rawValidatorJson().contains("\"valid\""));
    assertTrue(
        graphFinding.rawValidatorJson().contains("\"errors\"")
            || graphFinding.rawValidatorJson().contains("\"missingRequired\""));
    assertNotEquals("design-execution", graphFinding.occurrenceId());
    assertNotEquals("design-planning", graphFinding.occurrenceId());
    assertNotEquals("requirement-analysis", graphFinding.occurrenceId());
    assertNotEquals("planning", graphFinding.occurrenceId());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void executionRegenerationRetriesItsProducerUnlessCatalogWasWritten(boolean catalogWritten) {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverRegenerates(
        Kind.PLAN_VALIDATION_RESULT, "Regenerate the rejected validation artifact.");

    if (catalogWritten) {
      artifactStore.append(new CompilationArtifacts.AppendCommand(
          RUN_ID, Kind.CATALOG_CHAIN_SNAPSHOT, "1", "test-materializer", "1",
          new ChainCatalogFacts("written-chain", "Orders", "", 0, 0, "", List.of(), List.of(), "catalog"),
          List.of(), null,
          new org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance(
              RUN_ID, "materialization", profile.profileId(), profile.profileVersion(), "sha",
              "materialization", "1", "sha")));
    }

    var decision = execute(runtime, "design-execution").decision();
    if (catalogWritten) {
      var wait = assertInstanceOf(StageDecision.WaitForInput.class, decision);
      assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      assertEquals(HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(), PipelineGates.guardOf(wait.prompt()).orElseThrow());
      assertEquals(1, executionCalls.get());
    } else {
      assertInstanceOf(StageDecision.Retry.class, decision);
      applyLifecycle(runtime, new StageExecutionResult(decision, List.of()));
      assertEquals(RunStatus.RUNNING, requireRun().run().status());
      assertEquals(1, executionCalls.get());
    }
  }

  @Test
  void reviseOfAnEarlierOwnerReopensItWithANewCandidateAndRejectsTheOldBinding() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenPrior = new AtomicReference<>();
    StageCapability analysis =
        capability(
            "analysis-cap",
            context -> {
              seenError.set(
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
              seenPrior.set(
                  context.attributeAsString(ProductPipelineRunSupport.PRIOR_CANDIDATE_ATTR));
              return analysisCandidate().execute(context);
            });
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent), profile, analysis, planningMissingBriefFacts());
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForApproval firstWait =
        assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, "analysis").decision());
    Reference originalCandidate = firstWait.candidate();
    long originalRevision = requireRun().run().runRevision();
    runtime
        .recordApprove(new ApproveCommand(RUN_ID, originalCandidate, originalRevision))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals("planning", requireRun().run().currentStageId());

    StageExecutionResult failed = execute(runtime, "planning");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("analysis", reopen.producerStageId());
    applyLifecycle(runtime, failed);
    applyLifecycle(runtime, execute(runtime, "analysis"));

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals("analysis", requireRun().run().currentStageId());
    StageSnapshot analysisSnapshot = snapshot(requireRun(), "analysis");
    Reference repairCandidate = analysisSnapshot.approvableReference();
    assertNotNull(repairCandidate);
    assertNotEquals(originalCandidate.contentHash(), repairCandidate.contentHash());
    assertNotEquals(originalRevision, requireRun().run().runRevision());
    assertTrue(analysisSnapshot.candidateRevision() > 1);
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "planning").status());
    assertTrue(snapshot(requireRun(), "planning").outputRefs().isEmpty());
    assertNotNull(seenError.get());
    assertFalse(seenError.get().isBlank());
    assertEquals(originalCandidate.contentHash(), seenPrior.get());

    Throwable stale =
        assertThrows(
            Exception.class,
            () ->
                runtime
                    .recordApprove(
                        new ApproveCommand(RUN_ID, originalCandidate, originalRevision))
                    .collect()
                    .asList()
                    .await()
                    .indefinitely());
    assertInstanceOf(StaleApprovalException.class, rootCause(stale));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals("analysis", requireRun().run().currentStageId());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "planning").status());
  }

  @Test
  void differentFailuresCanReopenTheSameOwnerButTheSameFailureCannot() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningDomainFailures(
                "missing scheduler", "missing access-control requirement", "missing access-control requirement"));
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    reopenPlanningToAnalysis(runtime);
    approveCurrentAnalysis(runtime);
    reopenPlanningToAnalysis(runtime);
    approveCurrentAnalysis(runtime);
    execute(runtime, "planning");

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    long reopenCount =
        requireRun().transitions().stream()
            .filter(transition -> RecoveryAttemptLedger.isReopenReason(transition.reason()))
            .count();
    assertEquals(2, reopenCount);
  }

  @Test
  void aRepeatedPlanningHaltDoesNotOfferStageSelection() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owners(
            "Repair the producing stage.", "analysis", "design", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenDesignThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            designCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "analysis");
    approveCurrentStage(runtime, "design");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_UNCLASSIFIED));
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    long reopenCount =
        requireRun().transitions().stream()
            .filter(transition -> RecoveryAttemptLedger.isReopenReason(transition.reason()))
            .count();
    assertEquals(0, reopenCount);
  }

  @Test
  void graphStructureReopensSucceededDesignInput() {
    ArtifactTypeRef revision = new ArtifactTypeRef("chain-semantic-revision", 1);
    StageCapability designInput =
        capability(
            "design-input-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.SUCCEEDED,
                                List.of(
                                    new ArtifactCandidate(
                                        Kind.CHAIN_SEMANTIC_REVISION,
                                        SemanticFixtures.linearOrders(),
                                        List.of())),
                                "semantic revision ready",
                                null))));
    StageCapability execution =
        capability(
            "execution-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE,
                                "loop-2 node op-loop-2 has 0 child; the runtime descriptor requires"
                                    + " at least 1",
                                RecoveryCause.of(RecoveryCauseCode.GRAPH_STRUCTURE)))));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "graph-structure-recovery",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "design-input",
                    "design-input-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(revision),
                    null,
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "design-execution",
                    "execution-cap",
                    List.of(revision),
                    List.of(new ArtifactTypeRef("chain-plan-graph", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("design-execution", "CHAIN_MATERIALIZED"),
            List.of("design-input", "design-execution"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, designInput, execution);
    startAndRecordInput(runtime, profile);
    StageExecutionResult captured = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.Continue.class, captured.decision());
    applyLifecycle(runtime, captured);

    StageExecutionResult failed = execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());

    assertEquals("design-input", reopen.producerStageId());
  }

  @Test
  void preWriteMaterializationContractShapeReopensSucceededDesignExecution() {
    ArtifactTypeRef request = new ArtifactTypeRef("materialization-request", 1);
    StageCapability execution =
        capability(
            "execution-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.SUCCEEDED,
                                List.of(new ArtifactCandidate(Kind.MATERIALIZATION_REQUEST, "request", List.of())),
                                "execution complete",
                                null))));
    StageCapability materialization =
        capability(
            "materialization-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.CONTRACT_FAILURE,
                                "materialization rejected the execution request",
                                RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE)))));
    ProductPipelineProfile profile = executionThenMaterializationProfile(request);
    CreateChainTestOrchestrator runtime = newRuntime(profile, execution, materialization);
    startAndRecordInput(runtime, profile);
    StageExecutionResult executionResult = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Continue.class, executionResult.decision());
    applyLifecycle(runtime, executionResult);

    StageExecutionResult failed = execute(runtime, "materialization");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(
            StageDecision.ReopenProducer.class, failed.decision());

    assertEquals("design-execution", reopen.producerStageId());
    applyLifecycle(runtime, failed);
    assertEquals("design-execution", requireRun().run().currentStageId());
  }

  @Test
  void repairedUpstreamArtifactCanContinueThroughMaterialization() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted a required fact.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    AtomicInteger materializationCalls = new AtomicInteger();
    StageCapability materialization =
        capability(
            "materialization-cap",
            context -> {
              if (materializationCalls.incrementAndGet() == 1) {
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                "The approved brief is missing the destination operation.",
                                RecoveryCause.missingBriefFacts(
                                    List.of("destination operation")))));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.SUCCEEDED,
                              List.of(
                                  new ArtifactCandidate(
                                      Kind.CATALOG_CHAIN_SNAPSHOT,
                                      new ChainCatalogFacts(
                                          "catalog-chain-1",
                                          "DemoChain",
                                          "",
                                          0,
                                          0,
                                          "",
                                          List.of(),
                                          List.of(),
                                          "built_in_catalog"),
                                      List.of())),
                              "chain materialized",
                              null)));
            });
    ProductPipelineProfile profile = analysisThenMaterializationProfile(brief);
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), materialization);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "analysis");

    StageExecutionResult failure = execute(runtime, "materialization");
    assertEquals(
        "analysis",
        assertInstanceOf(StageDecision.ReopenProducer.class, failure.decision()).producerStageId());
    applyLifecycle(runtime, failure);
    approveCurrentStage(runtime, "analysis");

    StageDecision.Complete complete =
        assertInstanceOf(
            StageDecision.Complete.class, execute(runtime, "materialization").decision());

    assertEquals(RunStatus.CHAIN_MATERIALIZED, complete.status());
    assertEquals(RunStatus.CHAIN_MATERIALIZED, requireRun().run().status());
    assertEquals(2, materializationCalls.get());
    assertTrue(runtime.support().latestCatalogChainSnapshot(RUN_ID).isPresent());
  }

  @ParameterizedTest
  @ValueSource(strings = {"snapshot", "checkpoint"})
  void catalogWriteBlocksCausalReopenAndRetryRepeatsMaterialization(String writeEvidence) {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief is wrong.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    AtomicInteger materializationCalls = new AtomicInteger();
    StageCapability materialization =
        capability(
            "materialization-cap",
            context -> {
              materializationCalls.incrementAndGet();
              if ("checkpoint".equals(writeEvidence)) {
                var checkpoint = new org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCheckpoint(
                    1, RUN_ID, "catalog-chain-1",
                    org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationPhase.CHAIN,
                    new org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap(
                        "catalog-chain-1", java.util.Map.of("trigger", "el-1"),
                        java.util.Map.of(), java.util.Map.of()), "pending", java.util.Map.of());
                artifactStore.append(new CompilationArtifacts.AppendCommand(RUN_ID, Kind.MATERIALIZATION_CHECKPOINT,
                    "1", "materialization-cap", "1", checkpoint, List.of(), null,
                    artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().provenance()));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.DOMAIN_FAILURE,
                              "checkpoint".equals(writeEvidence) ? List.of() : List.of(
                                  new ArtifactCandidate(
                                      Kind.CATALOG_CHAIN_SNAPSHOT,
                                      new ChainCatalogFacts(
                                          "catalog-chain-1",
                                          "DemoChain",
                                          "",
                                          0,
                                          0,
                                          "",
                                          List.of(),
                                          List.of(),
                                          "built_in_catalog"),
                                      List.of())),
                              "catalog wrote then domain failed",
                              null,
                              new RecoveryCause(
                                  RecoveryCauseCode.MISSING_BRIEF_FACTS,
                                  List.of(
                                      new PlanValidationFinding(
                                          "MISSING_BRIEF_FACTS",
                                          "catalog wrote then domain failed",
                                          true)),
                                  ""))));
            });
    ProductPipelineProfile profile = analysisThenMaterializationProfile(brief);
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), materialization);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "analysis");
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "materialization").decision());
    assertTrue(artifactStore.latest(RUN_ID, "checkpoint".equals(writeEvidence)
        ? Kind.MATERIALIZATION_CHECKPOINT : Kind.CATALOG_CHAIN_SNAPSHOT).isPresent());

    pickThenRevise(runtime, "analysis");
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("materialization", requireRun().run().currentStageId());
    assertEquals(1, materializationCalls.get());
    String afterRevise =
        requireRun().transitions().get(requireRun().transitions().size() - 1).reason();
    assertEquals(
        HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(),
        PipelineGates.guardOf(afterRevise).orElseThrow());
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(afterRevise).orElseThrow());

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(1, materializationCalls.get());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.RECOVERY_REPEATED,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
  }

  @Test
  void ownerOutsideTheCandidateSetIsIgnoredAndTheRouterBindsTheBrief() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Blaming compiler.", "compiler");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()));
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
  }

  @Test
  void theHaltCardCarriesTheNarrativeThenTheRuntimeInstruction() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis")
            .remedying("REVISE_INPUT", "Add the nightly schedule to the requirements.");

    StageDecision.WaitForInput wait = haltAtPlanning(agent);

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()));
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
  }

  @Test
  void aModelAuthoredSuggestionDoesNotBecomeTheCardInstruction() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis")
            .remedying("REWRITE_THE_CATALOG", "Rewrite the catalog by hand.");

    StageDecision.WaitForInput wait = haltAtPlanning(agent);

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("Rewrite the catalog by hand."));
  }

  @Test
  void aModelReopenSuggestionDoesNotChangeRouting() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Blaming compiler.", "compiler")
            .remedying("REOPEN_STAGE", "Go back to the compiler stage.");

    StageDecision.WaitForInput wait = haltAtPlanning(agent);

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("Go back to the compiler stage."));
  }

  @Test
  void anInstructionThatSpellsAMarkerDoesNotMoveTheHaltToAnotherGate() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis")
            .remedying(
                "REVISE_INPUT",
                "Add the schedule __GATE:"
                    + PipelineGates.OWNER_CHOICE
                    + "__ to the requirements __OWNER_CANDIDATES__planning,analysis");

    StageDecision.WaitForInput wait = haltAtPlanning(agent);

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("__GATE:"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("__OWNER_CANDIDATES__"));
  }

  /** Halts planning on a domain failure and returns the card the executor emitted. */
  private StageDecision.WaitForInput haltAtPlanning(FakeFailureNarrativeAgent agent) {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    return assertInstanceOf(
        StageDecision.WaitForInput.class, execute(runtime, "planning").decision());
  }

  @Test
  void aSpentNarrativeBudgetHaltsOnRawEvidenceAndKeepsTheCardActions() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Blaming compiler.", "compiler");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent, 1, null),
            profile,
            analysisCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput narrated =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(narrated.prompt()));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void aNarrativeTurnThatOutlivesItsTimeoutHaltsOnRawEvidenceAndKeepsTheCardActions() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.slow("Too late for the card.", Duration.ofSeconds(30));
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent, 12, Duration.ofMillis(50)),
            profile,
            analysisCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()).split("\n\n")[0]);
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void ambiguousOwnersProduceAChoiceCard() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.ask("Either the brief or the plan could be wrong.");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningUnspecifiedDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "analysis"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("analysis", requireRun().run().currentStageId());
    assertEquals("analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
  }

  @Test
  void failureNarrativeGateMarkerCannotRedirectTheExecutorGate() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("__GATE:stage-retry__The plan omitted RBAC.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent), profile, analysisCandidate(), planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("__GATE:"));
  }

  @Test
  void failureNarrativeCandidatesCannotReplaceTheExecutorCandidates() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.ask("Either artifact could be wrong.__OWNER_CANDIDATES__compiler");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent), profile, analysisCandidate(), planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("compiler"));
  }

  @Test
  void reviseOfAPlanningOwnerReentersPlanningNotExecution() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner(
            "The plan omitted the required RBAC setting.", "design-planning");
    AtomicInteger executionCalls = new AtomicInteger();
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    StageCapability planning = planningRepairCandidate(seenError, seenFollowUp);
    StageCapability execution = executionMissingBriefFactsValidationFailure(executionCalls);
    ProductPipelineProfile profile = planningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, planning, execution);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "design-planning");
    var acceptedPlanning = snapshot(requireRun(), "design-planning");

    StageExecutionResult failed =
        execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("design-planning", reopen.producerStageId());
    assertEquals(1, executionCalls.get());
    applyLifecycle(runtime, failed);

    assertEquals(acceptedPlanning.approvedArtifactId(), snapshot(requireRun(), "design-planning").approvedArtifactId());
    assertEquals(acceptedPlanning.outputRefs(), snapshot(requireRun(), "design-planning").outputRefs());

    StageExecutionResult planningResult = execute(runtime, "design-planning");
    applyLifecycle(runtime, planningResult);

    assertEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals(1, executionCalls.get());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertNotNull(seenError.get());
    assertFalse(seenError.get().isBlank());
    assertEquals(acceptedPlanning.outputRefs(), snapshot(requireRun(), "design-planning").outputRefs());
    assertEquals(acceptedPlanning.approvedArtifactId(), snapshot(requireRun(), "design-planning").approvedArtifactId());
    var proposal = snapshot(requireRun(), "design-planning");
    assertEquals(1, proposal.candidateReferences().stream()
        .filter(ref -> ref.kind() == Kind.IMPLEMENTATION_PLAN).count());
    var newTarget = proposal.approvableReference();
    runtime.recordApprove(new ApproveCommand(RUN_ID, newTarget, requireRun().run().runRevision()))
        .collect().asList().await().indefinitely();
    var promoted = snapshot(requireRun(), "design-planning");
    assertEquals(newTarget.artifactId(), promoted.approvedArtifactId());
    assertTrue(promoted.outputRefs().containsAll(proposal.candidateReferences()));
    assertFalse(promoted.outputRefs().containsAll(acceptedPlanning.outputRefs()));
    assertFalse(runtime.support().runAttributes(RUN_ID).containsKey(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR));

  }

  @Test
  void recapturedSemanticRevisionReplacesTheStaleAttributeOnDesignExecution() {
    AtomicInteger inputCalls = new AtomicInteger();
    AtomicInteger executionCalls = new AtomicInteger();
    List<String> seenRevisionIds = new ArrayList<>();
    ProductPipelineProfile profile = analysisThenDesignInputThenExecutionProfile();
    StageCapability designInput =
        capability(
            "design-input-cap",
            context -> {
              int call = inputCalls.incrementAndGet();
              ChainSemanticRevision payload =
                  call == 1 ? twoEntryRevision() : SemanticFixtures.linearOrders();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.SUCCEEDED,
                              List.of(
                                  new ArtifactCandidate(
                                      Kind.CHAIN_SEMANTIC_REVISION, payload, List.of())),
                              "revision ready",
                              null)));
            });
    StageCapability execution =
        capability(
            "execution-cap",
            context -> {
              executionCalls.incrementAndGet();
              Object attribute = context.attributes().get("chainSemanticRevision");
              seenRevisionIds.add(
                  attribute instanceof ChainSemanticRevision revision
                      ? revision.revisionId()
                      : null);
              if (executionCalls.get() == 1) {
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                "execution validation failed",
                                RecoveryCause.missingBriefFacts(List.of("missing quartz")))));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "done")));
            });
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), designInput, execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    applyLifecycle(runtime, execute(runtime, "design-input"));

    StageExecutionResult failed = execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("requirement-analysis", reopen.producerStageId());
    applyLifecycle(runtime, failed);

    approveStage(runtime, "requirement-analysis");
    applyLifecycle(runtime, execute(runtime, "design-input"));
    applyLifecycle(runtime, execute(runtime, "design-execution"));

    assertEquals(List.of("revision-1", "revision-orders"), seenRevisionIds);
    assertEquals(2, inputCalls.get());
    assertEquals(2, executionCalls.get());
  }

  @Test
  void missingApprovedBriefFactsReopenRequirementAnalysisWithTheFollowUp() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design input needs more information.", "design-input");
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    StageCapability designInput =
        capability(
            "design-input-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                "The approved requirement brief is missing required facts: "
                                    + "SERVICE_CALL participant and operation query",
                                RecoveryCause.missingBriefFacts(
                                    List.of(
                                        "SERVICE_CALL participant", "operation query"))))));
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisRepairCandidate(seenError, seenFollowUp),
            designInput);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "requirement-analysis");

    StageExecutionResult failedInput = execute(runtime, "design-input");
    assertInstanceOf(StageDecision.ReopenProducer.class, failedInput.decision());
    runtime.support().applyStageLifecycle(RUN_ID, failedInput).collect().asList().await().indefinitely();

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("requirement-analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());

    StageExecutionResult analysis = execute(runtime, "requirement-analysis");
    assertInstanceOf(StageDecision.WaitForApproval.class, analysis.decision());
    runtime
        .support()
        .applyStageLifecycle(RUN_ID, analysis)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-input").status());
    assertFalse(seenError.get().isBlank());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CHAIN_PLAN_GRAPH", "IMPLEMENTATION_PLAN"})
  void missingRecoveryDecisionOnCaptureContractShapeOffersManualRebuild(String historicalKind) {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger captureCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    StageCapability designInput =
        capability(
            "design-input-cap",
            context -> {
              captureCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              "The response mapping edge targets a node with multiple incoming"
                                  + " edges",
                              new RecoveryCause(
                                  RecoveryCauseCode.CONTRACT_SHAPE,
                                  List.of(
                                      new PlanValidationFinding(
                                          "CONTRACT_SHAPE",
                                          "The response mapping edge targets a node with multiple"
                                              + " incoming edges",
                                          true)),
                                  ""))));
            });
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    artifactStore.append(new CompilationArtifacts.AppendCommand(
        RUN_ID, Kind.valueOf(historicalKind), "1", "historical-producer", "1",
        Map.of("historical", true), List.of(), null,
        new org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance(
            RUN_ID, "design-planning", profile.profileId(), profile.profileVersion(), "sha",
            "historical-producer", "1", "sha")));
    approveCurrentStage(runtime, "requirement-analysis");

    StageDecision.WaitForInput wait = waitAfterOptionalSemanticRepair(runtime, "design-input");

    assertEquals(3, captureCalls.get());
    assertEquals(
        PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.gateOf(wait.prompt()).orElseThrow())
            .contains(ChatEvent.EDIT_REQUIREMENTS_ACTION));
    assertFalse(
        PipelineGates.strip(wait.prompt())
            .contains("Edit the requirements if this node or path is wrong"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("design-input"));
  }

  @Test
  void captureContractFailuresKeepTheBriefAndGiveEachRejectionItsOwnRepairBudget() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger calls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    StageCapability designInput = capability("design-input-cap", context -> {
      String message = calls.incrementAndGet() == 1
          ? "Missing sourceFactIds: receive-event"
          : "Anchor onTaskStart has a mismatched elementType";
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE, message,
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE,
              List.of(new PlanValidationFinding("CONTRACT_SHAPE", message, true)), ""))));
    });
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "requirement-analysis");
    Reference brief = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference();
    agent.recoverReviseBrief(brief, List.of(), "Change the trigger in the requirements.");

    for (int attempt = 0; attempt < 2; attempt++) {
      StageExecutionResult result = execute(runtime, "design-input");
      StageDecision.Retry retry = assertInstanceOf(StageDecision.Retry.class, result.decision());
      assertEquals("design-input", retry.stageId());
      applyLifecycle(runtime, result);
    }

    assertEquals(2, calls.get());
    assertNull(agent.lastRecoveryContextJson.get());
    assertEquals(brief, artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference());
    assertEquals(1, artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size());
  }

  @Test
  void exhaustedBriefRecoverySummaryListsTypedMappingTargetPaths() {
    PlanValidationFinding finding =
        new PlanValidationFinding(
            "MAPPING_UNKNOWN_TARGET",
            "Target path $.preserved.executionId is absent from the target contract.",
            true,
            new MappingValidationDetails(
                "preserved-mapping",
                "source-call",
                "RESPONSE",
                "target-call",
                "REQUEST",
                "$.source",
                "$.preserved.executionId",
                "",
                "PROPOSED",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "$.preserved.executionId"));
    String summary =
        ProductPipelineStageExecutor.exhaustedBriefRecoverySummary(
            RecoveryCause.mappingContract(List.of(finding)));
    assertTrue(summary.contains("$.preserved.executionId"), summary);
    assertFalse(
        summary.toLowerCase(Locale.ROOT).contains("omitted a required api value"), summary);
    assertFalse(summary.toLowerCase(Locale.ROOT).contains("your requirements"), summary);
  }

  @Test
  void exhaustedBriefRecoverySummaryStillListsMissingBriefFactsPaths() {
    String summary =
        ProductPipelineStageExecutor.exhaustedBriefRecoverySummary(
            RecoveryCause.missingBriefFacts(List.of("$.preserved.executionId")));
    assertTrue(summary.contains("$.preserved.executionId"), summary);
  }

  @Test
  void unclassifiedRecoverySummaryNamesMissingGraphPatch() {
    assertEquals(
        ProductPipelineStageExecutor.MISSING_GRAPH_PATCH_SUMMARY,
        ProductPipelineStageExecutor.unclassifiedRecoverySummary(
            "contract failure: missing producer for mandatory artifact GRAPH_PATCH_ARTIFACT"));
    assertEquals(
        ProductPipelineStageExecutor.MISSING_GRAPH_PATCH_SUMMARY,
        ProductPipelineStageExecutor.unclassifiedRecoverySummary(
            "contract failure: skill did not complete cip-script-generator status=FAILED: "
                + "Compiler skill did not capture a graph patch. The agent must call"
                + " captureGraphPatch with a valid GraphPatch before finishing."));
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        ProductPipelineStageExecutor.unclassifiedRecoverySummary("Cannot deserialize"));
  }

  @Test
  void spentDesignInputCaptureRepairOffersRebuildWithoutEscalatingOwners() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger captureCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    StageCapability designInput =
        capability(
            "design-input-cap",
            context -> {
              captureCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              "Trigger node 'trigger-om-onTaskResult' must have exactly one"
                                  + " outgoing edge",
                              new RecoveryCause(
                                  RecoveryCauseCode.CONTRACT_SHAPE,
                                  List.of(
                                      new PlanValidationFinding(
                                          "CONTRACT_SHAPE",
                                          "Trigger node 'trigger-om-onTaskResult' must have"
                                              + " exactly one outgoing edge",
                                          true)),
                                  ""))));
            });
    agent.recoverRegenerates(
        Kind.CHAIN_SEMANTIC_REVISION, "The captured topology still has two triggers.");
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "requirement-analysis");

    StageDecision.WaitForInput wait = waitAfterOptionalSemanticRepair(runtime, "design-input");

    assertEquals(3, captureCalls.get());
    assertEquals(
        PipelineGates.RECOVERY_REPAIR_ALLOWANCE,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("requirement-analysis"));
  }

  @Test
  void aGrantedAllowanceResumesDesignInputWithoutReopeningApprovedRequirements() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    AtomicInteger captureCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenDesignInputProfile();
    StageCapability designInput =
        capability(
            "design-input-cap",
            context -> {
              captureCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              "Trigger node 'trigger-om-onTaskResult' must have exactly one"
                                  + " outgoing edge",
                              new RecoveryCause(
                                  RecoveryCauseCode.CONTRACT_SHAPE,
                                  List.of(
                                      new PlanValidationFinding(
                                          "CONTRACT_SHAPE",
                                          "Trigger node 'trigger-om-onTaskResult' must have"
                                              + " exactly one outgoing edge",
                                          true)),
                                  ""))));
            });
    agent.recoverRegenerates(
        Kind.CHAIN_SEMANTIC_REVISION, "The captured topology still has two triggers.");
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), designInput);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "requirement-analysis");
    StageDecision.WaitForInput first = waitAfterOptionalSemanticRepair(runtime, "design-input");
    applyLifecycle(runtime, new StageExecutionResult(first, List.of()));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, ProductPipelineRunSupport.repairOfferCommand(requireRun()),
            "grant-click", "grant-hash", InputOrigin.TRUSTED))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("design-input", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(StageStatus.RUNNING, snapshot(requireRun(), "design-input").status());
    assertEquals(3, captureCalls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"KEEP_REQUESTED", "USE_RECOMMENDED"})
  void aGeneratedPolicyViolationCannotAskForAnAlreadyConfirmedChoice(String selected) {
    var choice = org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision.valueOf(selected);
    RequirementBrief brief = accessPolicyBrief(choice);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger diagnoses = new AtomicInteger();
    var narrative = sourceAwarePolicyNarrative(diagnoses);
    var profile = analysisThenPlanningThenExecutionProfile();
    var execution = capability("execution-cap", context -> calls.incrementAndGet() == 1
        ? executionRbacValidationFailure(new AtomicInteger()).execute(context)
        : Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Applied the confirmed access control"))));
    var runtime = newRuntime(narrative, profile, analysisWithBrief(brief), planningAlwaysCandidate(), execution);
    attachAuthorSources(runtime, "Use NONE for external access", "Use " + choice.name());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    String approval = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();

    var repair = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    applyLifecycle(runtime, repair);
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertInstanceOf(StageDecision.Complete.class, execute(runtime, "design-execution").decision());
    assertEquals(2, diagnoses.get());
    assertEquals(2, calls.get());
    assertEquals(approval, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(brief, artifactStore.payload(artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow(),
        RequirementBrief.class));
  }

  @Test
  void anUnansweredPolicyConflictStillAsksForTheAuthorChoice() {
    AtomicInteger diagnoses = new AtomicInteger();
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(sourceAwarePolicyNarrative(diagnoses), profile,
        analysisWithBrief(accessPolicyBrief(null)), planningAlwaysCandidate(),
        executionRbacValidationFailure(new AtomicInteger()));
    attachAuthorSources(runtime, "Use NONE for external access");
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(wait.prompt().contains("Keep NONE or use the recommended RBAC?"));
    assertEquals(1, diagnoses.get());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "design-planning").status());
  }

  @Test
  void omittedDiscoveryCaptureRepairsInTheSameRunAndRetiresItsErrorBeforeAnalysis() {
    var profile = discoveryAndAnalysisThenPlanningProfile(new ArtifactTypeRef("requirement-draft", 1),
        new ArtifactTypeRef("requirement-brief", 1), new ArtifactTypeRef("plan-validation-result", 1));
    var calls = new AtomicInteger();
    var discovery = capability("discovery-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(context.attributeAsString("userText") == null && !StageRepairEvidence.isRepairTurn(context)
            ? StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "") : calls.incrementAndGet() == 1
            ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "Mandatory capture was omitted.",
                new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
                    "CAPTURE_MISSING", "Capture the draft from saved input.", true)), ""))
            : new StageOutcome(StageOutcomeClass.SUCCEEDED, List.of(new ArtifactCandidate(
                Kind.REQUIREMENT_DRAFT, new RequirementDraft(true, "draft"), List.of())), "ready", null))));
    var analysis = capability("analysis-cap", context -> {
      assertFalse(StageRepairEvidence.isRepairTurn(context), "Resolved discovery error must not reopen analysis.");
      return analysisAlwaysCandidate().execute(context);
    });
    var runtime = newRuntime(new FailureNarrative(), profile, discovery, analysis, planningAlwaysCandidate());
    startAndRecordInput(runtime, profile);
    int inputs = artifactStore.history(RUN_ID, Kind.USER_INPUT).size();
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "requirement-discovery").decision());
    applyLifecycle(runtime, execute(runtime, "requirement-discovery"));
    assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, "requirement-analysis").decision());
    assertEquals(2, calls.get());
    assertEquals(inputs, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
    assertTrue(requireRun().attempts().stream().anyMatch(attempt -> attempt.outcome() == StageStatus.FAILED));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"design-input,REVISE_BRIEF", "design-planning,REVISE_BRIEF",
      "design-input,ASK_USER", "design-planning,ASK_USER"})
  void sourceCorrectionReopensCommittedDiscoveryAndRenewsBriefApprovalWithoutNewInput(String failedStage, String diagnosis) {
    var draftType = new ArtifactTypeRef("requirement-draft", 1);
    var briefType = new ArtifactTypeRef("requirement-brief", 1);
    var base = discoveryAndAnalysisThenPlanningProfile(draftType, briefType,
        new ArtifactTypeRef("plan-validation-result", 1));
    var profile = new ProductPipelineProfile(1, "committed-discovery-repair", "1", base.runInputs(), List.of(
        new ProfileStage("requirement-discovery", "requirement-discovery", base.runInputs(),
            List.of(draftType), null, null, new RetryPolicy(0, 1L)),
        base.stages().get(1),
        new ProfileStage(failedStage, "planning-cap", List.of(briefType), List.of(),
            null, null, new RetryPolicy(0, 1L))),
        new TerminalPolicy(failedStage, "PLAN_APPROVED"),
        List.of("requirement-discovery", "analysis-cap", "planning-cap"));
    var narrative = org.mockito.Mockito.spy(new FailureNarrative());
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      RecoveryContext context = call.getArgument(1);
      if ("ASK_USER".equals(diagnosis)) {
        return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT,
            context.evidence().approvedBriefRef(), List.of(context.evidence().failureId()), RecoveryAction.ASK_USER,
            List.of(), "Which group should receive the events?", "The group is missing.",
            new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence(
                "endpoint", "groupId", org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.MISSING_VALUE,
                context.authorInputs().stream().map(org.qubership.integration.platform.ai.productpipeline.artifact.UserInput::inputId).toList())));
      }
      return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT,
          context.evidence().approvedBriefRef(), List.of(context.evidence().failureId(), context.authorInputs().getFirst().inputId()),
          RecoveryAction.REVISE_BRIEF,
          List.of(new ProposedBriefChange("endpoint", "httpMethod", "", "POST", "SOURCE_VALUE_OMITTED", false)),
          "", "Restore the supplied method."));
    });
    var discoveryCalls = new AtomicInteger();
    var discovery = capability("requirement-discovery", context -> {
      if (context.attributeAsString("userText") == null && !StageRepairEvidence.isRepairTurn(context)) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
      }
      if (discoveryCalls.incrementAndGet() > 1) {
        assertNull(context.attributeAsString("userText"), "Internal recovery must not manufacture an author turn.");
        assertEquals("MISSING_BRIEF_FACTS", StageRepairEvidence.from(context).causeCode());
        assertTrue(context.attributes().containsKey(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR));
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED,
          List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, new RequirementDraft(true, "draft"), List.of())), "ready", null)));
    });
    var planningCalls = new AtomicInteger();
    var planning = capability("planning-cap", context -> Multi.createFrom().item(new CapabilitySignal.Completed(
        planningCalls.incrementAndGet() == 1
            ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "The draft omitted the method.",
                RecoveryCause.missingBriefFacts(List.of("HTTP method is absent from the draft.")))
            : StageOutcome.of(StageOutcomeClass.SUCCEEDED, "ready"))));
    var runtime = newRuntime(narrative, profile, discovery,
        analysisWithBrief(new RequirementBrief("Receive events", List.of(), List.of(), List.of(), List.of(), "")
            .withFacts(List.of(new org.qubership.integration.platform.ai.plan.RequirementFact("endpoint",
                org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
                org.qubership.integration.platform.ai.plan.RequirementFactKind.ENDPOINT,
                "kafka-trigger-2", "Receive events")))), planning);
    attachAuthorSources(runtime, "Receive events with groupId=orders", "Change groupId to invoices");
    startAndRecordInput(runtime, profile);
    applyLifecycle(runtime, execute(runtime, "requirement-discovery"));
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "requirement-discovery").status());
    assertNull(snapshot(requireRun(), "requirement-discovery").approvedArtifactId());
    approveStage(runtime, "requirement-analysis");
    int inputs = artifactStore.history(RUN_ID, Kind.USER_INPUT).size();

    var acceptedDiscovery = snapshot(requireRun(), "requirement-discovery");
    var acceptedBrief = snapshot(requireRun(), "requirement-analysis");
    var repair = execute(runtime, failedStage);
    assertEquals("requirement-discovery", assertInstanceOf(StageDecision.ReopenProducer.class, repair.decision()).producerStageId());
    applyLifecycle(runtime, repair);
    assertEquals("requirement-discovery", requireRun().run().currentStageId());
    applyLifecycle(runtime, execute(runtime, "requirement-discovery"));
    assertEquals(acceptedDiscovery.outputRefs(), snapshot(requireRun(), "requirement-discovery").outputRefs());
    assertFalse(snapshot(requireRun(), "requirement-discovery").candidateReferences().isEmpty());
    assertEquals(acceptedBrief.outputRefs(), snapshot(requireRun(), "requirement-analysis").outputRefs());
    var briefProposal = execute(runtime, "requirement-analysis");
    var waiting = assertInstanceOf(StageDecision.WaitForApproval.class, briefProposal.decision());
    assertEquals(acceptedBrief.approvedArtifactId(), snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(acceptedDiscovery.outputRefs(), snapshot(requireRun(), "requirement-discovery").outputRefs());
    var preparedSource = snapshot(requireRun(), "requirement-discovery").candidateReferences();
    runtime.recordApprove(new ApproveCommand(RUN_ID, waiting.candidate(), requireRun().run().runRevision()))
        .collect().asList().await().indefinitely();
    assertEquals(preparedSource, snapshot(requireRun(), "requirement-discovery").outputRefs());
    assertInstanceOf(StageDecision.Complete.class, execute(runtime, failedStage).decision());
    assertEquals(2, discoveryCalls.get());
    assertEquals(inputs, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
    assertEquals(RUN_ID, requireRun().run().runId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"REGENERATE_ARTIFACT", "PARK", "MISSING", "INVALID_REFERENCE"})
  void executionFailureBeforeCaptureDoesNotLabelTheApprovedPlanAsRejectedOutput(String diagnosis) {
    var observed = new AtomicReference<RecoveryContext>();
    var narrative = org.mockito.Mockito.spy(new FailureNarrative());
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      RecoveryContext context = call.getArgument(1);
      observed.set(context);
      if ("MISSING".equals(diagnosis)) {
        return FailureNarrative.RecoveryResult.reply(null);
      }
      if ("PARK".equals(diagnosis)) {
        return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED,
            null, List.of(context.evidence().failureId()), RecoveryAction.PARK, List.of(), "",
            "The finding does not identify a specific generated field."));
      }
      return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT,
          "INVALID_REFERENCE".equals(diagnosis)
              ? new Reference(Kind.CHAIN_PLAN_GRAPH, "unknown-output", "unknown-hash")
              : context.evidence().rejectedArtifactRefs().getFirst(), List.of(context.evidence().failureId()),
          RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Generate configuration using confirmed RBAC."));
    });
    var calls = new AtomicInteger();
    var execution = capability("execution-cap", context -> Multi.createFrom().item(new CapabilitySignal.Completed(
        calls.incrementAndGet() == 1
            ? StageOutcome.of(StageOutcomeClass.VALIDATION_FAILURE, "Security validation rejected the generated output.",
                RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY))
            : StageOutcome.of(StageOutcomeClass.SUCCEEDED, "ready"))));
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(narrative, profile, analysisWithBrief(accessPolicyBrief(
        org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision.USE_RECOMMENDED)),
        planningAlwaysCandidate(), execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    var approvedPlan = snapshot(requireRun(), "design-planning").outputRefs();
    var pinnedPlan = approvedPlan.stream().filter(ref -> ref.kind() == Kind.IMPLEMENTATION_PLAN)
        .findFirst().orElseThrow();
    artifactStore.append(new CompilationArtifacts.AppendCommand(RUN_ID, Kind.IMPLEMENTATION_PLAN, "1", "planning-cap", "1",
        Map.of("plan", "uncommitted orphan"), List.of(), null,
        artifactStore.get(RUN_ID, pinnedPlan).orElseThrow().provenance()));

    var repair = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    assertTrue(observed.get().evidence().rejectedArtifactRefs().stream()
        .noneMatch(approvedPlan::contains), "An approved input must not be relabeled as a rejected output.");
    assertEquals(pinnedPlan, observed.get().approvedPlanRef());
    assertEquals(artifactStore.payload(artifactStore.get(RUN_ID, pinnedPlan).orElseThrow(), Object.class),
        observed.get().approvedPlan());
    applyLifecycle(runtime, repair);
    assertInstanceOf(StageDecision.Complete.class, execute(runtime, "design-execution").decision());
    assertEquals(approvedPlan, snapshot(requireRun(), "design-planning").outputRefs());
    assertEquals(2, calls.get());
  }

  @Test
  void nonMappingTypedEvidenceReachesTheRealDiagnosticProjectionWithoutAnOrphanGraph() throws Exception {
    var projected = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      var json = mapper.readTree((String) call.getArgument(1));
      projected.set(json);
      return new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null,
          List.of(json.path("evidence").path("failureId").asText()), RecoveryAction.PARK, List.of(), "", "Inspect output.");
    });
    var finding = new PlanValidationFinding("SECURITY_POLICY", "Use the confirmed access policy.", true,
        null, "http-trigger", "/properties/accessControlType");
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), new AtomicInteger(),
        new RecoveryCause(RecoveryCauseCode.SECURITY_POLICY, List.of(finding), ""));
    artifactStore.append(new CompilationArtifacts.AppendCommand(RUN_ID, Kind.CHAIN_PLAN_GRAPH, "1", "execution-cap", "1",
        new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph("1.0",
            new org.qubership.integration.platform.ai.plan.model.ChainSection("orphan", "Orphan"),
            List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                "orphan-trigger", "http-trigger", "Orphan", null, null, List.of())), List.of()),
        List.of(), null, artifactStore.latest(RUN_ID, Kind.IMPLEMENTATION_PLAN).orElseThrow().provenance()));

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());

    var evidence = projected.get().path("evidence");
    assertEquals("design-execution", evidence.path("producerStageId").asText());
    var saved = evidence.path("findings").get(0);
    assertEquals("SECURITY_POLICY", saved.path("code").asText());
    assertEquals("http-trigger", saved.path("nodeId").asText());
    var raw = mapper.readTree(saved.path("rawValidatorJson").asText());
    assertEquals(finding.originRecordId(), raw.path("originRecordId").asText());
    assertEquals(finding.fieldPointer(), raw.path("fieldPointer").asText());
    assertFalse(evidence.toString().contains("orphan-trigger"));
    assertTrue(projected.get().path("rejectedArtifact").isMissingNode()
        || projected.get().path("rejectedArtifact").isNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"AUTHENTICATION", "QUOTA", "TLS"})
  void permanentDiagnosisFailureDoesNotSpendProducerRepairCredit(String failureKind) {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    RuntimeException failure = switch (failureKind) {
      case "AUTHENTICATION" -> new dev.langchain4j.exception.AuthenticationException("Invalid credential");
      case "QUOTA" -> new dev.langchain4j.exception.HttpException(402, "Provider quota exhausted");
      default -> new IllegalStateException(new javax.net.ssl.SSLHandshakeException("Trust validation failed"));
    };
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenThrow(failure);
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), calls,
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    var priorPlan = snapshot(requireRun(), "design-planning");

    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(PipelineGates.RECOVERY_ENVIRONMENT, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.times(1)).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    execute(runtime, "design-execution");
    assertEquals(1, calls.get());
    assertEquals(priorPlan, snapshot(requireRun(), "design-planning"));
    assertTrue(requireRun().transitions().stream().noneMatch(transition ->
        transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)));
    assertTrue(requireRun().attempts().stream().anyMatch(attempt -> attempt.failureEvidence() != null
        && attempt.failureEvidence().contains("PERMANENT_PROVIDER_FAILURE")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void completedTransientDiagnosisUsesOnlyTheRemainingDiagnosisCall(boolean recover) {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    var diagnoses = new AtomicInteger();
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      if (diagnoses.incrementAndGet() == 1 || !recover) {
        throw new dev.langchain4j.exception.HttpException(503, "Provider unavailable");
      }
      var json = mapper.readTree((String) call.getArgument(1));
      var ref = mapper.treeToValue(json.path("evidence").path("rejectedArtifactRefs").get(0), Reference.class);
      return new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, ref,
          List.of(json.path("evidence").path("failureId").asText()), RecoveryAction.REGENERATE_ARTIFACT,
          List.of(), "", "Correct the rejected output.");
    });
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), calls,
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    var result = execute(runtime, "design-execution");
    assertEquals(2, diagnoses.get());
    assertEquals(1, calls.get());
    if (recover) {
      assertInstanceOf(StageDecision.Retry.class, result.decision());
    } else {
      var wait = assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
      assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      execute(runtime, "design-execution");
      assertEquals(2, diagnoses.get());
    }
  }

  @Test
  void diagnosticParsingFailureGetsOneCorrectionThenSafeProducerFallback() {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenThrow(
            new dev.langchain4j.service.output.OutputParsingException("Invalid diagnostic JSON", null));
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), calls,
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    var priorPlan = snapshot(requireRun(), "design-planning");
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.times(2)).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    assertEquals(1, calls.get());
    assertEquals(priorPlan, snapshot(requireRun(), "design-planning"));
  }

  @Test
  void timedOutDiagnosisCannotPromoteALateReplyOrStartOverlappingRecovery() throws InterruptedException {
    var release = new java.util.concurrent.CountDownLatch(1);
    var finished = new java.util.concurrent.CountDownLatch(1);
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      try {
        while (release.getCount() > 0) {
          try { release.await(); }
          catch (InterruptedException ignored) { /* Simulate transport that cannot confirm cancellation. */ }
        }
        var json = mapper.readTree((String) call.getArgument(1));
        var ref = mapper.treeToValue(json.path("evidence").path("rejectedArtifactRefs").get(0), Reference.class);
        return new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, ref,
            List.of(json.path("evidence").path("failureId").asText()),
            RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Regenerate the output.");
      } finally { finished.countDown(); }
    });
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent, 12, Duration.ofMillis(100)), calls,
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    var priorPlan = snapshot(requireRun(), "design-planning");
    try {
      var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
      assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      assertEquals(1, calls.get());
      assertTrue(requireRun().attempts().stream().anyMatch(attempt -> attempt.failureEvidence() != null
          && attempt.failureEvidence().contains("TIMED_OUT")));
    } finally { release.countDown(); }
    assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(priorPlan, snapshot(requireRun(), "design-planning"));
    assertEquals(1, calls.get());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.times(1)).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING_MANDATORY_INPUT", "POLICY_FAILURE", "DOMAIN_FAILURE"})
  void syntheticOutputMarkerCannotAuthorizeRepairOfAKnownNonOutputFailure(String causeName) {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      var json = mapper.readTree((String) call.getArgument(1));
      return new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null,
          List.of(json.path("evidence").path("failureId").asText()), RecoveryAction.PARK, List.of(), "", "No output defect is established.");
    });
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), calls,
        RecoveryCause.of(RecoveryCauseCode.valueOf(causeName)));

    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(1, calls.get());
    assertTrue(requireRun().transitions().stream().noneMatch(transition ->
        transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PARK", "INVALID_REFERENCE"})
  void diagnosisReentryReusesSavedResultButANewProducerAttemptHasNewCredit(String mode) {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    var diagnoses = new AtomicInteger();
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      diagnoses.incrementAndGet();
      var json = mapper.readTree((String) call.getArgument(1));
      return new RecoveryDecision("PARK".equals(mode) ? RecoveryCauseClass.UNCLASSIFIED : RecoveryCauseClass.DERIVATION_DEFECT,
          "PARK".equals(mode) ? null : new Reference(Kind.CHAIN_PLAN_GRAPH, "unknown", "unknown"),
          List.of(json.path("evidence").path("failureId").asText()),
          "PARK".equals(mode) ? RecoveryAction.PARK : RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Inspect the generated output.");
    });
    var observed = new AtomicReference<RecoveryContext>();
    var narrative = org.mockito.Mockito.spy(new FailureNarrative(agent));
    org.mockito.Mockito.doAnswer(call -> {
      observed.set(call.getArgument(1));
      return call.callRealMethod();
    }).when(narrative).diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    var runtime = executionRecoveryRuntime(narrative, new AtomicInteger(), RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    var repair = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    int firstCalls = "PARK".equals(mode) ? 1 : 2;
    assertEquals(firstCalls, diagnoses.get());
    var executor = assertInstanceOf(ProductPipelineStageExecutor.class, runtime.stageExecutor());
    var firstContext = observed.get();
    var saved = executor.acceptedRecoveryDecision(RUN_ID, firstContext);
    assertEquals(saved, executor.acceptedRecoveryDecision(RUN_ID, observed.get()));
    assertEquals(firstCalls, diagnoses.get());
    assertTrue(requireRun().attempts().stream().anyMatch(attempt -> attempt.failureEvidence() != null
        && attempt.failureEvidence().contains(ProductPipelineRunSupport.RECOVERY_DIAGNOSIS_ATTR)));
    applyLifecycle(runtime, repair);
    execute(runtime, "design-execution");
    assertEquals(firstCalls * 2, diagnoses.get());
    assertEquals(saved, executor.acceptedRecoveryDecision(RUN_ID, firstContext));
    assertEquals(firstCalls * 2, diagnoses.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void theLastDiagnosisRequestBlocksRepairWithoutDoubleChargingOrRecordingADispatch(boolean transientFailure) {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxRequestsPerRun()).thenReturn(1);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    org.mockito.Mockito.when(agent.recover(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      budget.reserve(RUN_ID, 1, 1);
      assertNull(budget.state(RUN_ID).refusal());
      if (transientFailure) { throw new dev.langchain4j.exception.HttpException(503, "Provider unavailable"); }
      return null;
    });
    var calls = new AtomicInteger();
    var runtime = executionRecoveryRuntime(new FailureNarrative(agent), calls,
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    runtime.support().withLlmBudget(budget);

    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.times(1)).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    assertEquals(1, calls.get());
    assertEquals(1, budget.state(RUN_ID).requests());
    assertTrue(requireRun().transitions().stream().noneMatch(transition ->
        transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 12})
  void unavailableDiagnosisStillAllowsTheProvedProducerRepair(int diagnosisAllowance) {
    var agent = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    var narrative = diagnosisAllowance == 0 ? new FailureNarrative(agent, 0, null) : new FailureNarrative();
    var runtime = executionRecoveryRuntime(narrative, new AtomicInteger(),
        RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY));
    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).recover(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRefusedTokenReservationSpendsCorrectionCreditOnlyIfTransportWasAdmitted(boolean transportAdmitted) {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxTokensPerRun()).thenReturn(150L);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var calls = new AtomicInteger();
    var cause = new RecoveryCause(RecoveryCauseCode.SECURITY_POLICY,
        List.of(new PlanValidationFinding("SECURITY_POLICY", "Generated output was rejected.", true)), "");
    var execution = capability("execution-cap", context -> {
      if (calls.incrementAndGet() > 1) {
        if (transportAdmitted) { budget.reserve(context.runId(), 1, 1); }
        budget.reserve(context.runId(), 10, 10);
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.VALIDATION_FAILURE, "Generated output was rejected.",
          cause)));
    });
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(new FailureNarrative(), profile, analysisWithBrief(accessPolicyBrief(
        org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision.USE_RECOMMENDED)),
        planningAlwaysCandidate(), execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    runtime.support().withLlmBudget(budget);
    budget.reserve(RUN_ID, 70, 70);
    var repair = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, repair.decision());
    assertNull(budget.state(RUN_ID).refusal());
    applyLifecycle(runtime, repair);

    var wait = assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(transportAdmitted ? 2 : 1, budget.state(RUN_ID).requests());
    var doc = requireRun();
    var ledger = new org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger();
    var key = ledger.key("design-execution", cause,
        org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger
            .inputArtifactIdentity(doc, "design-execution"), doc.transitions());
    assertEquals(transportAdmitted ? 1 : 0, ledger.repairsUsed(doc.transitions(), key,
        org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED));
    assertTrue(doc.transitions().stream().anyMatch(transition ->
        transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)));
    runtime.recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION, "grant-after-unsent",
        "grant-after-unsent-hash", org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED))
        .collect().asList().await().indefinitely();
    assertEquals(1, ledger.repairsUsed(requireRun().transitions(), key,
        org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED),
        "Resuming an unsent correction must restore its intent without granting new defect credit");
  }

  private CreateChainTestOrchestrator executionRecoveryRuntime(FailureNarrative narrative,
      AtomicInteger calls, RecoveryCause cause) {
    var execution = capability("execution-cap", context -> {
      calls.incrementAndGet();
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
          StageOutcomeClass.VALIDATION_FAILURE, "Generated output was rejected.", cause)));
    });
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(narrative, profile, analysisWithBrief(accessPolicyBrief(
        org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision.USE_RECOMMENDED)),
        planningAlwaysCandidate(), execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    return runtime;
  }

  @Test
  void aPaidRepairCandidateCannotBeRefundedByAnUnsentApprovalRefinement() {
    var config = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.configuration.AppConfig.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(config.llm().maxTokensPerRun()).thenReturn(10L);
    var budget = new org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget(blobStore, mapper, config);
    var calls = new AtomicInteger();
    var cause = new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE,
        List.of(new PlanValidationFinding("CONTRACT_SHAPE", "Generated plan shape was rejected.", true)), "");
    var planning = capability("planning-cap", context -> {
      int call = calls.incrementAndGet();
      if (call == 1) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
            StageOutcomeClass.CONTRACT_FAILURE, "Generated plan shape was rejected.", cause)));
      }
      budget.reserve(context.runId(), call == 2 ? 1 : 10, call == 2 ? 1 : 10);
      return planningAlwaysCandidate().execute(context);
    });
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(new FailureNarrative(), profile, analysisWithBrief(accessPolicyBrief(
        org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision.USE_RECOMMENDED)), planning);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    runtime.support().withLlmBudget(budget);
    var intent = execute(runtime, "design-planning");
    assertInstanceOf(StageDecision.Retry.class, intent.decision());
    applyLifecycle(runtime, intent);
    assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, "design-planning").decision());
    var ledger = new org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger();
    var doc = requireRun();
    var key = ledger.key("design-planning", cause,
        org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger
            .inputArtifactIdentity(doc, "design-planning"), doc.transitions());
    assertEquals(1, ledger.repairsUsed(doc.transitions(), key,
        org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED));
    runtime.recordInput(new AcceptInputCommand(RUN_ID, "Revise plan wording"))
        .collect().asList().await().indefinitely();

    assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-planning").decision());

    assertEquals(1, budget.state(RUN_ID).requests());
    assertEquals(1, ledger.repairsUsed(requireRun().transitions(), key,
        org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED));
    assertTrue(requireRun().transitions().stream().noneMatch(transition -> transition.reason().startsWith(
        org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger.UNSENT_ATTEMPT_REASON_PREFIX)));
  }

  @Test
  void sourceCitedBriefRevisionCarriesDialogueAndRenewsApprovals() {
    var observed = new AtomicReference<RecoveryContext>();
    var narrative = org.mockito.Mockito.spy(new FailureNarrative());
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      RecoveryContext context = call.getArgument(1);
      observed.set(context);
      return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT,
          context.evidence().approvedBriefRef(), List.of(context.evidence().failureId(), context.authorInputs().getFirst().inputId()),
          RecoveryAction.REVISE_BRIEF,
          List.of(new ProposedBriefChange("endpoint", "httpMethod", "", "POST", "SOURCE_VALUE_OMITTED", false)),
          "", "Restore the method supplied in the original message."));
    });
    var endpoint = new org.qubership.integration.platform.ai.plan.RequirementFact("endpoint",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.ENDPOINT,
        "http-trigger", "Receive orders", "", "", "", "", "/orders-v2");
    var omitted = new RequirementBrief("Receive orders", List.of(), List.of(), List.of(), List.of(), "Receive orders")
        .withFacts(List.of(endpoint));
    var restored = omitted.withFacts(List.of(new org.qubership.integration.platform.ai.plan.RequirementFact("endpoint",
        endpoint.polarity(), endpoint.kind(), endpoint.capabilityKey(), endpoint.text(), "", "", "", "POST", "/orders-v2")));
    AtomicInteger analysisCalls = new AtomicInteger();
    var analysis = capability("analysis-cap", context -> {
      if (context.attributeAsString("userText") == null
          && !context.attributes().containsKey(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR)) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
            StageOutcomeClass.NEEDS_INPUT, "Supply requirements")));
      }
      int call = analysisCalls.incrementAndGet();
      if (call > 1) {
        assertEquals(List.of(new ProposedBriefChange("endpoint", "httpMethod", "", "POST", "SOURCE_VALUE_OMITTED", false)),
            context.attributes().get(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR));
      }
      return analysisWithBrief(call == 1 ? omitted : restored).execute(context);
    });
    AtomicInteger executionCalls = new AtomicInteger();
    var execution = capability("execution-cap", context -> executionCalls.incrementAndGet() == 1
        ? executionValidationFailure(new AtomicInteger()).execute(context)
        : Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.SUCCEEDED, "ready"))));
    var profile = analysisThenPlanningThenExecutionProfile();
    var runtime = newRuntime(narrative, profile, analysis, planningAlwaysCandidate(), execution);
    attachAuthorSources(runtime, "Accept POST /orders", "Set timeout to 30", "Change the path to /orders-v2");
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    var repair = execute(runtime, "design-execution");
    var reopen = assertInstanceOf(StageDecision.ReopenProducer.class, repair.decision());
    assertEquals("requirement-analysis", reopen.producerStageId());
    assertEquals(omitted, observed.get().approvedBrief());
    assertEquals(List.of("Accept POST /orders", "Set timeout to 30", "Change the path to /orders-v2"),
        observed.get().authorInputs().stream().map(org.qubership.integration.platform.ai.productpipeline.artifact.UserInput::text).toList());
    applyLifecycle(runtime, repair);
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    assertInstanceOf(StageDecision.Complete.class, execute(runtime, "design-execution").decision());
    assertEquals(RUN_ID, requireRun().run().runId());
    assertEquals(2, analysisCalls.get());
    assertEquals(2, executionCalls.get());
    assertEquals(restored, artifactStore.payload(artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow(),
        RequirementBrief.class));
  }

  private static RequirementBrief accessPolicyBrief(
      org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision choice) {
    var policy = new org.qubership.integration.platform.ai.plan.RequirementPolicyConflict(
        "CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC", "access-choice", choice);
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("access",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.CONSTRAINT,
        "http-trigger", "External access", "", "", "", "", "", "", List.of("entry"), policy);
    return new RequirementBrief("External access", List.of(), List.of(), List.of(), List.of(), "External access")
        .withFacts(List.of(fact));
  }

  private StageCapability analysisWithBrief(RequirementBrief brief) {
    return capability("analysis-cap", context -> Multi.createFrom().item(new CapabilitySignal.Completed(
        new StageOutcome(StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())), "brief ready", null))));
  }

  private static FailureNarrative sourceAwarePolicyNarrative(AtomicInteger calls) {
    FailureNarrative narrative = org.mockito.Mockito.spy(new FailureNarrative());
    org.mockito.Mockito.when(narrative.diagnoseRecovery(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(RecoveryContext.class))).thenAnswer(call -> {
      calls.incrementAndGet();
      RecoveryContext context = call.getArgument(1);
      return FailureNarrative.RecoveryResult.reply(new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT,
          context.evidence().approvedBriefRef(), List.of(context.evidence().failureId()), RecoveryAction.ASK_USER,
          List.of(), "Keep NONE or use the recommended RBAC?", "RBAC is recommended for external access.",
          new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence(
              "entry", "accessControlType",
              org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.POLICY_CHOICE,
              context.authorInputs().stream().map(org.qubership.integration.platform.ai.productpipeline.artifact.UserInput::inputId)
                  .toList())));
    });
    return narrative;
  }

  private static void attachAuthorSources(CreateChainTestOrchestrator runtime, String... texts) {
    var archive = new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive(
        new InMemoryArtifactBlobStore(), new ObjectMapper().findAndRegisterModules());
    for (String text : texts) {
      var input = archive.beginInput(CONVERSATION, text, null);
      archive.resolveInput(CONVERSATION, input, text);
    }
    var conversations = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.chat.conversation.ConversationService.class);
    org.mockito.Mockito.when(conversations.archive()).thenReturn(archive);
    ((ProductPipelineStageExecutor) runtime.stageExecutor()).withConversations(conversations);
  }

  @Test
  void aMissingGeneratedPropertyRepairsExecutionInsteadOfAskingTheAuthor() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionPlanFillValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult failed = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, failed.decision());
    applyLifecycle(runtime, failed);

    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void aMissingGeneratedValueUsesTheApprovedValueAndPreservesItsApproval() {
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent(
        "send", "http-sender",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        null, null, null, null, List.of(new PlanProperty("httpMethod", "POST")), List.of());
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact(
        "send-config", org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR,
        "", "Send the request with POST.", "", "", "", "", "", "", List.of(), null, intent);
    RequirementBrief supplied = new RequirementBrief(
        "Send an event", List.of(), List.of(), List.of(), List.of(), "Send an event")
        .withFacts(List.of(fact));
    StageCapability analysis = capability("analysis-cap", context -> Multi.createFrom().item(
        new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, supplied, List.of())), "brief ready", null))));
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> correction = new AtomicReference<>();
    StageCapability execution = capability("execution-cap", context -> {
      if (calls.incrementAndGet() == 1) {
        var finding = new PlanValidationFinding(RecoveryCauseCode.MISSING_REQUIRED_PROPERTY.name(),
            "Missing required property httpMethod on http-sender", true);
        return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(
            StageOutcomeClass.VALIDATION_FAILURE,
            List.of(new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT,
                new PlanValidationResult(List.of(finding)), List.of())), finding.message(), null)));
      }
      correction.set(context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "sender repaired")));
    });
    CreateChainTestOrchestrator runtime = newRuntime(new FailureNarrative(
        FakeFailureNarrativeAgent.owner("", "design-execution")), profile,
        analysis, planningAlwaysCandidate(), execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    String approval = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();

    StageExecutionResult rejected = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, rejected.decision());
    applyLifecycle(runtime, rejected);
    StageDecision.Complete complete = assertInstanceOf(StageDecision.Complete.class,
        execute(runtime, "design-execution").decision());

    assertEquals(RunStatus.PLAN_APPROVED, complete.status());
    assertEquals(2, calls.get());
    assertTrue(correction.get().contains("httpMethod"));
    assertEquals(approval, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    var saved = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow();
    assertEquals(supplied, artifactStore.payload(saved, RequirementBrief.class));
  }

  @Test
  void sequentialFailuresCanReopenDifferentOwnersAndStillComplete() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionWithSequentialOwnerFailures(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult firstFailure = execute(runtime, "design-execution");
    StageDecision.ReopenProducer firstReopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, firstFailure.decision());
    assertEquals("requirement-analysis", firstReopen.producerStageId());
    applyLifecycle(runtime, firstFailure);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult secondFailure = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, secondFailure.decision());
    applyLifecycle(runtime, secondFailure);

    StageDecision.Complete complete =
        assertInstanceOf(
            StageDecision.Complete.class, execute(runtime, "design-execution").decision());

    assertEquals(RunStatus.PLAN_APPROVED, complete.status());
    assertEquals(RunStatus.PLAN_APPROVED, requireRun().run().status());
    assertEquals(3, executionCalls.get());
    assertEquals(
        1,
        requireRun().transitions().stream()
            .filter(transition -> RecoveryAttemptLedger.isReopenReason(transition.reason()))
            .count());
  }

  @Test
  void restartRetainsTheRepairBudgetAndApprovedInputsForAMissingGeneratedProperty() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    StageCapability analysis = analysisCandidate();
    StageCapability planning = planningAlwaysCandidate();
    StageCapability execution = executionPlanFillValidationFailure(executionCalls);
    CreateChainTestOrchestrator first =
        newRuntime(new FailureNarrative(agent), profile, analysis, planning, execution);
    startAndRecordInput(first, profile);
    approveStage(first, "requirement-analysis");
    approveStage(first, "design-planning");
    String approvedBriefId = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();
    String approvedPlanId = snapshot(requireRun(), "design-planning").approvedArtifactId();

    StageExecutionResult failure = execute(first, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, failure.decision());
    applyLifecycle(first, failure);

    CreateChainTestOrchestrator restarted =
        newRuntime(new FailureNarrative(agent), profile, analysis, planning, execution);
    restarted
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "requirement-analysis").status());
    assertEquals(
        approvedBriefId, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "design-planning").status());
    assertEquals(approvedPlanId, snapshot(requireRun(), "design-planning").approvedArtifactId());
    assertEquals(3, executionCalls.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unknownPropertyRepairsExecutionAndPreservesApprovedInputsAcrossRestart(
      boolean repairSucceeds) {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("", "design-planning");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    AtomicReference<String> repairError = new AtomicReference<>();
    StageCapability rejecting = executionUnknownPropertyValidationFailure(executionCalls);
    StageCapability execution =
        capability(
            "execution-cap",
            context -> {
              if (executionCalls.get() > 0) {
                repairError.set(
                    context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
                if (repairSucceeds) {
                  executionCalls.incrementAndGet();
                  return Multi.createFrom()
                      .item(
                          new CapabilitySignal.Completed(
                              StageOutcome.of(StageOutcomeClass.SUCCEEDED, "execution repaired")));
                }
              }
              return rejecting.execute(context);
            });
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            execution);
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    String approvedBriefId = snapshot(requireRun(), "requirement-analysis").approvedArtifactId();
    String approvedPlanId = snapshot(requireRun(), "design-planning").approvedArtifactId();

    StageDecision.Retry retry =
        assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    assertEquals("design-execution", retry.stageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(1, executionCalls.get());
    assertEquals(0, agent.calls.get());

    CreateChainTestOrchestrator restarted =
        newRuntime(
            new FailureNarrative(agent), profile, analysisCandidate(), planningAlwaysCandidate(), execution);
    restarted
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();
    StageDecision repaired = execute(restarted, "design-execution").decision();
    if (repairSucceeds) {
      assertInstanceOf(StageDecision.Complete.class, repaired);
      assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "design-execution").status());
    } else {
      StageDecision.WaitForInput wait = assertInstanceOf(StageDecision.WaitForInput.class, repaired);
      assertEquals(
          PipelineGates.RECOVERY_REPAIR_ALLOWANCE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      assertTrue(PipelineGates.guardOf(wait.prompt()).isEmpty());
      assertTrue(
          PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
              .orElseThrow()
              .contains("unknown property key 'topic'"));
      assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    }
    assertEquals(repairSucceeds ? 2 : 3, executionCalls.get());
    assertTrue(repairError.get().contains("unknown property key 'topic'"));
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "requirement-analysis").status());
    assertEquals(approvedBriefId, snapshot(requireRun(), "requirement-analysis").approvedArtifactId());
    assertEquals(StageStatus.SUCCEEDED, snapshot(requireRun(), "design-planning").status());
    assertEquals(approvedPlanId, snapshot(requireRun(), "design-planning").approvedArtifactId());
  }

  @Test
  void aCaptureContractFailureWithoutFindingsReachesCleanCorrectionBeforePausing() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("", "requirement-analysis");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionCaptureContractFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    assertInstanceOf(StageDecision.Retry.class, execute(runtime, "design-execution").decision());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals(1, executionCalls.get());

    StageExecutionResult clean = execute(runtime, "design-execution");
    assertInstanceOf(StageDecision.Retry.class, clean.decision());
    applyLifecycle(runtime, clean);

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(
        PipelineGates.RECOVERY_REPAIR_ALLOWANCE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.guardOf(wait.prompt()).isEmpty());
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("Cannot deserialize"));
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(3, executionCalls.get());
  }

  @Test
  void reviseReentersTheCurrentUnapprovedStageWithErrorContext() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The current stage can fix this.", "work");
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenUserText = new AtomicReference<>();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              seenError.set(
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
              seenUserText.set(context.attributeAsString("userText"));
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain")));
            });
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "fail-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "work",
                    "fail-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("work", "PLAN_APPROVED"),
            List.of("fail-cap"));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(wait.prompt()).orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    execute(runtime, "work");

    assertEquals("bad domain", seenError.get());
    assertEquals("provided input", seenUserText.get());
  }

  @Test
  void approvedDraftTextMismatchHaltsWithRetryAndReviseWithoutOverwritingRequirements() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Capture did not match the approved draft.", "work");
    AtomicReference<String> seenUserText = new AtomicReference<>();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              seenUserText.set(context.attributeAsString("userText"));
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              "Requirement brief coverage failed: requirement brief"
                                  + " approvedDraftText does not match approved draft")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait = waitAfterOptionalSemanticRepair(runtime, "work");
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED)
            .contains(PipelineGates.RETRY_ACTION));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(
        PipelineGates.RECOVERY_REPEATED,
        PipelineGates.gateOf(requireRun().transitions().getLast().reason()).orElseThrow());
  }

  @Test
  void retryWithAnUnchangedAttemptKeyIsRefusedWithoutRerunningTheStage() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Capture did not match the approved draft.", "work");
    AtomicInteger calls = new AtomicInteger();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              calls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              "Requirement brief coverage failed")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);
    waitAfterOptionalSemanticRepair(runtime, "work");
    int afterHalt = calls.get();
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);

    List<PipelineSignal> signals =
        runtime
            .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(afterHalt, calls.get());
    assertTrue(
        signals.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        signals.toString());
    String prompt =
        requireRun().transitions().get(requireRun().transitions().size() - 1).reason();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElseThrow());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
  }

  @Test
  void technicalPolicyAndMissingInputStayRetryOnlyEvenWithAFakeOwner() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.owner("Would blame work.", "work");
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore =
        new ProductPipelineArtifactStore(new CompilationArtifacts(blobStore, mapper, clock));
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.MISSING_MANDATORY_INPUT,
                                "MISSING_MANDATORY_INPUT closed"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION), ChatEvent.actionsForGate(PipelineGates.STAGE_RETRY));
  }

  @Test
  void diagnosisPassFollowUpTextWhenPresent() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "work");
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "use a different service"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "work");

    assertEquals("use a different service", agent.lastFollowUp.get());
  }

  @Test
  void domainFailureHaltsWithRetryGateInsteadOfFailingTheRun() {
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain"))));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "fail-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "work",
                    "fail-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("work", "PLAN_APPROVED"),
            List.of("fail-cap"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "work");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
    assertEquals("work", wait.stageId());
    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.strip(wait.prompt()).contains("bad domain"), wait.prompt());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    assertNotEquals(RunStatus.FAILED, doc.run().status());
    assertEquals(StageStatus.WAITING_FOR_INPUT, stageStatus(doc, "work"));
    assertTrue(
        result.signals().stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance));
    assertTrue(result.signals().stream().noneMatch(PipelineSignal.Failed.class::isInstance));
  }

  @Test
  void haltCardUsesModelNarrativeInsteadOfRawEvidence() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("The catalog could not find that service.");
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.DOMAIN_FAILURE,
                                List.of(
                                    new ArtifactCandidate(
                                        Kind.PLAN_VALIDATION_RESULT,
                                        new PlanValidationResult(
                                            List.of(
                                                new PlanValidationFinding(
                                                    "PLAN_BLOCKER", "missing quartz", true))),
                                        List.of())),
                                "bad domain",
                                null))));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "fail-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "work",
                    "fail-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(new ArtifactTypeRef("plan-validation-result", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("work", "PLAN_APPROVED"),
            List.of("fail-cap"));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.STAGE_REVISE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(
        PipelineGates.strip(wait.prompt()).contains("The catalog could not find that service."),
        wait.prompt());
    assertEquals("DOMAIN_FAILURE", agent.lastOutcome.get());
    assertEquals("bad domain", agent.lastException.get());
    assertTrue(agent.lastFindings.get().contains("PLAN_BLOCKER"), agent.lastFindings.get());
    assertTrue(agent.lastFindings.get().contains("missing quartz"), agent.lastFindings.get());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
  }

  @Test
  void haltKeepsRawEvidenceWhenNarrativeTurnFails() {
    FakeFailureNarrativeAgent boom = FakeFailureNarrativeAgent.boom();
    StageCapability failing =
        capability(
            "fail-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain"))));
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(boom), profile, failing);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.strip(wait.prompt()).contains("bad domain"), wait.prompt());
    String body = PipelineGates.strip(wait.prompt()).toLowerCase();
    assertFalse(body.contains("something went wrong"), wait.prompt());
    assertFalse(body.contains("please try"), wait.prompt());
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION),
        ChatEvent.actionsForGate(PipelineGates.STAGE_REVISE));
  }

  @Test
  void technicalHaltNarratesWithoutOfferingPlanRepair() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("The connection dropped while calling the catalog.");
    StageCapability alwaysFail =
        capability(
            "retry-cap",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                List.of(),
                                "persistent transport failure",
                                1L))));
    ProductPipelineProfile profile = retryProfile("work", "retry-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, alwaysFail);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        "The connection dropped while calling the catalog.", PipelineGates.strip(wait.prompt()));
    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.gateOf(wait.prompt()).orElseThrow()));
    assertEquals(
        "persistent transport failure",
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt()).orElseThrow());
    assertEquals(1L, PipelineGates.recoveryRetryDelayMsOf(wait.prompt()).orElseThrow());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertNotEquals(RunStatus.FAILED, requireRun().run().status());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.FAILED, requireRun().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.FAILURE_RECORD).isPresent());
  }

  @Test
  void exhaustedToolArgumentsTechnicalFailureHaltsWithRetryOnly() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("A tool call used invalid arguments.");
    StageCapability alwaysFail =
        capability(
            "design-execution",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                "Cannot deserialize value of type `NamingManifest`"))));
    ProductPipelineProfile profile =
        retryProfile("design-execution", "design-execution", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, alwaysFail);
    startAndRecordInput(runtime, profile);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "design-execution")
            .decision());

    assertEquals(
        PipelineGates.RECOVERY_RETRY_TECHNICAL,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_RETRY_TECHNICAL));
    assertFalse(wait.prompt().toLowerCase().contains("revise"), wait.prompt());
    assertNull(agent.lastCandidateSet.get());
    assertEquals("RETRYABLE_TECHNICAL_FAILURE", agent.lastOutcome.get());
  }

  @Test
  void retryReentersTheSameStageWithoutOverwritingRequirements() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<String> seenUserText = new AtomicReference<>();
    AtomicReference<String> seenDiscovery = new AtomicReference<>();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              attempts.incrementAndGet();
              seenUserText.set(context.attributeAsString("userText"));
              seenDiscovery.set(context.attributeAsString("discoveryUserText"));
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    int userInputsAfterHalt = artifactStore.history(RUN_ID, Kind.USER_INPUT).size();
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("work", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    execute(runtime, "work");

    assertEquals(2, attempts.get());
    assertEquals("provided input", seenUserText.get());
    assertEquals("provided input", seenDiscovery.get());
    assertEquals(userInputsAfterHalt, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
  }



  @Test
  void haltFollowUpBareGoBackAtOwnerChoiceNamesTheGuardAndAdvances() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The plan omitted RBAC.", "planning");
    ArtifactTypeRef draft = new ArtifactTypeRef("requirement-draft", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile =
        discoveryAndAnalysisThenPlanningProfile(draft, brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            discoveryCandidate(),
            analysisAlwaysCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-discovery");
    approveStage(runtime, "requirement-analysis");

    execute(runtime, "planning");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "go back to requirements and add RBAC"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED,
        PipelineGates.gateOf(
                requireRun().transitions().get(requireRun().transitions().size() - 1).reason())
            .orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "go back"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(StageStatus.RUNNING, snapshot(requireRun(), "requirement-analysis").status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "planning").status());
  }


  @Test
  void haltFollowUpNamingAStageOutsideTheSetKeepsTheDiagnosticCard() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner(
            "Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionUnspecifiedDomainFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "go back to compiler and add RBAC"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(1, executionCalls.get());
    long reopenCount =
        requireRun().transitions().stream()
            .filter(transition -> RecoveryAttemptLedger.isReopenReason(transition.reason()))
            .count();
    assertEquals(0, reopenCount);
    String listed =
        requireRun().transitions().get(requireRun().transitions().size() - 1).reason();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(listed).orElseThrow());
    assertEquals(
        HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET.name(),
        PipelineGates.guardOf(listed).orElseThrow());
  }

  @Test
  void haltFollowUpNamingRequirementsAsksWhenTwoRequirementStagesMatch() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The plan omitted RBAC.", "planning");
    ArtifactTypeRef draft = new ArtifactTypeRef("requirement-draft", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = discoveryAndAnalysisThenPlanningProfile(draft, brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            discoveryCandidate(),
            analysisAlwaysCandidate(),
            planningDomainFailure());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-discovery");
    approveStage(runtime, "requirement-analysis");

    execute(runtime, "planning");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "go back to requirements and add RBAC"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    String prompt =
        requireRun().transitions().get(requireRun().transitions().size() - 1).reason();
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(prompt).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(prompt).isEmpty());
  }

  @Test
  void retryAfterANamedStageFollowUpDoesNotOverwriteRequirements() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<String> seenUserText = new AtomicReference<>();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              attempts.incrementAndGet();
              seenUserText.set(context.attributeAsString("userText"));
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "go back to compiler and add RBAC"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "work");

    assertEquals(1, attempts.get());
    assertEquals("provided input", seenUserText.get());
  }

  @Test
  void haltFollowUpStaysWaitingAndKeepsCorrectionForTheNextTurn() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<String> seenUserText = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              attempts.incrementAndGet();
              seenUserText.set(context.attributeAsString("userText"));
              seenFollowUp.set(
                  context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR));
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "bad domain")));
            });
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);

    execute(runtime, "work");
    int requirementInputs = artifactStore.history(RUN_ID, Kind.USER_INPUT).size();
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "use a different service"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.RUNNING, requireRun().run().status());
    assertEquals("work", requireRun().run().currentStageId());
    assertEquals(
        "use a different service", runtime.support().haltFollowUpText(RUN_ID).orElseThrow());
    assertEquals(1, attempts.get());

    execute(runtime, "work");

    assertEquals(2, attempts.get());
    assertEquals("provided input", seenUserText.get());
    assertEquals("use a different service", seenFollowUp.get());
    assertEquals(
        requirementInputs + 1, artifactStore.history(RUN_ID, Kind.USER_INPUT).size());
  }

  @Test
  void terminalSuccessReturnsComplete() {
    AtomicInteger calls = new AtomicInteger();
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "complete-profile",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "only",
                    "only-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("only", "CHAIN_MATERIALIZED"),
            List.of("only-cap"));
    CreateChainTestOrchestrator runtime = newRuntime(profile, succeeding("only-cap", calls));
    startAndRecordInput(runtime, profile);

    StageExecutionResult result = execute(runtime, "only");

    StageDecision.Complete complete =
        assertInstanceOf(StageDecision.Complete.class, result.decision());
    assertEquals("only", complete.stageId());
    assertEquals(RunStatus.CHAIN_MATERIALIZED, complete.status());
    assertEquals(1, calls.get());
    assertEquals(RunStatus.CHAIN_MATERIALIZED, requireRun().run().status());
  }

  @Test
  void runtimeStillAdvancesTheCursorAfterTheStageSeamReturnsContinue() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    ProductPipelineProfile profile = twoStageSuccessProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            succeeding("flow-first", firstCalls),
            succeeding("flow-second", secondCalls));
    startAndRecordInput(runtime, profile);

    runtime.executeStage(RUN_ID, "first").collect().asList().await().indefinitely();

    assertEquals(1, firstCalls.get());
    assertEquals(0, secondCalls.get());
    assertEquals("second", requireRun().run().currentStageId());
    assertEquals(RunStatus.RUNNING, requireRun().run().status());
  }

  @Test
  void aRepeatedContinueCannotAdvanceAnUnexecutedSuccessor() {
    var firstCalls = new AtomicInteger();
    var secondCalls = new AtomicInteger();
    var profile = twoStageSuccessProfile();
    var runtime = newRuntime(profile, succeeding("flow-first", firstCalls), succeeding("flow-second", secondCalls));
    startAndRecordInput(runtime, profile);
    var completed = execute(runtime, "first");
    applyLifecycle(runtime, completed);
    long revision = requireRun().run().runRevision();
    applyLifecycle(runtime, completed);
    assertEquals(revision, requireRun().run().runRevision());
    assertEquals("second", requireRun().run().currentStageId());
    assertEquals(0, secondCalls.get());
  }

  private StageExecutionResult execute(CreateChainTestOrchestrator runtime, String stageId) {
    return runtime.stageExecutor().execute(RUN_ID, stageId).await().indefinitely();
  }

  /**
   * This fixture configures one targeted correction and one clean regeneration. Other
   * outcomes park on the first halt.
   */
  private StageDecision.WaitForInput waitAfterOptionalSemanticRepair(
      CreateChainTestOrchestrator runtime, String stageId) {
    StageExecutionResult result = execute(runtime, stageId);
    int retries = 0;
    while (result.decision() instanceof StageDecision.Retry retry) {
      if (retries == 0) {
        assertEquals(Duration.ZERO, retry.delay());
      }
      applyLifecycle(runtime, result);
      result = execute(runtime, stageId);
      assertTrue(++retries <= 2, "recovery exceeded its bounded automatic retries");
    }
    return assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
  }

  private void applyLifecycle(
      CreateChainTestOrchestrator runtime, StageExecutionResult result) {
    runtime
        .support()
        .applyStageLifecycle(RUN_ID, result)
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void startAndRecordInput(CreateChainTestOrchestrator runtime, ProductPipelineProfile profile) {
    runtime
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest(profile)))
        .collect()
        .asList()
        .await()
        .indefinitely();
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "provided input"))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  /** Applies a diagnosed-owner reopen from planning back to analysis. */
  private void reopenPlanningToAnalysis(CreateChainTestOrchestrator runtime) {
    StageExecutionResult failed = execute(runtime, "planning");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("analysis", reopen.producerStageId());
    applyLifecycle(runtime, failed);
    applyLifecycle(runtime, execute(runtime, "analysis"));
  }

  /** Clicks Revise on a diagnosed-owner halt so causal reopen actually runs. */
  private void pickThenRevise(CreateChainTestOrchestrator runtime, String ownerStageId) {
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private CreateChainTestOrchestrator newRuntime(
      ProductPipelineProfile ignoredProfile, StageCapability... capabilities) {
    return newRuntime(new FailureNarrative(), ignoredProfile, capabilities);
  }

  private CreateChainTestOrchestrator newRuntime(
      FailureNarrative narrative,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    return newRuntime(narrative, ignoredProfile, null, capabilities);
  }

  private CreateChainTestOrchestrator newRuntime(
      FailureNarrative narrative,
      ProductPipelineProfile ignoredProfile,
      MappingTurnAdapter mappingTurnAdapter,
      StageCapability... capabilities) {
    ProductPipelineRunSupport.Builder builder =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(capabilities)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(narrative)
            .recoveryLedger(new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 12, 1)));
    if (mappingTurnAdapter != null) {
      builder.mappingTurnAdapter(mappingTurnAdapter);
    }
    return new CreateChainTestOrchestrator(builder.build(), runStore);
  }

  private CreateChainTestOrchestrator newRuntime(
      int repeatedFailureThreshold,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    return newRuntime(repeatedFailureThreshold,
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 12, 1)), ignoredProfile, capabilities);
  }

  private CreateChainTestOrchestrator newRuntime(
      int repeatedFailureThreshold,
      RecoveryAttemptLedger ledger,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    return new CreateChainTestOrchestrator(
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(capabilities)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(new FailureNarrative())
            .cacheIdleTimeout(Duration.ofHours(1))
            .repeatedFailureThreshold(repeatedFailureThreshold)
            .recoveryLedger(ledger)
            .build(),
        runStore);
  }

  private ProductPipelineRunDocument requireRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private static StageStatus stageStatus(ProductPipelineRunDocument doc, String stageId) {
    return doc.run().stages().stream()
        .filter(stage -> stage.stageId().equals(stageId))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static ProductPipelineProfile analysisThenPlanningProfile(
      ArtifactTypeRef brief, ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "validation-reopen",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("analysis-cap", "planning-cap"));
  }

  private static ProductPipelineProfile requirementAnalysisThenPlanningProfile(
      ArtifactTypeRef brief, ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "brief-recovery-reopen",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("analysis-cap", "planning-cap"));
  }

  private static ProductPipelineProfile planningThenExecutionProfile() {
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "plan-then-exec",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "design-planning",
                "planning-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(plan),
                new ApprovalPolicy(plan),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(plan),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("planning-cap", "execution-cap"));
  }

  private static ProductPipelineProfile analysisThenDesignInputThenExecutionProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flow = new ArtifactTypeRef("chain-semantic-revision", 1);
    return new ProductPipelineProfile(
        1,
        "analysis-then-design-input-then-execution",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-input",
                "design-input-cap",
                List.of(brief),
                List.of(flow),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(flow),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("analysis-cap", "design-input-cap", "execution-cap"));
  }

  private static ProductPipelineProfile analysisThenDesignInputProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flow = new ArtifactTypeRef("chain-semantic-revision", 1);
    return new ProductPipelineProfile(
        1,
        "analysis-then-design-input",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-input",
                "design-input-cap",
                List.of(brief),
                List.of(flow),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-input", "PLAN_APPROVED"),
        List.of("analysis-cap", "design-input-cap"));
  }

  private static ProductPipelineProfile analysisThenPlanningThenExecutionWithGraphProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ArtifactTypeRef graph = new ArtifactTypeRef("chain-plan-graph", 1);
    return new ProductPipelineProfile(
        1,
        "analysis-plan-exec-graph",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                List.of(brief),
                List.of(),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "design-planning",
                "planning-cap",
                List.of(brief),
                List.of(),
                List.of(plan),
                List.of(),
                new ApprovalPolicy(plan),
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(plan),
                List.of(),
                List.of(validation),
                List.of(graph),
                null,
                null,
                new RetryPolicy(0, 1L),
                null)),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("analysis-cap", "planning-cap", "execution-cap"));
  }

  private static ProductPipelineProfile analysisThenPlanningThenExecutionProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "analysis-plan-exec",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-planning",
                "planning-cap",
                List.of(brief),
                List.of(plan),
                new ApprovalPolicy(plan),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(plan),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("analysis-cap", "planning-cap", "execution-cap"));
  }

  private static ProductPipelineProfile discoveryAndAnalysisThenPlanningProfile(
      ArtifactTypeRef draft, ArtifactTypeRef brief, ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "discovery-analysis-planning",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                "discovery-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(draft),
                new ApprovalPolicy(draft),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(draft),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("discovery-cap", "analysis-cap", "planning-cap"));
  }

  private StageCapability discoveryCandidate() {
    return capability(
        "discovery-cap",
        context -> {
          if (context.attributeAsString("userText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
          }
          RequirementDraft payload = new RequirementDraft(true, "draft");
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, payload, List.of())),
                          "draft ready",
                          null)));
        });
  }

  private StageCapability analysisRepairCandidate(
      AtomicReference<String> seenError, AtomicReference<String> seenFollowUp) {
    return capability(
        "analysis-cap",
        context -> {
          seenError.set(
              context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
          seenFollowUp.set(
              context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR));
          return analysisCandidate().execute(context);
        });
  }

  private StageCapability planningRepairCandidate(
      AtomicReference<String> seenError, AtomicReference<String> seenFollowUp) {
    return capability(
        "planning-cap",
        context -> {
          seenError.set(
              context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
          seenFollowUp.set(
              context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR));
          String error =
              context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
          boolean repairing = error != null && !error.isBlank();
          if (!repairing && context.attributeAsString("userText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
          }
          Map<String, String> payload =
              repairing ? Map.of("plan", "repaired") : Map.of("plan", "ok");
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(
                              new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, payload, List.of())),
                          "plan ready",
                          null)));
        });
  }

  private static void assertMissingValueQuestion(StageDecision decision, String findingText) {
    StageDecision.WaitForInput wait = assertInstanceOf(StageDecision.WaitForInput.class, decision);
    assertEquals(
        PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(wait.prompt().contains(findingText), wait.prompt());
  }

  private StageCapability executionMissingBriefFactsValidationFailure(
      AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(
                                      List.of(
                                          new PlanValidationFinding(
                                              RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
                                              "The approved requirement brief is missing required"
                                                  + " facts",
                                              true))),
                                  List.of())),
                          "Phase 5 plan validation failed. Findings: MISSING_BRIEF_FACTS: The"
                              + " approved requirement brief is missing required facts",
                          null)));
        });
  }

  private StageCapability executionRbacValidationFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(
                                      List.of(
                                          new PlanValidationFinding(
                                              "security-policy-choice",
                                              "External route requires accessControlType=RBAC",
                                              true, null, "entry", "/properties/accessControlType"))),
                                  List.of())),
                          "Phase 5 plan validation failed. Findings: security-1: External"
                              + " route requires accessControlType=RBAC",
                          null)));
        });
  }

  private StageCapability executionPlanFillValidationFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(
                                      List.of(
                                          new PlanValidationFinding(
                                              RecoveryCauseCode.MISSING_REQUIRED_PROPERTY.name(),
                                              "Missing required property on http-trigger",
                                              true))),
                                  List.of())),
                          "Phase 5 plan validation failed. Findings: plan-1: Missing required"
                              + " property on http-trigger",
                          null)));
        });
  }

  private StageCapability executionWithSequentialOwnerFailures(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          int call = executionCalls.incrementAndGet();
          if (call == 1) {
            return executionMissingBriefFactsValidationFailure(new AtomicInteger()).execute(context);
          }
          if (call == 2) {
            return executionPlanFillValidationFailure(new AtomicInteger()).execute(context);
          }
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.SUCCEEDED,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(List.of()),
                                  List.of())),
                          "plan validation passed",
                          null)));
        });
  }

  private StageCapability executionUnspecifiedDomainFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.DOMAIN_FAILURE,
                          "planning validation failed. Findings: PLAN_BLOCKER: missing quartz")));
        });
  }

  private StageCapability executionGraphSchemaValidationFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          ChainPlanGraph graph =
              new ChainPlanGraph(
                  "1.0",
                  new ChainSection("c1", "HealthProxy"),
                  List.of(failingServiceCallNode()),
                  List.of());
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          List.of(
                              new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, List.of())),
                          "service-call schema validation failed",
                          null)));
        });
  }

  private static ChainPlanNode failingServiceCallNode() {
    return new ChainPlanNode(
        "call-1",
        "service-call",
        "Get inventory",
        null,
        null,
        List.of(
            new PlanProperty("integrationOperationProtocolType", "http"),
            new PlanProperty("integrationSystemId", "sys-1"),
            new PlanProperty("integrationSpecificationGroupId", "grp-1"),
            new PlanProperty("integrationSpecificationId", "spec-1"),
            new PlanProperty("integrationOperationId", "op-1"),
            new PlanProperty("integrationOperationMethod", "GET"),
            new PlanProperty("integrationOperationPath", "/store/inventory")));
  }

  private StageCapability executionValidationFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(
                                      List.of(
                                          new PlanValidationFinding(
                                              "PLAN_BLOCKER", "invalid graph edge", true))),
                                  List.of())),
                          "execution validation failed",
                          null)));
        });
  }

  private StageCapability executionRejectedPlanFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.VALIDATION_FAILURE, "plan cannot be executed")));
        });
  }

  private StageCapability executionUnknownPropertyValidationFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.VALIDATION_FAILURE,
                          "Structure validation failed:\n"
                              + "node 'kafka-trigger-1' (kafka-trigger-2) has unknown"
                              + " property key 'topic'.",
                          RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY))));
        });
  }

  private StageCapability executionCaptureContractFailure(AtomicInteger executionCalls) {
    return capability(
        "execution-cap",
        context -> {
          executionCalls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.CONTRACT_FAILURE,
                          "Cannot deserialize value of type `java.lang.String` from Object"
                              + " value (token `JsonToken.START_OBJECT`)")));
        });
  }

  private StageCapability analysisCandidate() {
    return capability(
        "analysis-cap",
        context -> {
          String error =
              context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
          boolean repairing = error != null && !error.isBlank();
          if (!repairing && context.attributeAsString("userText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
          }
          String goal = repairing ? "repaired goal" : "goal";
          RequirementBrief payload =
              new RequirementBrief(goal, List.of(), List.of(), List.of(), List.of(), goal);
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, payload, List.of())),
                          "brief ready",
                          null)));
        });
  }

  /**
   * Fails the first turn with a validation artifact attached and succeeds without producing
   * anything afterwards, recording per turn what the halted attempt made readable and what the
   * declared-input resolution handed over.
   */
  private StageCapability planningRecordingPriorOutputs(
      List<List<Reference>> priorPerTurn, List<List<Reference>> inputsPerTurn) {
    return capability(
        "planning-cap",
        context -> {
          StageRepairEvidence repair = StageRepairEvidence.from(context);
          if (repair == null && context.attributeAsString("discoveryUserText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
          }
          priorPerTurn.add(repair == null ? List.of() : repair.priorOutputRefs());
          inputsPerTurn.add(context.inputRefs());
          if (priorPerTurn.size() > 1) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.SUCCEEDED, "planning repaired")));
          }
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.MISSING_MANDATORY_INPUT,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT,
                                  new PlanValidationResult(
                                      List.of(
                                          new PlanValidationFinding(
                                              "PLAN_BLOCKER", "missing quartz", true))),
                                  List.of())),
                          "planning validation failed",
                          null)));
        });
  }

  private static ProductPipelineProfile planningThenPublishProfile(ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "planning-then-publish",
        "2",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "publish",
                "publish-cap",
                List.of(validation),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("publish", "PLAN_APPROVED"),
        List.of("planning-cap", "publish-cap"));
  }

  private static ProductPipelineProfile skippableRetryProfile() {
    return new ProductPipelineProfile(
        1,
        "skippable-retry",
        "2",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "work",
                "fail-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L),
                new SkipPolicy(List.of(SkipPolicy.NO_APIHUB_CANDIDATE)))),
        new TerminalPolicy("work", "PLAN_APPROVED"),
        List.of("fail-cap"));
  }

  private StageCapability planningUnspecifiedDomainFailure() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.DOMAIN_FAILURE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.PLAN_VALIDATION_RESULT,
                                    new PlanValidationResult(
                                        List.of(
                                            new PlanValidationFinding(
                                                "PLAN_BLOCKER", "missing quartz", true))),
                                    List.of())),
                            "planning validation failed",
                            null))));
  }

  private StageCapability planningValidationFailure() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.VALIDATION_FAILURE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.PLAN_VALIDATION_RESULT,
                                    new PlanValidationResult(
                                        List.of(
                                            new PlanValidationFinding(
                                                "PLAN_BLOCKER", "missing quartz", true))),
                                    List.of())),
                            "planning validation failed",
                            null))));
  }

  private StageCapability planningMissingBriefFacts() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.DOMAIN_FAILURE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.PLAN_VALIDATION_RESULT,
                                    new PlanValidationResult(
                                        List.of(
                                            new PlanValidationFinding(
                                                "MISSING_BRIEF_FACTS", "missing quartz", true))),
                                    List.of())),
                            "planning validation failed",
                            null))));
  }

  private StageCapability planningDomainFailure() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.DOMAIN_FAILURE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.PLAN_VALIDATION_RESULT,
                                    new PlanValidationResult(
                                        List.of(
                                            new PlanValidationFinding(
                                                "PLAN_BLOCKER", "missing quartz", true))),
                                    List.of())),
                            "planning validation failed",
                            null))));
  }

  private StageCapability planningDomainFailures(String... messages) {
    AtomicInteger calls = new AtomicInteger();
    return capability(
        "planning-cap",
        context -> {
          String message = messages[Math.min(calls.getAndIncrement(), messages.length - 1)];
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.DOMAIN_FAILURE,
                          List.of(),
                          message,
                          null,
                          new RecoveryCause(
                              RecoveryCauseCode.MISSING_BRIEF_FACTS,
                              List.of(
                                  new PlanValidationFinding(
                                      "MISSING_BRIEF_FACTS", message, true)),
                              ""))));
        });
  }

  private void approveAnalysis(CreateChainTestOrchestrator runtime) {
    var waiting =
        execute(runtime, "analysis").decision() instanceof StageDecision.WaitForApproval approval
            ? approval
            : null;
    assertNotNull(waiting);
    runtime
        .recordApprove(
            new ApproveCommand(RUN_ID, waiting.candidate(), requireRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals("planning", requireRun().run().currentStageId());
  }

  private void approveCurrentAnalysis(CreateChainTestOrchestrator runtime) {
    approveCurrentStage(runtime, "analysis");
  }

  private void approveCurrentStage(CreateChainTestOrchestrator runtime, String stageId) {
    approveStage(runtime, stageId);
  }

  private void approveStage(CreateChainTestOrchestrator runtime, String stageId) {
    StageDecision.WaitForApproval waiting =
        assertInstanceOf(
            StageDecision.WaitForApproval.class, execute(runtime, stageId).decision());
    runtime
        .recordApprove(
            new ApproveCommand(RUN_ID, waiting.candidate(), requireRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private StageCapability planningAlwaysCandidate() {
    return capability(
        "planning-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.IMPLEMENTATION_PLAN, Map.of("plan", "ok"), List.of())),
                            "plan ready",
                            null))));
  }

  private StageCapability analysisAlwaysCandidate() {
    return capability(
        "analysis-cap",
        context -> {
          RequirementBrief payload =
              new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "goal");
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, payload, List.of())),
                          "brief ready",
                          null)));
        });
  }

  private static StageSnapshot snapshot(ProductPipelineRunDocument doc, String stageId) {
    return doc.run().stages().stream()
        .filter(stage -> stageId.equals(stage.stageId()))
        .findFirst()
        .orElseThrow();
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private StageCapability designCandidate() {
    return capability(
        "design-cap",
        context -> {
          String error =
              context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
          String goal = error == null || error.isBlank() ? "design" : "repaired design";
          RequirementBrief payload =
              new RequirementBrief(goal, List.of(), List.of(), List.of(), List.of(), goal);
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, payload, List.of())),
                          "design brief ready",
                          null)));
        });
  }

  private static ProductPipelineProfile analysisThenDesignThenPlanningProfile(
      ArtifactTypeRef brief, ArtifactTypeRef validation) {
    return new ProductPipelineProfile(
        1,
        "validation-reopen-three",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design",
                "design-cap",
                List.of(brief),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("analysis-cap", "design-cap", "planning-cap"));
  }

  private static ProductPipelineProfile analysisThenMaterializationProfile(ArtifactTypeRef brief) {
    return new ProductPipelineProfile(
        1,
        "catalog-write-gate",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "materialization",
                "materialization-cap",
                List.of(brief),
                List.of(new ArtifactTypeRef("catalog-chain-snapshot", 1)),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("materialization", "CHAIN_MATERIALIZED"),
        List.of("analysis-cap", "materialization-cap"));
  }

  private static ProductPipelineProfile executionThenMaterializationProfile(
      ArtifactTypeRef request) {
    return new ProductPipelineProfile(
        1,
        "execution-materialization-recovery",
        "2",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(request),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "materialization",
                "materialization-cap",
                List.of(request),
                List.of(new ArtifactTypeRef("materialization-result", 1)),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("materialization", "CHAIN_MATERIALIZED"),
        List.of("design-execution", "materialization"));
  }

  private static ProductPipelineProfile retryProfile(
      String stageId, String capabilityId, RetryPolicy retry) {
    return new ProductPipelineProfile(
        1,
        "retry-profile",
        "2",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                stageId,
                capabilityId,
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                null,
                null,
                retry)),
        new TerminalPolicy(stageId, "PLAN_APPROVED"),
        List.of(capabilityId));
  }

  private static ProductPipelineProfile twoStageSuccessProfile() {
    return new ProductPipelineProfile(
        1,
        "flow-test",
        "2",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "first",
                "flow-first",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "second",
                "flow-second",
                List.of(),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("second", "CHAIN_MATERIALIZED"),
        List.of("first", "second"));
  }

  private static ProductPipelineProfile twoStageApprovalProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef bypass = new ArtifactTypeRef("ids-bypass", 1);
    return new ProductPipelineProfile(
        1,
        "test-two-stage",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "collect",
                "fake-collector",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "finish",
                "fake-finisher",
                List.of(brief),
                List.of(bypass),
                new ApprovalPolicy(bypass),
                null,
                new RetryPolicy(1, 1L))),
        new TerminalPolicy("finish", "PLAN_APPROVED"),
        List.of("fake-collector", "fake-finisher"));
  }

  private RunManifest manifest(ProductPipelineProfile profile) {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("cap", "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static StageCapability succeeding(String id, AtomicInteger calls) {
    return capability(
        id,
        context -> {
          calls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.SUCCEEDED, id + " complete")));
        });
  }

  private static StageCapability routeDraft(String id, AtomicInteger calls) {
    return capability(
        id,
        context -> {
          calls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.SUCCEEDED,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.REQUIREMENT_DRAFT,
                                  new RequirementDraft(true, "draft"),
                                  List.of())),
                          "draft ready",
                          null)));
        });
  }

  private static StageCapability capability(
      String id, java.util.function.Function<StageExecutionContext, Multi<CapabilitySignal>> exec) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return id;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return exec.apply(context);
      }
    };
  }

  private ProductPipelineStageExecutor stageExecutor() {
    return new ProductPipelineStageExecutor(
        runStore,
        artifactStore,
        new StageCapabilityRegistry(List.of()),
        Clock.fixed(FIXED, ZoneOffset.UTC),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        null);
  }

  private ApprovalRecordV2 approve(ChainSemanticRevision revision) {
    return stageExecutor().approveCandidate(revision, CONTRACT);
  }

  private static ChainSemanticRevision twoEntryRevision() {
    return SemanticFixtures.revision(
        List.of(
            SemanticFixtures.entry("http-in", "trigger-http"),
            SemanticFixtures.entry("kafka-in", "trigger-kafka")));
  }

  private static ChainSemanticRevision withChangedMapping(ChainSemanticRevision revision) {
    MappingIntent mapping = revision.mappingIntents().getFirst();
    MappingIntent renamed =
        new MappingIntent(
            "map-body-changed",
            mapping.sourceRef(),
            mapping.sourcePort(),
            mapping.targetRef(),
            mapping.targetPort(),
            mapping.rules());
    return copy(
        revision,
        revision.entryPoints(),
        revision.executionEdges(),
        List.of(renamed),
        revision.citations());
  }

  private static ChainSemanticRevision withChangedEntryPoint(ChainSemanticRevision revision) {
    SemanticEntryPoint http = revision.entryPoints().getFirst();
    SemanticEntryPoint retargeted =
        new SemanticEntryPoint(
            http.entryPointId(),
            http.triggerNodeId(),
            "node-call",
            http.order(),
            http.provenance(),
            http.presentation());
    return copy(
        revision,
        List.of(retargeted, revision.entryPoints().get(1)),
        revision.executionEdges(),
        revision.mappingIntents(),
        revision.citations());
  }

  private static ChainSemanticRevision withChangedEdge(ChainSemanticRevision revision) {
    SemanticExecutionEdge edge = revision.executionEdges().getLast();
    SemanticExecutionEdge retargeted =
        new SemanticExecutionEdge(
            edge.edgeId(),
            edge.sourceNodeId(),
            "trigger-http",
            edge.regionId(),
            edge.route(),
            edge.mappingId());
    List<SemanticExecutionEdge> edges = new ArrayList<>(revision.executionEdges());
    edges.set(edges.size() - 1, retargeted);
    return copy(
        revision,
        revision.entryPoints(),
        edges,
        revision.mappingIntents(),
        revision.citations());
  }

  private static ChainSemanticRevision withChangedProvenanceCitation(
      ChainSemanticRevision revision) {
    return copy(
        revision,
        revision.entryPoints(),
        revision.executionEdges(),
        revision.mappingIntents(),
        List.of(
            new QipKnowledgeCitation(
                "cite-1", QipKnowledgeRefType.RULE, "rules/example.yaml", null, "pinned fact")));
  }

  private static ApprovalRecordV2 withSubjectSchemaVersion(
      ApprovalRecordV2 approval, String schemaVersion) {
    return copyApproval(
        approval,
        approval.subjectArtifactKind(),
        schemaVersion,
        approval.compilerContractSha256());
  }

  private static ApprovalRecordV2 withCompilerContractSha256(
      ApprovalRecordV2 approval, String compilerContractSha256) {
    return copyApproval(
        approval,
        approval.subjectArtifactKind(),
        approval.subjectSchemaVersion(),
        compilerContractSha256);
  }

  private static ApprovalRecordV2 withSubjectArtifactKind(
      ApprovalRecordV2 approval, String subjectArtifactKind) {
    return copyApproval(
        approval,
        subjectArtifactKind,
        approval.subjectSchemaVersion(),
        approval.compilerContractSha256());
  }

  private static ApprovalRecordV2 copyApproval(
      ApprovalRecordV2 approval,
      String subjectArtifactKind,
      String subjectSchemaVersion,
      String compilerContractSha256) {
    return new ApprovalRecordV2(
        approval.target(),
        approval.targetContentHash(),
        approval.approvedCandidates(),
        approval.actor(),
        approval.comment(),
        approval.approvedAt(),
        approval.bindingResolutionPolicy(),
        approval.bindingResolutionPolicyHash(),
        subjectArtifactKind,
        subjectSchemaVersion,
        approval.subjectRevisionId(),
        approval.subjectSha256(),
        approval.compilerContractVersion(),
        compilerContractSha256);
  }

  private static ChainSemanticRevision copy(
      ChainSemanticRevision base,
      List<SemanticEntryPoint> entryPoints,
      List<SemanticExecutionEdge> edges,
      List<MappingIntent> mappings,
      List<QipKnowledgeCitation> citations) {
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        entryPoints,
        base.nodes(),
        base.regions(),
        edges,
        base.containment(),
        mappings,
        base.constraints(),
        base.assumptions(),
        citations);
  }
}
