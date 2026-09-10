package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
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
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Repeated missing-brief facts after automatic reopen keep that cause and offer Edit requirements.
 * Fake LLM and catalog; real ledger and stage executor.
 */
class MissingBriefFactsExhaustedRecoveryTest {

  private static final Instant FIXED = Instant.parse("2026-09-10T16:00:00Z");
  private static final String RUN_ID = "run-exhausted-brief-1";
  private static final String CONV_ID = "conv-exhausted-brief-1";
  private static final List<String> PRESERVED_FIELDS =
      List.of(
          "$.preserved.executionId",
          "$.preserved.orderId",
          "$.preserved.processInstanceId",
          "$.preserved.executionNumber",
          "$.preserved.taskId");

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private ProductPipelineProfile profile;
  private Clock clock;
  private AtomicInteger analysisCalls;
  private AtomicInteger executionCalls;

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
  }

  @Test
  void repeatedMissingBriefFactsKeepCauseAndOfferEditRequirements() {
    haltAfterRepeatedBriefFailure(new FailureNarrative());

    assertRepeatedBriefEditCard();
    assertEquals(2, executionCalls.get());
    assertEquals("requirement-analysis", support.diagnosedOwnerStageId(RUN_ID).orElse(""));
  }

  @Test
  void absentNarrativeStillOffersEditRequirementsForKnownBriefDefect() {
    haltAfterRepeatedBriefFailure(new FailureNarrative(FakeFailureNarrativeAgent.boom()));

    assertRepeatedBriefEditCard();
    assertEquals(2, executionCalls.get());
  }

  @Test
  void parkedNarrativeStillOffersEditRequirementsForKnownBriefDefect() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    agent.recoverReturns(
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(),
            RecoveryAction.PARK,
            List.of(),
            "",
            "Park until recovery is clarified."));
    haltAfterRepeatedBriefFailure(new FailureNarrative(agent));

    assertRepeatedBriefEditCard();
    assertEquals(2, executionCalls.get());
  }

  @Test
  void unclassifiedFailureDoesNotGainEditOrRetry() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused");
    support = supportFor(new FailureNarrative(agent), unclassifiedWork());
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runStore);
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, oneStageProfile(), oneStageManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    if (run().run().status() == RunStatus.WAITING_FOR_INPUT) {
      runtime
          .acceptInput(new AcceptInputCommand(RUN_ID, "create a chain"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    String prompt = latestWaitingPrompt();
    String gate = PipelineGates.gateOf(prompt).orElse("");
    assertTrue(
        PipelineGates.RECOVERY_UNCLASSIFIED.equals(gate)
            || PipelineGates.RECOVERY_REPEATED.equals(gate),
        prompt);
    List<String> actions = ChatEvent.actionsForGate(gate);
    assertFalse(actions.contains(ChatEvent.EDIT_REQUIREMENTS_ACTION), actions.toString());
    assertFalse(actions.contains(ChatEvent.RETRY_CREATION_ACTION), actions.toString());
    assertFalse(actions.contains(PipelineGates.RETRY_ACTION), actions.toString());
    assertTrue(actions.contains(PipelineGates.STOP_WITH_REPORT_ACTION), actions.toString());
    assertNotEquals(
        RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
        String.valueOf(
            support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR)));
  }

  private void haltAfterRepeatedBriefFailure(FailureNarrative narrative) {
    support =
        supportFor(narrative, analysisCapability(), designInputCapability(), failingExecution());
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runStore);
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    if (run().run().status() == RunStatus.WAITING_FOR_INPUT) {
      runtime
          .acceptInput(new AcceptInputCommand(RUN_ID, "create a WFMS work order chain"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("requirement-analysis", run().run().currentStageId());
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
  }

  private void assertRepeatedBriefEditCard() {
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
    for (String field : PRESERVED_FIELDS) {
      assertTrue(summary.contains(field), summary);
    }
    String details = PipelineGates.recoveryTechnicalDetailsOf(prompt).orElse("");
    for (String field : PRESERVED_FIELDS) {
      assertTrue(details.contains(field), details);
    }
    assertEquals(
        RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
        support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
    String findings =
        String.valueOf(
            support.runAttributes(RUN_ID).get(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR));
    for (String field : PRESERVED_FIELDS) {
      assertTrue(findings.contains(field), findings);
    }
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
        String goal = call == 1 ? "first approved brief" : "second approved brief";
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
                        StageOutcomeClass.DOMAIN_FAILURE,
                        List.of(),
                        "mapping targets are absent from the contract",
                        null,
                        RecoveryCause.missingBriefFacts(PRESERVED_FIELDS))));
      }
    };
  }

  private StageCapability unclassifiedWork() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "work-cap";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    StageOutcome.of(
                        StageOutcomeClass.DOMAIN_FAILURE, "planning validation failed")));
      }
    };
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

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flow = new ArtifactTypeRef("chain-semantic-revision", 1);
    return new ProductPipelineProfile(
        1,
        "exhausted-brief-edit",
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

  private static ProductPipelineProfile oneStageProfile() {
    return new ProductPipelineProfile(
        1,
        "unclassified-work",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "work",
                "work-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("work", "PLAN_APPROVED"),
        List.of("work-cap"));
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

  private RunManifest oneStageManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "unclassified-work",
        "1",
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("work-cap", "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }
}
