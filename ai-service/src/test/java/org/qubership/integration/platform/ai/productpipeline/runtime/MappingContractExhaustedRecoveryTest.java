package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Exhausted automatic mapping repair keeps MAPPING_CONTRACT and offers Edit requirements. Fake LLM
 * and catalog; real ledger and stage executor.
 */
class MappingContractExhaustedRecoveryTest {

  private static final Instant FIXED = Instant.parse("2026-09-11T00:00:00Z");
  private static final String RUN_ID = "run-exhausted-mapping-1";
  private static final String CONV_ID = "conv-exhausted-mapping-1";
  private static final List<String> UNKNOWN_TARGETS =
      List.of(
          "$.preserved.executionId",
          "$.preserved.orderId",
          "$.preserved.processInstanceId",
          "$.preserved.executionNumber",
          "$.preserved.taskId");
  private static final RequirementBrief FIRST_BRIEF =
      briefWithTargets("first approved brief", UNKNOWN_TARGETS);
  private static final RequirementBrief SECOND_BRIEF =
      briefWithTargets("second approved brief", UNKNOWN_TARGETS);
  private static final String WHAT_FAILED = "what failed?";
  private static final String FAILURE_ANSWER =
      "The mapping still targets paths absent from the contract.";
  private static final String OMITTED_REQUIRED_API_VALUE =
      "the user omitted a required api value for an unknown target";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private ProductPipelineProfile profile;
  private Clock clock;
  private AtomicInteger analysisCalls;
  private AtomicInteger executionCalls;
  private AtomicReference<RequirementBrief> postEditBrief;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = threeStageProfile();
    analysisCalls = new AtomicInteger();
    executionCalls = new AtomicInteger();
    postEditBrief = new AtomicReference<>(SECOND_BRIEF);
  }

  @Test
  void exhaustedMappingRepairOffersEditRequirementsAndEndRun() {
    haltAfterRepeatedMappingFailure(new FailureNarrative());

    assertExhaustedMappingEditCard();
    assertEquals(2, executionCalls.get());
    assertEquals(2, analysisCalls.get());
    assertEquals("requirement-analysis", support.diagnosedOwnerStageId(RUN_ID).orElse(""));
  }

  @Test
  void askingWhatFailedLeavesArtifactsAndExecutionUnchanged() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("unused").answeringOnly(WHAT_FAILED, FAILURE_ANSWER);
    CreateChainTestOrchestrator runtime =
        haltAfterRepeatedMappingFailure(new FailureNarrative(agent));
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int executions = executionCalls.get();
    int briefs =
        artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size();

    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, WHAT_FAILED))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(FAILURE_ANSWER, onlyMessage(signals));
    assertEquals(executions, executionCalls.get());
    assertEquals(briefs, artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).size());
    assertExhaustedMappingEditCard();
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
  }

  @Test
  void clickingEditEntersCorrectionOnTheSameRun() {
    CreateChainTestOrchestrator runtime = haltAfterRepeatedMappingFailure(new FailureNarrative());
    int executions = executionCalls.get();

    clickEdit(runtime);

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(executions, executionCalls.get());
    assertEquals(3, analysisCalls.get());
  }

  @Test
  void anUnchangedCandidateDoesNotResumeExecutionOrGrantAutomaticBudget() {
    CreateChainTestOrchestrator runtime = haltAfterRepeatedMappingFailure(new FailureNarrative());
    postEditBrief.set(SECOND_BRIEF);
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int executions = executionCalls.get();
    int automaticRemaining = before.remaining().causalReopensRemaining();

    clickEdit(runtime);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    approveCurrentBrief(runtime);

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(executions, executionCalls.get());
    assertExhaustedMappingEditCard();
    assertEquals(
        automaticRemaining,
        runtime.captureSemanticRecoveryState(RUN_ID).remaining().causalReopensRemaining());
    assertNoCatalogWrites();
  }

  @Test
  void aRephrasedRetryDoesNotResumeExecution() {
    CreateChainTestOrchestrator runtime = haltAfterRepeatedMappingFailure(new FailureNarrative());
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int executions = executionCalls.get();

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "please try creating the chain again"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(executions, executionCalls.get());
    assertExhaustedMappingEditCard();
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
  }

  @Test
  void catalogWriteBlocksUpstreamMappingReplay() {
    support =
        supportFor(new FailureNarrative(), analysisCapability(), designInputCapability(), failingExecution());
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runStore);
    reachFirstBriefApproval(runtime);
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.MATERIALIZATION_RESULT,
            "1",
            "test",
            "1",
            Map.of("ok", true),
            List.of(),
            null,
            provenance()));
    int analysisBefore = analysisCalls.get();
    int executions = executionCalls.get();
    int catalogWrites = artifactStore.history(RUN_ID, Kind.MATERIALIZATION_RESULT).size();

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    assertEquals(analysisBefore, analysisCalls.get());
    assertEquals(executions + 1, executionCalls.get());
    assertEquals(catalogWrites, artifactStore.history(RUN_ID, Kind.MATERIALIZATION_RESULT).size());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElse(""));
    assertEquals(
        HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(), PipelineGates.guardOf(prompt).orElse(""));
    List<String> actions = ChatEvent.actionsForGate(PipelineGates.gateOf(prompt).orElseThrow());
    assertFalse(actions.contains(ChatEvent.EDIT_REQUIREMENTS_ACTION), actions.toString());
    assertEquals(
        RecoveryCauseCode.MAPPING_CONTRACT.name(),
        support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
  }

  @Test
  void runCeilingStopsMappingRepairWithoutUpstreamReplay() {
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(analysisCapability(), designInputCapability(), failingExecution())),
                clock)
            .failureNarrative(new FailureNarrative())
            .recoveryLedger(new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 0)))
            .build();
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runStore);
    reachFirstBriefApproval(runtime);
    int analysisBefore = analysisCalls.get();

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    assertEquals(analysisBefore, analysisCalls.get());
    assertEquals(1, executionCalls.get());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElse(""));
    assertEquals(
        HaltRecoveryGuard.MAX_CAUSAL_REOPENS.name(), PipelineGates.guardOf(prompt).orElse(""));
    List<String> actions = ChatEvent.actionsForGate(PipelineGates.gateOf(prompt).orElseThrow());
    assertFalse(actions.contains(ChatEvent.EDIT_REQUIREMENTS_ACTION), actions.toString());
  }

  private CreateChainTestOrchestrator haltAfterRepeatedMappingFailure(FailureNarrative narrative) {
    support =
        supportFor(narrative, analysisCapability(), designInputCapability(), failingExecution());
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runStore);
    reachFirstBriefApproval(runtime);
    String firstBrief = snapshot("requirement-analysis").approvableReference().contentHash();

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("requirement-analysis", run().run().currentStageId());
    String secondBrief = snapshot("requirement-analysis").approvableReference().contentHash();
    assertNotEquals(firstBrief, secondBrief);
    assertEquals(
        2,
        artifactStore.history(RUN_ID, Kind.REQUIREMENT_BRIEF).stream()
            .map(revision -> revision.contentHash())
            .distinct()
            .count());

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    return runtime;
  }

  private void reachFirstBriefApproval(CreateChainTestOrchestrator runtime) {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    if (run().run().status() == RunStatus.WAITING_FOR_INPUT) {
      runtime
          .acceptInput(new AcceptInputCommand(RUN_ID, "create a Salesforce task chain"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("requirement-analysis", run().run().currentStageId());
  }

  private void assertExhaustedMappingEditCard() {
    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.RECOVERY_REVISE_BRIEF, PipelineGates.gateOf(prompt).orElse(""));
    List<String> actions = ChatEvent.actionsForGate(PipelineGates.gateOf(prompt).orElseThrow());
    assertEquals(
        List.of(ChatEvent.EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        actions);
    assertFalse(actions.contains(ChatEvent.RETRY_CREATION_ACTION), actions.toString());
    assertFalse(actions.contains(PipelineGates.RETRY_ACTION), actions.toString());
    String summary = PipelineGates.strip(prompt);
    assertTrue(summary.contains("The previous correction did not resolve"), summary);
    assertFalse(summary.toLowerCase(Locale.ROOT).contains("your requirements"), summary);
    assertFalse(summary.toLowerCase(Locale.ROOT).contains(OMITTED_REQUIRED_API_VALUE), summary);
    for (String field : UNKNOWN_TARGETS) {
      assertTrue(summary.contains(field), summary);
    }
    assertEquals(
        RecoveryCauseCode.MAPPING_CONTRACT.name(),
        support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
    assertNotEquals(
        RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
        support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
  }

  private ProductPipelineRunSupport supportFor(
      FailureNarrative narrative, StageCapability... capabilities) {
    return ProductPipelineRunSupport.builder(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(List.of(capabilities)),
            clock)
        .failureNarrative(narrative)
        .build();
  }

  private StageCapability analysisCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "analysis-cap";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        int call = analysisCalls.incrementAndGet();
        RequirementBrief payload =
            switch (call) {
              case 1 -> FIRST_BRIEF;
              case 2 -> SECOND_BRIEF;
              default -> postEditBrief.get();
            };
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, payload, List.of())),
                        "brief ready",
                        null)));
      }
    };
  }

  private StageCapability designInputCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "design-input-cap";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.CHAIN_SEMANTIC_REVISION,
                                SemanticFixtures.linearOrders(),
                                List.of())),
                        "revision ready",
                        null)));
      }
    };
  }

  private StageCapability failingExecution() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "execution-cap";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        executionCalls.incrementAndGet();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.VALIDATION_FAILURE,
                        List.of(),
                        "mapping contract rejected",
                        null,
                        RecoveryCause.mappingContract(unknownTargetFindings()))));
      }
    };
  }

  private void clickEdit(CreateChainTestOrchestrator runtime) {
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, PipelineGates.REVISE_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void approveCurrentBrief(CreateChainTestOrchestrator runtime) {
    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void assertNoCatalogWrites() {
    assertTrue(artifactStore.latest(RUN_ID, Kind.CATALOG_CHAIN_SNAPSHOT).isEmpty());
    assertTrue(artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT).isEmpty());
  }

  private static List<PlanValidationFinding> unknownTargetFindings() {
    List<PlanValidationFinding> findings = new ArrayList<>();
    for (String path : UNKNOWN_TARGETS) {
      findings.add(
          new PlanValidationFinding(
              "MAPPING_UNKNOWN_TARGET",
              "Target path " + path + " is absent from the target contract.",
              true,
              targetPathDetails(path)));
    }
    return List.copyOf(findings);
  }

  private static MappingValidationDetails targetPathDetails(String targetPath) {
    return new MappingValidationDetails(
        "preserved-mapping",
        "source-call",
        "RESPONSE",
        "target-call",
        "REQUEST",
        "$.source",
        targetPath,
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
        targetPath);
  }

  private static RequirementBrief briefWithTargets(String goal, List<String> targetPaths) {
    List<MappingIntentRule> rules = new ArrayList<>();
    for (String target : targetPaths) {
      rules.add(new MappingIntentRule("$.source", target, null));
    }
    MappingIntent intent =
        new MappingIntent(
            "preserved-mapping",
            "source-call",
            MappingPort.RESPONSE,
            "target-call",
            MappingPort.REQUEST,
            rules);
    return new RequirementBrief(
        goal, List.of(), List.of(), List.of(), List.of(), goal, null, "", List.of(), List.of(intent));
  }

  private static String onlyMessage(List<PipelineSignal> signals) {
    List<String> messages =
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .map(PipelineSignal.Message::text)
            .toList();
    assertEquals(1, messages.size(), signals.toString());
    return messages.getFirst();
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private StageSnapshot snapshot(String stageId) {
    return run().run().stages().stream()
        .filter(stage -> stageId.equals(stage.stageId()))
        .findFirst()
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID,
        "design-execution",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "design-execution",
        "1",
        "closure-sha");
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flow = new ArtifactTypeRef("chain-semantic-revision", 1);
    return new ProductPipelineProfile(
        1,
        "exhausted-mapping-edit",
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

  private RunManifest manifest() {
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
        List.of(
            new DependencyClosureEntry("analysis-cap", "1", "c1"),
            new DependencyClosureEntry("design-input-cap", "1", "c2"),
            new DependencyClosureEntry("execution-cap", "1", "c3")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }
}
