package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.SkipPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineRuntimeTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T11:00:00Z");
  private static final String CONVERSATION = "conversation-runtime-1";
  private static final String RUN_ID = "run-runtime-1";

  private InMemoryArtifactBlobStore blobStore;
  private ProductPipelineRunStore runStore;
  private ProductPipelineProfile profile;
  private RunManifest manifest;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/two-stage-approval-v1.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }
    manifest = sampleManifest();
  }

  @Test
  void advancesThroughInputApprovalAndTerminalPlanApproved() {
    ProductPipelineRuntime runtime = newRuntime(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());

    List<PipelineSignal> started =
        runtime
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(started.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance));

    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "need greetings"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    PipelineSignal.WaitingForApproval waitingDraft =
        afterInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("collect", waitingDraft.stageId());

    List<PipelineSignal> afterDraftApproval =
        runtime
            .approve(
                new ApproveCommand(
                    RUN_ID,
                    waitingDraft.candidate(),
                    runStore.load(RUN_ID).orElseThrow().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    PipelineSignal.WaitingForApproval waitingPlan =
        afterDraftApproval.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("finish", waitingPlan.stageId());

    List<PipelineSignal> completed =
        runtime
            .approve(
                new ApproveCommand(
                    RUN_ID,
                    waitingPlan.candidate(),
                    runStore.load(RUN_ID).orElseThrow().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(completed.stream().anyMatch(s -> s instanceof PipelineSignal.Completed c
        && c.status() == RunStatus.PLAN_APPROVED));

    assertThrows(
        Exception.class,
        () ->
            runtime
                .approve(
                    new ApproveCommand(
                        RUN_ID,
                        waitingPlan.candidate(),
                        runStore.load(RUN_ID).orElseThrow().run().runRevision()))
                .collect()
                .asList()
                .await()
                .indefinitely());
  }

  @Test
  void restartAfterEveryTransitionRestoresDurableWait() {
    ProductPipelineRuntime runtime = newRuntime(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());
    runtime
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ProductPipelineRuntime restarted = newRuntime(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());
    List<PipelineSignal> resumed =
        restarted
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertInstanceOf(PipelineSignal.WaitingForInput.class, resumed.get(0));

    restarted
        .acceptInput(new AcceptInputCommand(RUN_ID, "text"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    ProductPipelineRuntime restartedAgain =
        newRuntime(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());
    List<PipelineSignal> waitingApproval =
        restartedAgain
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, profile, manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertInstanceOf(PipelineSignal.WaitingForApproval.class, waitingApproval.get(0));
  }

  @Test
  void retriesRetryableTechnicalFailureWithinBudget() {
    ProductPipelineProfile retryProfile =
        new ProductPipelineProfile(
            profile.schemaVersion(),
            profile.profileId(),
            profile.profileVersion(),
            profile.runInputs(),
            List.of(
                new org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage(
                    "collect",
                    "fake-collector",
                    profile.stages().get(0).consumes(),
                    profile.stages().get(0).produces(),
                    profile.stages().get(0).approval(),
                    null,
                    new org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy(
                        1, 1L)),
                profile.stages().get(1)),
            profile.terminal(),
            profile.dependencyRoots());
    ProductPipelineRuntime runtime =
        newRuntime(FakeStageCapabilities.flakyTechnical(), FakeStageCapabilities.finisher());

    List<PipelineSignal> signals =
        runtime
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, retryProfile, manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(signals.stream().anyMatch(PipelineSignal.WaitingForApproval.class::isInstance));
  }

  @Test
  void retriesMissingStructureOutcomeAndClosesTheLatestAttempt() {
    ProductPipelineProfile retryProfile =
        new ProductPipelineProfile(
            profile.schemaVersion(),
            profile.profileId(),
            profile.profileVersion(),
            profile.runInputs(),
            List.of(
                new ProfileStage(
                    "collect",
                    "fake-collector",
                    profile.stages().get(0).consumes(),
                    profile.stages().get(0).produces(),
                    profile.stages().get(0).approval(),
                    null,
                    new RetryPolicy(1, 1L)),
                profile.stages().get(1)),
            profile.terminal(),
            profile.dependencyRoots());
    ProductPipelineRuntime runtime =
        newRuntime(
            FakeStageCapabilities.flakyTechnical(
                "Planning stopped because CHAIN_STRUCTURE is unavailable"),
            FakeStageCapabilities.finisher());

    List<PipelineSignal> signals =
        runtime
            .startOrResume(
                new StartOrResumeCommand(
                    CONVERSATION,
                    RUN_ID,
                    retryProfile,
                    manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        signals.stream()
            .anyMatch(PipelineSignal.WaitingForApproval.class::isInstance));
    ProductPipelineRunDocument persisted =
        runStore.load(RUN_ID).orElseThrow();
    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL,
        persisted.run().status());
    StageAttempt latestAttempt =
        persisted.attempts().get(persisted.attempts().size() - 1);
    assertEquals(
        StageStatus.WAITING_FOR_APPROVAL,
        latestAttempt.outcome());
  }

  @Test
  void rehydratingOneRunDoesNotClearRetryCountersForOtherRuns() throws Exception {
    String runA = "run-retry-a";
    String runB = "run-retry-b";
    RunManifest manifestA =
        new RunManifest(
            runA,
            null,
            List.of(),
            "product",
            profile.profileId(),
            profile.profileVersion(),
            "profile-sha",
            "baseline",
            "baseline-sha",
            List.of(new DependencyClosureEntry("fake-collector", "1", "c1")),
            "closure-sha",
            new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
            "24.4",
            List.of(),
            null);
    RunManifest manifestB =
        new RunManifest(
            runB,
            null,
            List.of(),
            "product",
            profile.profileId(),
            profile.profileVersion(),
            "profile-sha",
            "baseline",
            "baseline-sha",
            List.of(new DependencyClosureEntry("fake-collector", "1", "c1")),
            "closure-sha",
            new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
            "24.4",
            List.of(),
            null);

    ProductPipelineRuntime runtime =
        newRuntime(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher());

    // Park both runs in WAITING_FOR_INPUT so later resumes only rehydrate caches.
    runtime
        .startOrResume(new StartOrResumeCommand("conv-a", runA, profile, manifestA))
        .collect()
        .asList()
        .await()
        .indefinitely();
    runtime
        .startOrResume(new StartOrResumeCommand("conv-b", runB, profile, manifestB))
        .collect()
        .asList()
        .await()
        .indefinitely();

    java.lang.reflect.Field retriesField =
        ProductPipelineRuntime.class.getDeclaredField("technicalRetriesByStage");
    retriesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Integer> retries = (Map<String, Integer>) retriesField.get(runtime);
    retries.put(runA + ":collect", 1);

    runtime
        .startOrResume(new StartOrResumeCommand("conv-b", runB, profile, manifestB))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(
        1,
        retries.getOrDefault(runA + ":collect", 0),
        "rehydrating run B must not clear run A's technical retry counter");
  }

  @Test
  void approvalCommitsWholeDeclaredSetAndApprovalRecordForNextStageInputs() {
    AtomicReference<List<Reference>> consumedByFinish = new AtomicReference<>(List.of());
    StageCapability collect =
        new StageCapability() {
          @Override
          public String capabilityId() {
            return "multi-collect";
          }

          @Override
          public io.smallrye.mutiny.Multi<CapabilitySignal> execute(
              org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext context) {
            if (!context.attributes().containsKey("userText")) {
              return io.smallrye.mutiny.Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
            }
            return io.smallrye.mutiny.Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(
                                new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, Map.of("plan", 1), List.of()),
                                new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", 1), List.of()),
                                new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", 1), List.of())),
                            "candidate",
                            null)));
          }
        };
    StageCapability finish =
        new StageCapability() {
          @Override
          public String capabilityId() {
            return "capture-finish";
          }

          @Override
          public io.smallrye.mutiny.Multi<CapabilitySignal> execute(
              org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext context) {
            consumedByFinish.set(new ArrayList<>(context.inputRefs()));
            return io.smallrye.mutiny.Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "captured")));
          }
        };
    ProductPipelineProfile multiItemProfile =
        new ProductPipelineProfile(
            1,
            "multi-item-profile",
            "1",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "collect",
                    "multi-collect",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(
                        new ArtifactTypeRef("implementation-plan", 2),
                        new ArtifactTypeRef("plan-validation-result", 1),
                        new ArtifactTypeRef("chain-plan-graph", 1)),
                    new ApprovalPolicy(
                        new ArtifactTypeRef("implementation-plan", 2),
                        List.of(
                            new ArtifactTypeRef("implementation-plan", 2),
                            new ArtifactTypeRef("plan-validation-result", 1),
                            new ArtifactTypeRef("chain-plan-graph", 1))),
                    null,
                    new RetryPolicy(0, 1L)),
                new ProfileStage(
                    "finish",
                    "capture-finish",
                    List.of(
                        new ArtifactTypeRef("implementation-plan", 2),
                        new ArtifactTypeRef("plan-validation-result", 1),
                        new ArtifactTypeRef("chain-plan-graph", 1),
                        new ArtifactTypeRef("approval-record", 2)),
                    List.of(new ArtifactTypeRef("fake-plan", 1)),
                    new ApprovalPolicy(new ArtifactTypeRef("fake-plan", 1)),
                    null,
                    new RetryPolicy(0, 1L))),
            new TerminalPolicy("finish", "PLAN_APPROVED"),
            List.of("multi-collect", "capture-finish"));
    ProductPipelineRuntime runtime =
        new ProductPipelineRuntime(
            new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC)),
            new ProductPipelineArtifactStore(
                new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC))),
            new StageCapabilityRegistry(List.of(collect, finish)),
            Clock.fixed(FIXED, ZoneOffset.UTC));

    runtime
        .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, multiItemProfile, manifest))
        .collect()
        .asList()
        .await()
        .indefinitely();
    PipelineSignal.WaitingForApproval waiting =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "go"))
            .collect()
            .asList()
            .await()
            .indefinitely()
            .stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                waiting.candidate(),
                runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    Set<Kind> consumedKinds =
        consumedByFinish.get().stream().map(Reference::kind).collect(java.util.stream.Collectors.toSet());
    assertTrue(consumedKinds.contains(Kind.IMPLEMENTATION_PLAN));
    assertTrue(consumedKinds.contains(Kind.PLAN_VALIDATION_RESULT));
    assertTrue(consumedKinds.contains(Kind.CHAIN_PLAN_GRAPH));
    assertTrue(consumedKinds.contains(Kind.APPROVAL_RECORD));
  }

  @Test
  void v2ProvidedDesignRouteSkipsRequirementStagesWithoutMandatoryInputFailure() {
    AtomicReference<Integer> discoveryCalls = new AtomicReference<>(0);
    AtomicReference<Integer> analysisCalls = new AtomicReference<>(0);
    StageCapability idsEntry =
        capability(
            "design-input",
            context -> {
              if ("ids-entry".equals(context.stageId())) {
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.SUCCEEDED,
                                List.of(
                                    new ArtifactCandidate(
                                        Kind.DESIGN_ENTRY_ROUTE,
                                        DesignEntryRoute.PROVIDE,
                                        List.of())),
                                "provide route",
                                null)));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          new StageOutcome(
                              StageOutcomeClass.SUCCEEDED,
                              List.of(
                                  new ArtifactCandidate(Kind.DESIGN_MODE, DesignMode.PROVIDE, List.of()),
                                  new ArtifactCandidate(
                                      Kind.IDS_DOCUMENT,
                                      Map.of("markdown", "# ids"),
                                      List.of()),
                                  new ArtifactCandidate(
                                      Kind.NORMALIZED_DESIGN_FLOW,
                                      Map.of("flowId", "flow-1"),
                                      List.of())),
                              "design ready",
                              null)));
            });
    StageCapability discovery =
        capability(
            "requirement-discovery",
            context -> {
              discoveryCalls.updateAndGet(v -> v + 1);
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "should skip")));
            });
    StageCapability analysis =
        capability(
            "requirement-analysis",
            context -> {
              analysisCalls.updateAndGet(v -> v + 1);
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "should skip")));
            });

    ProductPipelineProfile v2 =
        new ProductPipelineProfile(
            1,
            "create-chain",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "ids-entry",
                    "design-input",
                    List.of(new ArtifactTypeRef("run-manifest", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(new ArtifactTypeRef("ids-document", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null),
                new ProfileStage(
                    "requirement-discovery",
                    "requirement-discovery",
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    new SkipPolicy(List.of(SkipPolicy.PROVIDED_DESIGN_ROUTE))),
                new ProfileStage(
                    "requirement-analysis",
                    "requirement-analysis",
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("requirement-brief", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    new SkipPolicy(List.of(SkipPolicy.PROVIDED_DESIGN_ROUTE))),
                new ProfileStage(
                    "design-input",
                    "design-input",
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(
                        new ArtifactTypeRef("ids-document", 1),
                        new ArtifactTypeRef("requirement-brief", 1)),
                    List.of(
                        new ArtifactTypeRef("design-mode", 1),
                        new ArtifactTypeRef("ids-document", 1),
                        new ArtifactTypeRef("normalized-design-flow", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("design-input", "PLAN_APPROVED"),
            List.of("design-input", "requirement-discovery", "requirement-analysis"));

    ProductPipelineRuntime runtime =
        new ProductPipelineRuntime(
            runStore,
            new ProductPipelineArtifactStore(
                new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC))),
            new StageCapabilityRegistry(List.of(idsEntry, discovery, analysis)),
            Clock.fixed(FIXED, ZoneOffset.UTC));

    RunManifest v2Manifest =
        new RunManifest(
            RUN_ID,
            null,
            List.of(),
            "product",
            v2.profileId(),
            v2.profileVersion(),
            "profile-sha",
            "baseline",
            "baseline-sha",
            List.of(new DependencyClosureEntry("design-input", "1", "c1")),
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

    List<PipelineSignal> signals =
        runtime
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, v2, v2Manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(0, discoveryCalls.get());
    assertEquals(0, analysisCalls.get());
    assertTrue(
        signals.stream().noneMatch(s -> String.valueOf(s).contains("MISSING_MANDATORY_INPUT")),
        signals.toString());
    assertTrue(
        runStore.load(RUN_ID).orElseThrow().run().status() != RunStatus.FAILED,
        runStore.load(RUN_ID).orElseThrow().run().status().name());
  }

  @Test
  void v2MissingRunInputWaitsSilentlyWithoutTechnicalPromptLeak() {
    StageCapability entry =
        capability(
            "design-input",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "should not run"))));

    ProductPipelineProfile v2 =
        new ProductPipelineProfile(
            1,
            "create-chain",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "ids-entry",
                    "design-input",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("ids-entry", "PLAN_APPROVED"),
            List.of("design-input"));

    ProductPipelineRuntime runtime =
        new ProductPipelineRuntime(
            runStore,
            new ProductPipelineArtifactStore(
                new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC))),
            new StageCapabilityRegistry(List.of(entry)),
            Clock.fixed(FIXED, ZoneOffset.UTC));

    RunManifest v2Manifest =
        new RunManifest(
            RUN_ID,
            null,
            List.of(),
            "product",
            v2.profileId(),
            "2",
            "profile-sha",
            "baseline",
            "baseline-sha",
            List.of(new DependencyClosureEntry("design-input", "1", "c1")),
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

    List<PipelineSignal> signals =
        runtime
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, v2, v2Manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();

    PipelineSignal.WaitingForInput waiting =
        signals.stream()
            .filter(PipelineSignal.WaitingForInput.class::isInstance)
            .map(PipelineSignal.WaitingForInput.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected WaitingForInput, got " + signals));
    assertEquals("", waiting.prompt(), waiting.prompt());
    assertTrue(
        signals.stream().noneMatch(s -> String.valueOf(s).contains("missing required input")),
        signals.toString());
    assertEquals(
        RunStatus.WAITING_FOR_INPUT, runStore.load(RUN_ID).orElseThrow().run().status());
  }

  @Test
  void v2MissingRequiredInputWaitsAfterRouteSkip() {
    StageCapability entry =
        capability(
            "design-input",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            new StageOutcome(
                                StageOutcomeClass.SUCCEEDED,
                                List.of(
                                    new ArtifactCandidate(
                                        Kind.DESIGN_ENTRY_ROUTE,
                                        DesignEntryRoute.STANDARD,
                                        List.of())),
                                "standard",
                                null))));
    StageCapability discovery =
        capability(
            "requirement-discovery",
            context ->
                Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need draft"))));

    ProductPipelineProfile v2 =
        new ProductPipelineProfile(
            1,
            "create-chain",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "ids-entry",
                    "design-input",
                    List.of(new ArtifactTypeRef("run-manifest", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("design-entry-route", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null),
                new ProfileStage(
                    "requirement-discovery",
                    "requirement-discovery",
                    List.of(
                        new ArtifactTypeRef("design-entry-route", 1),
                        new ArtifactTypeRef("requirement-brief", 1)),
                    List.of(),
                    List.of(new ArtifactTypeRef("requirement-draft", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("requirement-discovery", "PLAN_APPROVED"),
            List.of("design-input", "requirement-discovery"));

    ProductPipelineRuntime runtime =
        new ProductPipelineRuntime(
            runStore,
            new ProductPipelineArtifactStore(
                new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC))),
            new StageCapabilityRegistry(List.of(entry, discovery)),
            Clock.fixed(FIXED, ZoneOffset.UTC));

    RunManifest v2Manifest =
        new RunManifest(
            RUN_ID,
            null,
            List.of(),
            "product",
            v2.profileId(),
            "2",
            "profile-sha",
            "baseline",
            "baseline-sha",
            List.of(new DependencyClosureEntry("design-input", "1", "c1")),
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

    List<PipelineSignal> signals =
        runtime
            .startOrResume(new StartOrResumeCommand(CONVERSATION, RUN_ID, v2, v2Manifest))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        signals.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        signals.toString());
    assertEquals(
        RunStatus.WAITING_FOR_INPUT, runStore.load(RUN_ID).orElseThrow().run().status());
  }

  private static StageCapability capability(
      String id,
      java.util.function.Function<
              org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext,
              io.smallrye.mutiny.Multi<CapabilitySignal>>
          exec) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return id;
      }

      @Override
      public io.smallrye.mutiny.Multi<CapabilitySignal> execute(
          org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext
              context) {
        return exec.apply(context);
      }
    };
  }

  private ProductPipelineRuntime newRuntime(
      org.qubership.integration.platform.ai.productpipeline.capability.StageCapability collector,
      org.qubership.integration.platform.ai.productpipeline.capability.StageCapability finisher) {
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    return new ProductPipelineRuntime(
        runStore,
        new ProductPipelineArtifactStore(artifacts),
        new StageCapabilityRegistry(List.of(collector, finisher)),
        Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  private RunManifest sampleManifest() {
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
        List.of(new DependencyClosureEntry("fake-collector", "1", "c1")),
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
}
