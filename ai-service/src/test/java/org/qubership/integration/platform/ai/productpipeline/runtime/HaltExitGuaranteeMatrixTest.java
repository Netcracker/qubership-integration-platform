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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Runtime coverage of {@code gate × input kind × owner state × catalog state}. Unreachable cells
 * are recorded with their reason rather than forced through a fixture that cannot exist.
 */
class HaltExitGuaranteeMatrixTest {

  private static final Instant FIXED = Instant.parse("2026-08-19T12:00:00Z");
  private static final String RUN_ID = "run-halt-matrix-1";
  private static final String ANSWER = "The plan asked for an element the catalog does not hold.";
  private static final String CLARIFICATION = "Need the catalog service for this chain.";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private FakeFailureNarrativeAgent agent;
  private ProductPipelineProfile profile;
  private final AtomicInteger planningCalls = new AtomicInteger();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
  }

  @Test
  void unreachableCellsAreRecordedWithAReason() {
    List<String> unreachable =
        List.of(
            "STAGE_CLARIFICATION × Retry: Retry is not a clarification action; the gate accepts free text",
            "STAGE_CLARIFICATION × Revise: Revise is not a clarification action",
            "STAGE_CLARIFICATION × owner-choice pick: no owner candidates on this gate",
            "OWNER_CHOICE × Retry: Retry is not on the owner-choice card",
            "OWNER_CHOICE × Revise: Revise is not on the owner-choice card",
            "STAGE_INTERNAL_FAILURE × Retry: internal failure does not offer Retry",
            "STAGE_ESCALATED × Retry as an offered action: Retry is omitted once the guard refuses it",
            "WAITING_FOR_APPROVAL × halt follow-up: not a halt gate",
            "catalog written × automatic ReopenProducer: the executor never emits that decision after a catalog write",
            "STAGE_RETRY × Revise: Revise is not on the retry-only card",
            "STAGE_REVISE × owner-choice pick: revise has no owner-candidate list",
            "untrusted origin × extra repair after the flat budget: InputOrigin absent or untrusted uses the flat budget");
    assertEquals(12, unreachable.size());
  }

  @Test
  void anExhaustedHaltFollowUpEmitsTheCardAndKeepsTheApprovedBrief() {
    haltOnPlanningValidation(FakeFailureNarrativeAgent.owner("", "requirement-analysis"));

    List<PipelineSignal> signals = type("go back to compiler and add RBAC");

    assertFalse(signals.isEmpty(), signals.toString());
    assertTrue(
        signals.stream()
            .anyMatch(
                signal ->
                    signal instanceof PipelineSignal.WaitingForInput
                        || signal instanceof PipelineSignal.Message),
        signals.toString());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.STAGE_ESCALATED, PipelineGates.gateOf(prompt).orElseThrow());
    assertEquals(
        HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET.name(),
        PipelineGates.guardOf(prompt).orElseThrow());
    assertTrue(
        PipelineGates.strip(prompt)
            .contains(HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET.cardSentence()),
        prompt);
    assertTrue(
        PipelineGates.escalatedActionsOf(prompt).contains(PipelineGates.STOP_WITH_REPORT_ACTION));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertTrue(artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).isPresent());
  }

  @Test
  void facadeActionsMatchWhatEachGateStillAccepts() {
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION),
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify("retry", List.of(), PipelineGates.STAGE_RETRY)));
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION),
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify("revise", List.of(), PipelineGates.STAGE_REVISE)));
    assertEquals(
        List.of("requirement-analysis", PipelineGates.STOP_WITH_REPORT_ACTION),
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                HaltRecoveryGuard.BLANK_OR_UNAPPROVED_OWNER.cardSentence(),
                List.of("requirement-analysis", PipelineGates.STOP_WITH_REPORT_ACTION),
                PipelineGates.STAGE_ESCALATED)));
    assertEquals(
        List.of("requirement-analysis", "planning"),
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "pick an owner",
                List.of("requirement-analysis", "planning"),
                PipelineGates.OWNER_CHOICE)));
    assertEquals(
        null,
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "need the catalog service", List.of(), PipelineGates.STAGE_CLARIFICATION)));
  }

  @Test
  void aQuestionAtAReviseGateLeavesSemanticStateUnchanged() {
    haltOnPlanningValidation(answeringAgent());
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);

    type("why did this stop?");

    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
  }

  @Test
  void retryWithAnUnchangedKeyAfterACorrectionStillRuns() {
    haltOnPlanningValidation(FakeFailureNarrativeAgent.owner("", "requirement-analysis"));
    int callsBefore = planningCalls.get();

    type("requirement-analysis");
    type(PipelineGates.REVISE_ACTION);

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals(callsBefore, planningCalls.get());
  }

  @Test
  void anUnsatisfyingClarificationAnswerSpendsAnAttemptAndChangesTheCard() {
    haltOnCatalogResolution(
        FakeFailureNarrativeAgent.owner("", "design-execution").clarifying(CLARIFICATION));
    String first = latestWaitingPrompt();
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(first).orElseThrow());
    assertTrue(PipelineGates.strip(first).contains(CLARIFICATION), first);
    assertFalse(PipelineGates.strip(first).contains("Which catalog service should this chain use?"));
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int callsBefore = planningCalls.get();

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "still the wrong service"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    String second = latestWaitingPrompt();
    assertNotEquals(PipelineGates.strip(first), PipelineGates.strip(second));
    assertEquals(PipelineGates.STAGE_ESCALATED, PipelineGates.gateOf(second).orElseThrow());
    assertEquals(
        HaltRecoveryGuard.MAX_SEMANTIC_REPAIRS.name(), PipelineGates.guardOf(second).orElseThrow());
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Advanced.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
    assertTrue(planningCalls.get() > callsBefore);
  }

  @Test
  void aSpentExplanationBudgetStillAnswersAQuestion() {
    haltOnPlanningValidation(answeringAgent(), 1);
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);

    List<PipelineSignal> signals = type("why did this stop?");

    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(ANSWER, onlyMessage(signals));
  }

  @Test
  void anUnanswerableQuestionProducesACardThatKeepsRawEvidence() {
    haltOnPlanningValidation(
        FakeFailureNarrativeAgent.slow("Too late.", Duration.ofSeconds(30)).answering(ANSWER),
        Integer.MAX_VALUE,
        Duration.ofMillis(50));
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);

    type("why did this stop?");

    String prompt = latestWaitingPrompt();
    assertTrue(PipelineGates.strip(prompt).contains(FailureNarrative.NO_EXPLANATION_AVAILABLE));
    assertTrue(PipelineGates.strip(prompt).contains("planning validation failed"));
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Advanced.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
  }

  private void haltOnPlanningValidation(FakeFailureNarrativeAgent narrativeAgent) {
    haltOnPlanningValidation(narrativeAgent, Integer.MAX_VALUE);
  }

  private void haltOnPlanningValidation(FakeFailureNarrativeAgent narrativeAgent, int maxCalls) {
    haltOnPlanningValidation(narrativeAgent, maxCalls, null);
  }

  private void haltOnPlanningValidation(
      FakeFailureNarrativeAgent narrativeAgent, int maxCalls, Duration timeout) {
    agent = narrativeAgent;
    profile = twoStageProfile();
    support =
        new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(List.of(analysisCapability(), countingPlanning())),
            null,
            null,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            null,
            null,
            null,
            new FailureNarrative(agent, maxCalls, timeout));
    runtime = new CreateChainTestOrchestrator(support, runStore);
    startThroughAnalysisApproval();
  }

  private static String onlyMessage(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.Message.class::isInstance)
        .map(PipelineSignal.Message.class::cast)
        .map(PipelineSignal.Message::text)
        .reduce(
            (first, second) -> {
              throw new AssertionError("expected one answer message, got more");
            })
        .orElseThrow(() -> new AssertionError("expected an answer message"));
  }

  private void haltOnCatalogResolution(FakeFailureNarrativeAgent narrativeAgent) {
    agent = narrativeAgent;
    profile = twoStageProfile();
    support =
        new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(analysisCapability(), catalogResolutionPlanning())),
            null,
            null,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            null,
            null,
            null,
            new FailureNarrative(agent));
    runtime = new CreateChainTestOrchestrator(support, runStore);
    startThroughAnalysisApproval();
  }

  private void startThroughAnalysisApproval() {
    runtime
        .startOrResume(new StartOrResumeCommand("conv-halt-matrix", RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "build pending pets"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    PipelineSignal.WaitingForApproval briefWaiting =
        afterInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    runtime
        .approve(new ApproveCommand(RUN_ID, briefWaiting.candidate(), run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
  }

  private FakeFailureNarrativeAgent answeringAgent() {
    return FakeFailureNarrativeAgent.owner("", "requirement-analysis").answering(ANSWER);
  }

  private List<PipelineSignal> type(String text) {
    return support
        .recordInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private static StageCapability analysisCapability() {
    return new ScriptedCapability(
        "requirement-analysis",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(
                new ArtifactCandidate(
                    Kind.REQUIREMENT_BRIEF, Map.of("goal", "pending pets"), List.of())),
            "brief ready",
            null));
  }

  private StageCapability countingPlanning() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "planning";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        planningCalls.incrementAndGet();
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
                                            "PLAN_BLOCKER", "missing quartz-scheduler", true))),
                                List.of())),
                        "planning validation failed. Findings: PLAN_BLOCKER: missing quartz-scheduler",
                        null)));
      }
    };
  }

  private StageCapability catalogResolutionPlanning() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "planning";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        planningCalls.incrementAndGet();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    StageOutcome.of(
                        StageOutcomeClass.VALIDATION_FAILURE,
                        "catalog service was not resolved",
                        RecoveryCause.catalogResolution("catalog service"))));
      }
    };
  }

  private static ProductPipelineProfile twoStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 2);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "test-halt-matrix",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "requirement-analysis",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief, List.of(brief)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning",
                List.of(brief),
                List.of(plan, validation),
                new ApprovalPolicy(plan, List.of(plan, validation)),
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("requirement-analysis", "planning"));
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
            new DependencyClosureEntry("requirement-analysis", "1", "c1"),
            new DependencyClosureEntry("planning", "1", "c2")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static final class ScriptedCapability implements StageCapability {

    private final String id;
    private final Queue<StageOutcome> outcomes;

    private ScriptedCapability(String id, StageOutcome... outcomes) {
      this.id = id;
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public String capabilityId() {
      return id;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      if ("requirement-analysis".equals(id)) {
        String userText = context.attributeAsString("userText");
        if (userText == null || userText.isBlank()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need user text")));
        }
      }
      StageOutcome outcome =
          outcomes.isEmpty()
              ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "no scripted outcome")
              : outcomes.remove();
      return Multi.createFrom().item(new CapabilitySignal.Completed(outcome));
    }
  }
}
