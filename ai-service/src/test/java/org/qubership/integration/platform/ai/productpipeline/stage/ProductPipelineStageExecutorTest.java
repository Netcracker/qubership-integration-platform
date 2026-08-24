package org.qubership.integration.platform.ai.productpipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
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
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.FakeStageCapabilities;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignalLiveSink;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StaleApprovalException;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
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
            "ReopenApproval",
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
                        new ArtifactTypeRef("design-entry-route", 1)),
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
    assertTrue(wait.prompt().contains("design-entry-route"));
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
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "skipped",
                    "skipped-cap",
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    new SkipPolicy(List.of(SkipPolicy.PROVIDED_DESIGN_ROUTE))),
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
            routeProvide("route-cap", routeCalls),
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
  void exhaustingTheTechnicalRetryBudgetRecordsOneFailedOutcomeWithOriginalEvidence() {
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
    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());

    assertEquals(2, attempts.get());
    assertEquals("work", wait.stageId());
    assertEquals(
        PipelineGates.tag(PipelineGates.STAGE_RETRY, "persistent transport failure"),
        wait.prompt());
    ProductPipelineRunDocument doc = requireRun();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    StageAttempt latest = doc.attempts().get(doc.attempts().size() - 1);
    assertEquals(StageStatus.WAITING_FOR_INPUT, latest.outcome());
    assertEquals(
        0,
        doc.attempts().stream().filter(attempt -> attempt.outcome() == StageStatus.FAILED).count());
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

      assertFalse(
          result.decision() instanceof StageDecision.Retry,
          outcomeClass + " must not enter technical retry");
      StageDecision.WaitForInput wait =
          assertInstanceOf(StageDecision.WaitForInput.class, result.decision());
      assertEquals(
          PipelineGates.tag(PipelineGates.STAGE_RETRY, outcomeClass.name() + " closed"),
          wait.prompt());
      assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    }
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
    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals(briefCountBefore, artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size());
    assertTrue(artifactStore.latest(RUN_ID, Kind.PLAN_VALIDATION_RESULT).isPresent());
    assertTrue(result.signals().stream().noneMatch(PipelineSignal.Failed.class::isInstance));
  }

  @Test
  void validationHaltOffersReviseWhenTheFakePicksAnEarlierOwner() {
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
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(PipelineGates.STAGE_REVISE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        "The brief omitted the scheduler.", PipelineGates.strip(wait.prompt()));
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION),
        ChatEvent.actionsForGate(PipelineGates.STAGE_REVISE));
    assertEquals("analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertTrue(agent.lastCandidateSet.get().contains("analysis"));
    assertTrue(agent.lastCandidateSet.get().contains("planning"));
    assertFalse(agent.lastCandidateSet.get().contains("compiler"));
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
            new FailureNarrative(agent), profile, analysis, planningValidationFailure());
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

    execute(runtime, "planning");
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

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
  void secondDiagnosisOfTheSameOwnerAsksInsteadOfReopeningAgain() {
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
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    execute(runtime, "planning");
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveCurrentAnalysis(runtime);
    execute(runtime, "planning");

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
  }

  @Test
  void thirdCausalReopenIsBlockedAtCapOfTwo() {
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
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "analysis");
    approveCurrentStage(runtime, "design");
    execute(runtime, "planning");

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveCurrentAnalysis(runtime);
    approveCurrentStage(runtime, "design");
    execute(runtime, "planning");

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveCurrentStage(runtime, "design");
    execute(runtime, "planning");

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    long reopenCount =
        requireRun().transitions().stream()
            .filter(transition -> transition.reason().startsWith("causal reopen of "))
            .count();
    assertEquals(2, reopenCount);
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
                              null)));
            });
    ProductPipelineProfile profile = analysisThenMaterializationProfile(brief);
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), materialization);
    startAndRecordInput(runtime, profile);
    approveCurrentStage(runtime, "analysis");
    execute(runtime, "materialization");
    assertTrue(runtime.support().latestCatalogChainSnapshot(RUN_ID).isPresent());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("materialization", requireRun().run().currentStageId());
    assertEquals(1, materializationCalls.get());

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(2, materializationCalls.get());
  }

  @Test
  void ownerOutsideTheCandidateSetFallsBackToRetryOnly() {
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
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("Blaming compiler.", PipelineGates.strip(wait.prompt()));
    assertTrue(runtime.support().diagnosedOwnerStageId(RUN_ID).isEmpty());
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
            planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "planning").decision());

    assertEquals(PipelineGates.OWNER_CHOICE, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(
        List.of("planning", "analysis"), PipelineGates.ownerCandidatesOf(wait.prompt()));
    assertEquals(
        "Either the brief or the plan could be wrong.", PipelineGates.strip(wait.prompt()));

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, "analysis"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("planning", requireRun().run().currentStageId());
    assertEquals("analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertEquals(
        PipelineGates.STAGE_REVISE,
        PipelineGates.gateOf(
                requireRun().transitions().get(requireRun().transitions().size() - 1).reason())
            .orElseThrow());
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
  void technicalPolicyAndMissingInputStayRetryOnlyEvenWithAFakeOwner() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.owner("Would blame work.", "work");
    for (StageOutcomeClass outcomeClass :
        List.of(
            StageOutcomeClass.POLICY_FAILURE, StageOutcomeClass.MISSING_MANDATORY_INPUT)) {
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
      ProductPipelineProfile profile = retryProfile("work", "fail-cap", new RetryPolicy(0, 1L));
      CreateChainTestOrchestrator runtime =
          newRuntime(new FailureNarrative(agent), profile, failing);
      startAndRecordInput(runtime, profile);

      StageDecision.WaitForInput wait =
          assertInstanceOf(StageDecision.WaitForInput.class, execute(runtime, "work").decision());
      assertEquals(
          PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow(), outcomeClass.name());
      assertEquals(List.of(PipelineGates.RETRY_ACTION), ChatEvent.actionsForGate(PipelineGates.STAGE_RETRY));
    }
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
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
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
    assertEquals(PipelineGates.tag(PipelineGates.STAGE_RETRY, "bad domain"), wait.prompt());
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
                                StageOutcomeClass.VALIDATION_FAILURE,
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
        PipelineGates.tag(PipelineGates.STAGE_RETRY, "The catalog could not find that service."),
        wait.prompt());
    assertEquals("VALIDATION_FAILURE", agent.lastOutcome.get());
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

    assertEquals(PipelineGates.tag(PipelineGates.STAGE_RETRY, "bad domain"), wait.prompt());
    String body = PipelineGates.strip(wait.prompt()).toLowerCase();
    assertFalse(body.contains("something went wrong"), wait.prompt());
    assertFalse(body.contains("please try"), wait.prompt());
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION), ChatEvent.actionsForGate(PipelineGates.STAGE_RETRY));
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
        PipelineGates.tag(
            PipelineGates.STAGE_RETRY, "The connection dropped while calling the catalog."),
        wait.prompt());
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION),
        ChatEvent.actionsForGate(PipelineGates.gateOf(wait.prompt()).orElseThrow()));
    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertNotEquals(RunStatus.FAILED, requireRun().run().status());
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

    assertEquals(RunStatus.WAITING_FOR_INPUT, requireRun().run().status());
    assertEquals("work", requireRun().run().currentStageId());
    assertEquals(
        "use a different service", runtime.support().haltFollowUpText(RUN_ID).orElseThrow());
    assertEquals(1, attempts.get());

    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
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

  private CreateChainTestOrchestrator newRuntime(
      ProductPipelineProfile ignoredProfile, StageCapability... capabilities) {
    return newRuntime(new FailureNarrative(), ignoredProfile, capabilities);
  }

  private CreateChainTestOrchestrator newRuntime(
      FailureNarrative narrative,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    return new CreateChainTestOrchestrator(
        new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(List.of(capabilities)),
            null,
            null,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            null,
            null,
            null,
            narrative),
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
    assertEquals(stageId, requireRun().run().currentStageId());
    StageDecision.WaitForApproval waiting =
        execute(runtime, stageId).decision() instanceof StageDecision.WaitForApproval approval
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

  private static StageCapability routeProvide(String id, AtomicInteger calls) {
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
                                  Kind.DESIGN_ENTRY_ROUTE, DesignEntryRoute.PROVIDE, List.of())),
                          "provide route",
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
}
