package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
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
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Seam B: a message typed at a recoverable halt is answered when it asks and routed as before when
 * it instructs. Model wording is the double's, so nothing here asserts prose.
 */
class ProductPipelineHaltQuestionTest {

  private static final Instant FIXED = Instant.parse("2026-08-19T12:00:00Z");
  private static final String RUN_ID = "run-halt-question-1";
  private static final String ANSWER = "The plan asked for an element the catalog does not hold.";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private FakeFailureNarrativeAgent agent;
  private ProductPipelineProfile profile;

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
  void aQuestionIsAnsweredAndTheRunStaysAtTheSameGate() {
    String halted = haltOnPlanningValidation(answeringAgent());
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);

    List<PipelineSignal> signals = type("why did this stop?");

    assertEquals(ANSWER, onlyMessage(signals));
    assertEquals(halted, waitingPrompt(signals));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("planning", run().run().currentStageId());
    assertEquals(halted, latestWaitingPrompt());
    assertEquals(1, agent.questionCalls.get());
    SemanticRecoveryState after = runtime.captureSemanticRecoveryState(RUN_ID);
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class, before.compareTo(after));
  }

  @Test
  void theSameQuestionAgainstUnchangedEvidenceIsAnsweredTwiceWithOneModelCall() {
    haltOnPlanningValidation(answeringAgent());

    String first = onlyMessage(type("why did this stop?"));
    String second = onlyMessage(type("why did this stop?"));

    assertEquals(first, second);
    assertEquals(1, agent.questionCalls.get());
  }

  @Test
  void aQuestionIsNotKeptAsTheCorrectionTheNextRepairTurnReads() {
    haltOnPlanningValidation(answeringAgent());

    type("why did this stop?");

    assertTrue(support.haltFollowUpText(RUN_ID).isEmpty());
  }

  @Test
  void anInstructionNamingAStageStillReopensThatStage() {
    haltOnPlanningValidation(answeringAgent());

    type("go back to requirement-analysis");

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals(0, agent.questionCalls.get());
  }

  @Test
  void aBareGoBackStillReopensTheDiagnosedOwner() {
    haltOnPlanningValidation(answeringAgent());
    type("requirement-analysis");

    type("go back");

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals(0, agent.questionCalls.get());
  }

  @Test
  void aGoBackToAnUnknownStageStillListsTheAllowedStages() {
    haltOnPlanningValidation(answeringAgent());

    List<PipelineSignal> signals = type("go back to nowhere");

    String prompt = waitingPrompt(signals);
    assertTrue(prompt.contains("requirement-analysis"), prompt);
    assertEquals(
        HaltRecoveryGuard.NAMED_STAGE_OUTSIDE_CANDIDATE_SET.name(),
        PipelineGates.guardOf(
                run().transitions().get(run().transitions().size() - 1).reason())
            .orElseThrow());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(0, agent.questionCalls.get());
  }

  @Test
  void aHaltMessageReadAsAnInstructionExecutesTheDiagnosedRepairPath() {
    haltOnPlanningValidation(FakeFailureNarrativeAgent.owner("", "requirement-analysis"));
    type("requirement-analysis");

    type("use the other scheduler");

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals("use the other scheduler", support.haltFollowUpText(RUN_ID).orElseThrow());
  }

  /** Drives the run to a recoverable halt at planning and returns the prompt it halted on. */
  private String haltOnPlanningValidation(FakeFailureNarrativeAgent narrativeAgent) {
    agent = narrativeAgent;
    profile = twoStageProfile();
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(analysisCapability(), planningCapability())),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(new FailureNarrative(agent))
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
    runtime
        .startOrResume(new StartOrResumeCommand("conv-halt-question", RUN_ID, profile, manifest()))
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
        .approve(
            new ApproveCommand(RUN_ID, briefWaiting.candidate(), run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    String prompt = latestWaitingPrompt();
    assertTrue(
        PipelineGates.isRecoverableHaltGate(PipelineGates.gateOf(prompt).orElse("")),
        "expected a recoverable halt gate, got " + prompt);
    agent.questionCalls.set(0);
    return prompt;
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

  private static String onlyMessage(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.Message.class::isInstance)
        .map(PipelineSignal.Message.class::cast)
        .map(PipelineSignal.Message::text)
        .reduce((first, second) -> {
          throw new AssertionError("expected one answer message, got more");
        })
        .orElseThrow(() -> new AssertionError("expected an answer message"));
  }

  private static String waitingPrompt(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.WaitingForInput.class::isInstance)
        .map(PipelineSignal.WaitingForInput.class::cast)
        .map(PipelineSignal.WaitingForInput::prompt)
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("expected the halt card to come back"));
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

  private static StageCapability planningCapability() {
    return new ScriptedCapability(
        "planning",
        new StageOutcome(
            StageOutcomeClass.DOMAIN_FAILURE,
            List.of(
                new ArtifactCandidate(
                    Kind.PLAN_VALIDATION_RESULT,
                    new PlanValidationResult(
                        List.of(
                            new PlanValidationFinding(
                                "PLAN_BLOCKER", "missing quartz-scheduler", true))),
                    List.of())),
            "planning validation failed. Findings: PLAN_BLOCKER: missing quartz-scheduler",
            null));
  }

  private static ProductPipelineProfile twoStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 2);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "test-halt-question",
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
