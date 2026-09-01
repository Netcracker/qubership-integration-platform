package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
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
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Product-pipeline seam for contextual recovery telemetry: dialog presentation through final
 * outcome, without treating pipeline stage ids as user actions.
 */
class RecoveryOutcomeMatrixTest {

  private static final Instant FIXED = Instant.parse("2026-08-19T12:00:00Z");
  private static final String RUN_ID = "run-recovery-telemetry-1";
  private static final String CONVERSATION = "conv-recovery-telemetry";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private RecoveryOutcomeTelemetry telemetry;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    telemetry = new RecoveryOutcomeTelemetry();
  }

  static Stream<Arguments> singleStageCategories() {
    return Stream.of(
        Arguments.of(
            StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
            "persistent transport failure",
            PipelineGates.RECOVERY_RETRY_TECHNICAL,
            List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION)),
        Arguments.of(
            StageOutcomeClass.POLICY_FAILURE,
            "This region is not supported for chain creation.",
            PipelineGates.RECOVERY_ENVIRONMENT,
            List.of(PipelineGates.STOP_WITH_REPORT_ACTION)),
        Arguments.of(
            StageOutcomeClass.INTERNAL_FAILURE,
            "catalog lookup broke",
            PipelineGates.RECOVERY_INTERNAL,
            List.of(PipelineGates.STOP_WITH_REPORT_ACTION)));
  }

  @ParameterizedTest
  @MethodSource("singleStageCategories")
  void presentedDialogRecordsCategoryAndApprovedActions(
      StageOutcomeClass outcomeClass,
      String evidence,
      String gate,
      List<String> expectedActions) {
    haltSingleStage(outcomeClass, evidence, new RetryPolicy(0, 1L));

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals(RecoveryOutcomeTelemetry.KIND_PRESENTED, presented.kind());
    assertEquals(ChatEvent.recoveryCategoryOf(gate), presented.category());
    assertEquals(expectedActions, presented.offeredActions());
    assertFalse(presented.offeredActions().contains("work"));
    assertFalse(presented.failureIdentity().isBlank());
    boolean retryOffered = presented.offeredActions().contains(ChatEvent.RETRY_CREATION_ACTION);
    assertEquals(PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(gate), retryOffered);
    endRun(runtime());
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void unclassifiedHaltOffersOnlyEndRun() {
    haltSingleStage(
        StageOutcomeClass.VALIDATION_FAILURE, "invalid plan", new RetryPolicy(0, 1L));

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals("unclassified-failure", presented.category());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), presented.offeredActions());
    assertFalse(presented.offeredActions().contains(ChatEvent.RETRY_CREATION_ACTION));
    endRun(runtime());
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void repeatedContractHaltOffersOnlyEndRun() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The reply did not match the contract.", "work");
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            retryProfile("work", "fail-cap", new RetryPolicy(1, 1L), "PLAN_APPROVED"),
            failing("fail-cap", StageOutcomeClass.CONTRACT_FAILURE, "reply is missing a field"));
    ProductPipelineProfile profile =
        retryProfile("work", "fail-cap", new RetryPolicy(1, 1L), "PLAN_APPROVED");
    startAndRecordInput(runtime, profile);
    var first = execute(runtime, "work");
    if (first.decision() instanceof StageDecision.Retry) {
      runtime
          .support()
          .applyStageLifecycle(RUN_ID, first)
          .collect()
          .asList()
          .await()
          .indefinitely();
      execute(runtime, "work");
    }

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals("repeated-identical-failure", presented.category());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), presented.offeredActions());
    assertFalse(presented.offeredActions().contains(ChatEvent.RETRY_CREATION_ACTION));
    endRun(runtime);
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void briefDefectOffersEditRequirements() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    ProductPipelineProfile profile = analysisThenPlanning(brief, validation);
    CreateChainTestOrchestrator runtime =
        newRuntime(new FailureNarrative(agent), profile, analysisCandidate(), planningValidationFailure());
    startAndRecordInput(runtime, profile);
    approveAnalysis(runtime);
    agent.recoverReviseBrief(
        artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow().reference(),
        List.of(),
        "The approved requirements need correction.");
    execute(runtime, "planning");

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals("requirement-brief-defect", presented.category());
    assertEquals(
        List.of(ChatEvent.EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        presented.offeredActions());
    assertFalse(presented.offeredActions().contains("requirement-analysis"));
    assertFalse(presented.offeredActions().contains("planning"));
    endRun(runtime);
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void planDefectOffersRebuildPlan() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionRejectedPlan());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverRegenerates(
        Kind.IMPLEMENTATION_PLAN, "The plan is missing information required to create the chain.");
    execute(runtime, "design-execution");

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals("plan-artifact-defect", presented.category());
    assertEquals(
        List.of(ChatEvent.REBUILD_PLAN_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        presented.offeredActions());
    assertFalse(presented.offeredActions().contains("design-planning"));
    assertFalse(presented.offeredActions().contains("design-execution"));
    endRun(runtime);
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void regeneratableExecutionOffersRetryCreation() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    ProductPipelineProfile profile = analysisThenPlanningThenExecutionProfile();
    CreateChainTestOrchestrator runtime =
        newRuntime(
            new FailureNarrative(agent),
            profile,
            analysisCandidate(),
            planningAlwaysCandidate(),
            executionValidationFailure());
    startAndRecordInput(runtime, profile);
    approveStage(runtime, "requirement-analysis");
    approveStage(runtime, "design-planning");
    agent.recoverRegenerates(Kind.PLAN_VALIDATION_RESULT, "Regenerate the rejected artifact.");
    execute(runtime, "design-execution");

    RecoveryOutcomeTelemetry.Event presented = presented();
    assertEquals("regeneratable-execution-failure", presented.category());
    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        presented.offeredActions());
    assertFalse(presented.offeredActions().contains("design-execution"));
    endRun(runtime);
    assertEquals(
        RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT,
        lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME).outcome());
  }

  @Test
  void endingTheRunRecordsUserExit() {
    haltSingleStage(
        StageOutcomeClass.INTERNAL_FAILURE, "catalog lookup broke", new RetryPolicy(0, 1L));
    runtime()
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RecoveryOutcomeTelemetry.Event outcome = lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME);
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT, outcome.outcome());
    assertFalse(outcome.reachedMaterialization());
    assertEquals(
        PipelineGates.STOP_WITH_REPORT_ACTION,
        lastKind(RecoveryOutcomeTelemetry.KIND_SELECTED).selectedAction());
  }

  @Test
  void retryingTheSameTechnicalFailureIsNoProgress() {
    haltSingleStage(
        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
        "persistent transport failure",
        new RetryPolicy(0, 1L));
    CreateChainTestOrchestrator runtime = lastRuntime;
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "work");

    RecoveryOutcomeTelemetry.Event outcome = lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME);
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_NO_PROGRESS, outcome.outcome());
    assertEquals(Boolean.FALSE, outcome.identityChanged());
    assertEquals(
        ChatEvent.RETRY_CREATION_ACTION,
        lastKind(RecoveryOutcomeTelemetry.KIND_SELECTED).selectedAction());
  }

  @Test
  void aChangedFailureAfterRetryIsPartialProgress() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability failing =
        capability(
            "fail-cap",
            context -> {
              int n = calls.incrementAndGet();
              String evidence = n == 1 ? "first transport failure" : "different catalog error";
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, evidence)));
            });
    ProductPipelineProfile profile =
        retryProfile("work", "fail-cap", new RetryPolicy(0, 1L), "PLAN_APPROVED");
    CreateChainTestOrchestrator runtime = newRuntime(profile, failing);
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "work");

    RecoveryOutcomeTelemetry.Event outcome = lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME);
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_PARTIAL_PROGRESS, outcome.outcome());
    assertEquals(Boolean.TRUE, outcome.identityChanged());
  }

  @Test
  void retryThenMaterializationIsSuccess() {
    AtomicInteger calls = new AtomicInteger();
    StageCapability sometimes =
        capability(
            "only-cap",
            context -> {
              if (calls.incrementAndGet() == 1) {
                return Multi.createFrom()
                    .item(
                        new CapabilitySignal.Completed(
                            StageOutcome.of(
                                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                "persistent transport failure")));
              }
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "created")));
            });
    ProductPipelineProfile profile =
        retryProfile("only", "only-cap", new RetryPolicy(0, 1L), "CHAIN_MATERIALIZED");
    CreateChainTestOrchestrator runtime = newRuntime(profile, sometimes);
    startAndRecordInput(runtime, profile);
    execute(runtime, "only");
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
    execute(runtime, "only");

    RecoveryOutcomeTelemetry.Event outcome = lastKind(RecoveryOutcomeTelemetry.KIND_OUTCOME);
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_SUCCESS, outcome.outcome());
    assertTrue(outcome.reachedMaterialization());
  }

  @Test
  void onlyTemporaryAndRegeneratableCategoriesOfferImmediateRetry() {
    List<String> retryGates =
        List.of(
            PipelineGates.RECOVERY_RETRY_TECHNICAL, PipelineGates.RECOVERY_REGENERATE_EXECUTION);
    List<String> terminalGates =
        List.of(
            PipelineGates.RECOVERY_REVISE_BRIEF,
            PipelineGates.RECOVERY_REBUILD_PLAN,
            PipelineGates.RECOVERY_ENVIRONMENT,
            PipelineGates.RECOVERY_INTERNAL,
            PipelineGates.RECOVERY_REPEATED,
            PipelineGates.RECOVERY_UNCLASSIFIED);
    for (String gate : retryGates) {
      assertTrue(
          ChatEvent.actionsForGate(gate).contains(ChatEvent.RETRY_CREATION_ACTION), gate);
    }
    for (String gate : terminalGates) {
      assertFalse(
          ChatEvent.actionsForGate(gate).contains(ChatEvent.RETRY_CREATION_ACTION), gate);
    }
  }

  private CreateChainTestOrchestrator lastRuntime;

  private void haltSingleStage(
      StageOutcomeClass outcomeClass, String evidence, RetryPolicy retry) {
    haltSingleStage(outcomeClass, evidence, retry, new FailureNarrative());
  }

  private void haltSingleStage(
      StageOutcomeClass outcomeClass,
      String evidence,
      RetryPolicy retry,
      FailureNarrative narrative) {
    ProductPipelineProfile profile = retryProfile("work", "fail-cap", retry, "PLAN_APPROVED");
    CreateChainTestOrchestrator runtime =
        newRuntime(narrative, profile, failing("fail-cap", outcomeClass, evidence));
    startAndRecordInput(runtime, profile);
    execute(runtime, "work");
  }

  private CreateChainTestOrchestrator runtime() {
    return lastRuntime;
  }

  private void endRun(CreateChainTestOrchestrator runtime) {
    runtime
        .recordInput(new AcceptInputCommand(RUN_ID, PipelineGates.STOP_WITH_REPORT_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private RecoveryOutcomeTelemetry.Event presented() {
    return telemetry.events().stream()
        .filter(event -> RecoveryOutcomeTelemetry.KIND_PRESENTED.equals(event.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no presented event: " + telemetry.events()));
  }

  private RecoveryOutcomeTelemetry.Event lastKind(String kind) {
    return telemetry.events().stream()
        .filter(event -> kind.equals(event.kind()))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("no " + kind + " event: " + telemetry.events()));
  }

  private CreateChainTestOrchestrator newRuntime(
      ProductPipelineProfile profile, StageCapability... capabilities) {
    return newRuntime(new FailureNarrative(), profile, capabilities);
  }

  private CreateChainTestOrchestrator newRuntime(
      FailureNarrative narrative,
      ProductPipelineProfile ignoredProfile,
      StageCapability... capabilities) {
    ProductPipelineRunSupport support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(capabilities)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(narrative)
            .recoveryTelemetry(telemetry)
            .build();
    lastRuntime = new CreateChainTestOrchestrator(support, runStore);
    return lastRuntime;
  }

  private void startAndRecordInput(
      CreateChainTestOrchestrator runtime, ProductPipelineProfile profile) {
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

  private org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult execute(
      CreateChainTestOrchestrator runtime, String stageId) {
    return runtime.stageExecutor().execute(RUN_ID, stageId).await().indefinitely();
  }

  private void approveStage(CreateChainTestOrchestrator runtime, String stageId) {
    StageDecision.WaitForApproval waiting =
        assertInstanceOf(StageDecision.WaitForApproval.class, execute(runtime, stageId).decision());
    runtime
        .recordApprove(
            new ApproveCommand(
                RUN_ID, waiting.candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void approveAnalysis(CreateChainTestOrchestrator runtime) {
    approveStage(runtime, "analysis");
  }

  private static StageCapability failing(
      String id, StageOutcomeClass outcomeClass, String evidence) {
    return capability(
        id,
        context ->
            Multi.createFrom()
                .item(new CapabilitySignal.Completed(StageOutcome.of(outcomeClass, evidence))));
  }

  private static StageCapability executionValidationFailure() {
    return capability(
        "execution-cap",
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
                                                "PLAN_BLOCKER", "invalid graph edge", true))),
                                    List.of())),
                            "execution validation failed",
                            null))));
  }

  private static StageCapability planningAlwaysCandidate() {
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

  private static StageCapability executionRejectedPlan() {
    return capability(
        "execution-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(
                            StageOutcomeClass.VALIDATION_FAILURE, "plan cannot be executed"))));
  }

  private static StageCapability analysisCandidate() {
    return capability(
        "analysis-cap",
        context -> {
          if (context.attributeAsString("userText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need text")));
          }
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

  private static StageCapability planningValidationFailure() {
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

  private static StageCapability capability(
      String id, java.util.function.Function<org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext, Multi<CapabilitySignal>> exec) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return id;
      }

      @Override
      public Multi<CapabilitySignal> execute(
          org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext
              context) {
        return exec.apply(context);
      }
    };
  }

  private static ProductPipelineProfile retryProfile(
      String stageId, String capabilityId, RetryPolicy retry, String terminal) {
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
        new TerminalPolicy(stageId, terminal),
        List.of(capabilityId));
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

  private static ProductPipelineProfile analysisThenPlanning(
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

  private static RunManifest manifest(ProductPipelineProfile profile) {
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
}
