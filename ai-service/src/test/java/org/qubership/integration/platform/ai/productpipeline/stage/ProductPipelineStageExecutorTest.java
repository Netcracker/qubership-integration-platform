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
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
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
import org.qubership.integration.platform.ai.productpipeline.create.PlanningDegradations;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.BypassPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
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

  @Test
  void verifyApprovalAcceptsUnchangedRevision() {
    ChainSemanticRevision revision = twoEntryRevision();
    ApprovalRecordV2 approval = approve(revision);
    stageExecutor().verifyApproval(approval, revision);
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

    StageExecutionResult result = execute(runtime, "skipped");

    assertInstanceOf(StageDecision.Continue.class, result.decision());
    assertEquals(1, routeCalls.get());
    assertEquals(0, skippedCalls.get());
    assertEquals(0, doneCalls.get());
    assertEquals("skipped", requireRun().run().currentStageId());
    assertEquals(StageStatus.SUCCEEDED, stageStatus(requireRun(), "skipped"));
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

      if (outcomeClass == StageOutcomeClass.CONTRACT_FAILURE) {
        StageDecision.Retry retry =
            assertInstanceOf(StageDecision.Retry.class, result.decision());
        assertEquals(
            Duration.ZERO,
            retry.delay(),
            "CONTRACT_FAILURE semantic repair is not a technical retry");
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
  void aValidationOutcomeParksWithoutOwnerDiagnosisOrTechnicalRetry() {
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

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY,
        PipelineGates.strip(wait.prompt()));
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_UNCLASSIFIED));
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
  void validationFailureHaltsInsteadOfReopeningThePreviousApproval() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(profile, analysisCandidate(), planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    int briefCountBefore = artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size();
    StageExecutionResult result = execute(runtime, "planning");

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
    assertEquals("planning", wait.stageId());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
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
  void aRetriedStageReadsTheArtifactItsHaltedAttemptProduced() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanningProfile(brief, validation);
    List<List<Reference>> priorPerTurn = new CopyOnWriteArrayList<>();
    List<List<Reference>> inputsPerTurn = new CopyOnWriteArrayList<>();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            profile,
            analysisCandidate(),
            planningRecordingPriorOutputs(priorPerTurn, inputsPerTurn));
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    execute(runtime, "planning");

    assertEquals(List.of(), priorPerTurn.get(0), "a first turn reads nothing");
    StageSnapshot halted = snapshot(requireRun(), "planning");
    assertEquals(StageStatus.WAITING_FOR_INPUT, halted.status());
    assertNull(halted.approvedArtifactId());
    Reference rejected =
        halted.outputRefs().stream()
            .filter(ref -> ref.kind() == Kind.PLAN_VALIDATION_RESULT)
            .findFirst()
            .orElseThrow();

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "planning");

    assertEquals(List.of(rejected), priorPerTurn.get(1));
    assertTrue(artifactStore.get(RUN_ID, rejected).isPresent());
    assertFalse(
        inputsPerTurn.get(1).contains(rejected),
        "a halted output is evidence, never a resolved declared input");
    assertNull(snapshot(requireRun(), "planning").approvedArtifactId());
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

    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(second.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(second.prompt()));
    assertEquals(
        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REPEATED));
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
  void invalidRecoveryDecisionGetsOneCorrectionThenParks() {
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

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(2, agent.calls.get());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
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

  @Test
  void identicalRegenerateValidationHaltAsksWithPriorAttemptRefs() {
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

    StageDecision.WaitForInput firstWait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());
    assertEquals(
        PipelineGates.RECOVERY_REGENERATE_EXECUTION,
        PipelineGates.gateOf(firstWait.prompt()).orElseThrow());
    assertTrue(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_REGENERATE_EXECUTION)
            .contains(ChatEvent.RETRY_CREATION_ACTION));
    assertEquals(1, executionCalls.get());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(
        PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(2, executionCalls.get());
    List<Revision> evidenceHistory = artifactStore.history(RUN_ID, Kind.RECOVERY_EVIDENCE);
    assertEquals(2, evidenceHistory.size());
    RecoveryEvidence parkedEvidence =
        artifactStore.payload(evidenceHistory.get(1), RecoveryEvidence.class);
    assertEquals(1, parkedEvidence.priorAttemptRefs().size());
    assertEquals(
        evidenceHistory.get(0).reference(), parkedEvidence.priorAttemptRefs().get(0));
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
  void catalogWriteBlocksCausalReopenAndRetryRepeatsMaterialization() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief is wrong.", "analysis");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    AtomicInteger materializationCalls = new AtomicInteger();
    StageCapability materialization =
        capability(
            "materialization-cap",
            context -> {
              materializationCalls.incrementAndGet();
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.DOMAIN_FAILURE,
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
    execute(runtime, "materialization");
    assertTrue(runtime.support().latestCatalogChainSnapshot(RUN_ID).isPresent());

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

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED,
        PipelineGates.gateOf(
                requireRun().transitions().get(requireRun().transitions().size() - 1).reason())
            .orElseThrow());
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
    StageCapability execution = executionRbacValidationFailure(executionCalls);
    ProductPipelineProfile profile = planningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, planning, execution);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "design-planning");

    StageExecutionResult failed =
        execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("design-planning", reopen.producerStageId());
    assertEquals(1, executionCalls.get());
    applyLifecycle(runtime, failed);

    StageExecutionResult planningResult = execute(runtime, "design-planning");
    applyLifecycle(runtime, planningResult);

    assertEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals(1, executionCalls.get());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertNotNull(seenError.get());
    assertFalse(seenError.get().isBlank());
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

  @Test
  void spentDesignInputCaptureRepairAsksInsteadOfEscalatingOwners() {
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

    assertEquals(2, captureCalls.get());
    assertEquals(
        PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertFalse(PipelineGates.strip(wait.prompt()).contains("requirement-analysis"));
    assertEquals(
        ProductPipelineStageExecutor.REPEATED_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
  }

  @Test
  void clarificationAnswerRetriesDesignInputInsteadOfEscalatingOwners() {
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
    waitAfterOptionalSemanticRepair(runtime, "design-input");

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "For onTaskResult use service call"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    String prompt =
        requireRun().transitions().stream()
            .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
            .reduce((a, b) -> b)
            .map(transition -> transition.reason() == null ? "" : transition.reason())
            .orElse("");
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(prompt).isEmpty());
    assertFalse(PipelineGates.strip(prompt).contains("Allowed stages"));
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
  }

  @Test
  void rbacValidationOnExecutionHaltsWithReviseOwnedByPlanningNotSelf() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = planningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            planningRepairCandidate(new AtomicReference<>(), new AtomicReference<>()),
            executionRbacValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "design-planning");

    StageExecutionResult failed = execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("design-planning", reopen.producerStageId());
    assertEquals("design-planning", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertEquals(1, executionCalls.get());
    applyLifecycle(runtime, failed);

    assertEquals("design-planning", requireRun().run().currentStageId());
    assertNotEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void policyValidationOnExecutionHaltsWithReviseOwnedByAnalysisWhenBriefIsInSet() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisRepairCandidate(seenError, seenFollowUp),
            planningAlwaysCandidate(),
            executionRbacValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult failed = execute(runtime, "design-execution");
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("requirement-analysis", reopen.producerStageId());
    assertEquals(
        "requirement-analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertEquals(1, executionCalls.get());
    applyLifecycle(runtime, failed);

    StageExecutionResult analysis = execute(runtime, "requirement-analysis");
    applyLifecycle(runtime, analysis);

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-planning").status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertEquals(1, executionCalls.get());
    assertNotNull(seenError.get());
    assertFalse(seenError.get().isBlank());
  }

  @Test
  void planFillValidationOnExecutionHaltsWithReviseOwnedByPlanningWhenBriefIsInSet() {
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
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("design-planning", reopen.producerStageId());
    applyLifecycle(runtime, failed);

    assertEquals("design-planning", requireRun().run().currentStageId());
    assertNotEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(1, executionCalls.get());
  }

  @Test
  void unknownPropertyOnExecutionKeepsTheHaltOnExecutionEvenWhenTheModelBlamesPlanning() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("", "design-planning");
    AtomicInteger executionCalls = new AtomicInteger();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionUnknownPropertyValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(
        PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(ProductPipelineStageExecutor.UNKNOWN_PROPERTY_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("unknown property key 'topic'"));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("unknown property key 'topic'"));
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertNotEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(1, executionCalls.get());
    assertFalse(
        ChatEvent.actionsForGate(PipelineGates.RECOVERY_INTERNAL)
            .contains(ChatEvent.RETRY_CREATION_ACTION));
  }

  @Test
  void aCaptureContractFailureWithoutFindingsRepairsExecutionOnceThenParks() {
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

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class, execute(runtime, "design-execution").decision());

    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        ProductPipelineStageExecutor.UNCLASSIFIED_RECOVERY_SUMMARY, PipelineGates.strip(wait.prompt()));
    assertTrue(
        PipelineGates.recoveryTechnicalDetailsOf(wait.prompt())
            .orElseThrow()
            .contains("Cannot deserialize"));
    assertEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(2, executionCalls.get());
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
  void haltFollowUpBareGoBackReopensDiagnosedOwnerNotExecution() {
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
            executionRbacValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult failed = execute(runtime, "design-execution");
    applyLifecycle(runtime, failed);

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertNotEquals("design-execution", requireRun().run().currentStageId());
    assertNotEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(1, executionCalls.get());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-planning").status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
  }

  @Test
  void bareGoBackToBriefRepairUsesHaltEvidenceAndWaitsForBriefApproval() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner(
            "Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenOutcome = new AtomicReference<>();
    AtomicReference<String> seenFailedStage = new AtomicReference<>();
    AtomicReference<String> seenFindings = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    AtomicReference<RequirementBrief> seenPriorBrief = new AtomicReference<>();
    AtomicReference<String> changeSummary = new AtomicReference<>();
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    StageCapability analysis =
        capability(
            "analysis-cap",
            context -> {
              seenError.set(
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
              seenOutcome.set(
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR));
              seenFailedStage.set(
                  context.attributeAsString(
                      ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR));
              seenFindings.set(
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR));
              seenFollowUp.set(
                  context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR));
              Object prior = context.attributes().get("requirementBrief");
              if (prior instanceof RequirementBrief brief) {
                seenPriorBrief.set(brief);
              }
              String error =
                  context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
              boolean repairing = error != null && !error.isBlank();
              if (!repairing && context.attributeAsString("userText") == null) {
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
              }
              String goal = repairing ? "repaired goal with RBAC" : "goal";
              RequirementBrief payload =
                  new RequirementBrief(goal, List.of(), List.of(), List.of(), List.of(), goal);
              if (repairing) {
                changeSummary.set(
                    "I added an RBAC access-control requirement. If you approve, the plan will be"
                        + " rebuilt.");
                return Multi.createFrom()
                    .items(
                        new CapabilitySignal.Message(changeSummary.get()),
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.CANDIDATE,
                                List.of(
                                    new ArtifactCandidate(
                                        Kind.REQUIREMENT_BRIEF, payload, List.of())),
                                "Requirement brief updated. Approve to rebuild the plan.",
                                null)));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.CANDIDATE,
                              List.of(
                                  new ArtifactCandidate(
                                      Kind.REQUIREMENT_BRIEF, payload, List.of())),
                              "brief ready",
                              null)));
            });
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysis,
            planningAlwaysCandidate(),
            executionRbacValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    StageExecutionResult failed = execute(runtime, "design-execution");
    applyLifecycle(runtime, failed);
    StageExecutionResult analysisResult = execute(runtime, "requirement-analysis");
    List<PipelineSignal> reopenSignals =
        runtime
            .support()
            .applyStageLifecycle(RUN_ID, analysisResult)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertNotEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-planning").status());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertNotNull(seenError.get());
    assertTrue(seenError.get().toLowerCase(Locale.ROOT).contains("rbac"), seenError.get());
    assertEquals("VALIDATION_FAILURE", seenOutcome.get());
    assertEquals("design-execution", seenFailedStage.get());
    assertTrue(seenFindings.get().toLowerCase(Locale.ROOT).contains("rbac"), seenFindings.get());
    assertNotNull(seenPriorBrief.get());
    assertEquals("goal", seenPriorBrief.get().goal());
    assertTrue(
        reopenSignals.stream()
            .anyMatch(
                signal ->
                    signal instanceof PipelineSignal.Message message
                        && message.text().contains("I added an RBAC")));
    assertTrue(
        reopenSignals.stream()
            .anyMatch(
                signal ->
                    signal instanceof PipelineSignal.WaitingForApproval waiting
                        && "requirement-analysis".equals(waiting.stageId())
                        && ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT.equals(
                            waiting.prompt())));

    approveStage(runtime, "requirement-analysis");
    String priorBriefHash =
        artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).getFirst().contentHash();
    String priorPlanHash =
        artifactStore.latest(RUN_ID, Kind.IMPLEMENTATION_PLAN).orElseThrow().contentHash();
    Map<String, Object> repairApprovalAttributes = runtime.support().runAttributes(RUN_ID);
    assertEquals(
        priorBriefHash,
        repairApprovalAttributes.get(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR));
    Object supersededArtifactHashes =
        repairApprovalAttributes.get(ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR);
    assertInstanceOf(List.class, supersededArtifactHashes);
    assertTrue(((List<?>) supersededArtifactHashes).contains(priorPlanHash));
    assertEquals("design-planning", requireRun().run().currentStageId());
    assertEquals(StageStatus.RUNNING, snapshot(requireRun(), "design-planning").status());
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

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    String prompt =
        requireRun().transitions().get(requireRun().transitions().size() - 1).reason();
    assertEquals(
        PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(prompt).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(prompt).isEmpty());
    assertTrue(PipelineGates.guardOf(prompt).isEmpty());
  }

  @Test
  void haltFollowUpNamingRequirementsReopensThatStageNotExecution() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner(
            "Design execution could not complete.", "design-execution");
    AtomicInteger executionCalls = new AtomicInteger();
    AtomicReference<String> seenError = new AtomicReference<>();
    AtomicReference<String> seenFollowUp = new AtomicReference<>();
    StageCapability analysis = analysisRepairCandidate(seenError, seenFollowUp);
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysis,
            planningAlwaysCandidate(),
            executionRbacValidationFailure(executionCalls));
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");

    StageExecutionResult failed = execute(runtime, "design-execution");
    applyLifecycle(runtime, failed);
    StageExecutionResult analysisResult = execute(runtime, "requirement-analysis");
    applyLifecycle(runtime, analysisResult);

    assertEquals("requirement-analysis", requireRun().run().currentStageId());
    assertNotEquals("design-execution", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, requireRun().run().status());
    assertEquals(1, executionCalls.get());
    assertEquals(StageStatus.PENDING, snapshot(requireRun(), "design-execution").status());
    assertNotNull(seenError.get());
    assertFalse(seenError.get().isBlank());
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
    assertEquals(PipelineGates.RECOVERY_UNCLASSIFIED, PipelineGates.gateOf(listed).orElseThrow());
    assertTrue(PipelineGates.ownerCandidatesOf(listed).isEmpty());
    assertTrue(PipelineGates.guardOf(listed).isEmpty());
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

  private StageExecutionResult execute(CreateChainTestOrchestrator runtime, String stageId) {
    return runtime.stageExecutor().execute(RUN_ID, stageId).await().indefinitely();
  }

  /**
   * Contract failures spend one semantic repair (Retry at delay zero) before the card. Other
   * outcomes park on the first halt.
   */
  private StageDecision.WaitForInput waitAfterOptionalSemanticRepair(
      CreateChainTestOrchestrator runtime, String stageId) {
    StageExecutionResult result = execute(runtime, stageId);
    if (result.decision() instanceof StageDecision.Retry retry) {
      assertEquals(Duration.ZERO, retry.delay());
      applyLifecycle(runtime, result);
      result = execute(runtime, stageId);
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
    return new CreateChainTestOrchestrator(
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(capabilities)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(narrative)
            .build(),
        runStore);
  }

  private CreateChainTestOrchestrator newRuntime(
      int repeatedFailureThreshold,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    return newRuntime(repeatedFailureThreshold, new RecoveryAttemptLedger(), ignoredProfile, capabilities);
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
                                              "security-1",
                                              "External route requires accessControlType=RBAC",
                                              true))),
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
