package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DefaultChainSemanticIdsRenderer;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
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
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Mapping-gap resume: pass-through confirmation, describe-mappings wait, and analysis reopen
 * without debiting the causal-reopen budget.
 */
class MappingGapResumeTest {

  private static final Instant FIXED = Instant.parse("2026-09-02T12:00:00Z");
  private static final String RUN_ID = "run-mapping-gap-resume";
  private static final String CONV_ID = "conv-mapping-gap-resume";
  private static final String DESCRIBE_PROSE = "Map task-start payload into create-task as-is.";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;
  private Clock clock;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = threeStageProfile();
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(analysisCapability(), designInputCapability(), planningCapability())),
                clock)
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void passThroughStoresConfirmationAndResumesDesignInputWithoutDebitingReopenBudget() {
    waitAtMappingGap();
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type("pass_through");

    UserInput latest = latestUserInputTargeting("design-input");
    MappingGapPassThroughConfirmation confirmation =
        MappingGapPassThroughConfirmation.parse(latest.text()).orElseThrow();
    assertEquals(committedBriefHash(), confirmation.briefSha());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals("design-input", run().run().currentStageId());
    assertNotEquals("pass_through", support.runAttributes(RUN_ID).get("userText"));
    assertEquals(before, remaining());
  }

  @Test
  void describeMappingsWaitsForDescribeGateAndBlankLeavesThatWait() {
    waitAtMappingGap();

    List<PipelineSignal> describe = type("describe_mappings");

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(
        PipelineGates.MAPPING_GAP_DESCRIBE, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertEquals(
        PipelineGates.MAPPING_GAP_DESCRIBE,
        describe.stream()
            .filter(PipelineSignal.WaitingForInput.class::isInstance)
            .map(PipelineSignal.WaitingForInput.class::cast)
            .map(signal -> PipelineGates.gateOf(signal.prompt()).orElse(""))
            .reduce((first, second) -> second)
            .orElse(""));

    type("   ");

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(
        PipelineGates.MAPPING_GAP_DESCRIBE, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertEquals("design-input", run().run().currentStageId());
  }

  @Test
  void mappingProseMovesToRequirementAnalysisWithoutDebitingReopenBudget() {
    waitAtMappingGap();
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type(DESCRIBE_PROSE);

    assertAnalysisReopenedForProse(before);
  }

  @Test
  void mappingProseAfterDescribeMappingsMovesToRequirementAnalysis() {
    waitAtMappingGap();
    type("describe_mappings");
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type(DESCRIBE_PROSE);

    assertAnalysisReopenedForProse(before);
  }

  @Test
  void wrongBriefShaPassThroughStillAsksOnDesignInputExecute() {
    waitAtMappingGap();
    String realHash = committedBriefHash();
    type("pass_through");
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.USER_INPUT,
            "1",
            "product-pipeline-runtime",
            "1",
            new UserInput("wrong-sha-pass-through", "design-input", wrongConfirmationJson(), FIXED),
            List.of(),
            null,
            provenance()));

    runtime.executeStage(RUN_ID, "design-input").collect().asList().await().indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    Object hydrated = support.runAttributes(RUN_ID).get("mappingGapPassThrough");
    assertInstanceOf(MappingGapPassThroughConfirmation.class, hydrated);
    assertEquals("wrong-sha", ((MappingGapPassThroughConfirmation) hydrated).briefSha());
    assertEquals(realHash, support.runAttributes(RUN_ID).get("requirementBriefContentHash"));
  }

  @Test
  void restoreAfterPassThroughHydratesMappingGapPassThroughForDesignInput() {
    waitAtMappingGap();
    type("pass_through");

    ProductPipelineRunSupport restored = restoreSupport();
    restored
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    Object hydrated = restored.runAttributes(RUN_ID).get("mappingGapPassThrough");
    assertInstanceOf(MappingGapPassThroughConfirmation.class, hydrated);
    assertEquals(committedBriefHash(), ((MappingGapPassThroughConfirmation) hydrated).briefSha());
    assertNotEquals("pass_through", restored.runAttributes(RUN_ID).get("userText"));
  }

  @Test
  void restoreAfterDescribeProseHydratesUserTextForRequirementAnalysis() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);

    ProductPipelineRunSupport restored = restoreSupport();
    restored
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(DESCRIBE_PROSE, restored.runAttributes(RUN_ID).get("userText"));
  }

  private void assertAnalysisReopenedForProse(SemanticRecoveryState.RemainingAttempts before) {
    UserInput latest = latestUserInputTargeting("requirement-analysis");
    assertEquals(DESCRIBE_PROSE, latest.text());
    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    for (StageSnapshot snapshot : run().run().stages()) {
      if ("requirement-analysis".equals(snapshot.stageId())) {
        assertEquals(StageStatus.RUNNING, snapshot.status());
      } else {
        assertEquals(StageStatus.PENDING, snapshot.status(), snapshot.stageId());
        assertTrue(snapshot.outputRefs().isEmpty(), snapshot.stageId());
      }
    }
    assertEquals(DESCRIBE_PROSE, support.runAttributes(RUN_ID).get("userText"));
    assertEquals(before, remaining());
  }

  private void waitAtMappingGap() {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "build a mapped chain"))
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
    assertEquals("design-input", run().run().currentStageId());
    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
  }

  private List<PipelineSignal> type(String text) {
    return support
        .recordInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private SemanticRecoveryState.RemainingAttempts remaining() {
    return support.captureSemanticRecoveryState(RUN_ID).remaining();
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private UserInput latestUserInputTargeting(String stageId) {
    return artifactStore.history(RUN_ID, Kind.USER_INPUT).stream()
        .map(revision -> artifactStore.payload(revision, UserInput.class))
        .filter(input -> stageId.equals(input.targetStageId()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private String committedBriefHash() {
    return artifactStore
        .latest(RUN_ID, Kind.REQUIREMENT_BRIEF)
        .map(Revision::contentHash)
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private ProductPipelineRunSupport restoreSupport() {
    return ProductPipelineRunSupport.builder(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(analysisCapability(), designInputCapability(), planningCapability())),
            clock)
        .build();
  }

  private String wrongConfirmationJson() {
    return new MappingGapPassThroughConfirmation(
            "wrong-sha",
            List.of(new MappingGapPassThroughConfirmation.TransitionRef("source-a", "target-b")))
        .toJson();
  }

  private ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID, "design-input", profile.profileId(), profile.profileVersion(), "profile-sha",
        "design-input", "1", "closure-sha");
  }

  private static StageCapability analysisCapability() {
    return new ScriptedCapability(
        "requirement-analysis",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, uncoveredBrief(), List.of())),
            "brief ready",
            null));
  }

  private static StageCapability designInputCapability() {
    return new DesignInputCapability(
        (conversationId, prompt) -> {
          throw new AssertionError("design agent must not run during mapping-gap tests");
        },
        new DefaultChainSemanticIdsRenderer());
  }

  private static StageCapability planningCapability() {
    return new ScriptedCapability(
        "planning",
        new StageOutcome(
            StageOutcomeClass.CONTRACT_FAILURE, List.of(), "planning must not run", null));
  }

  private static RequirementBrief uncoveredBrief() {
    return new RequirementBrief(
            "Map source-a onto target-b",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Uncovered flow transition",
            "ref",
            "draft",
            List.of())
        .withFlow(
            new RequirementFlow(
                List.of(), List.of(new Transition("source-a", "target-b"))));
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef userInput = new ArtifactTypeRef("user-input", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef semantic = new ArtifactTypeRef("chain-semantic-revision", 1);
    ArtifactTypeRef ids = new ArtifactTypeRef("ids-document", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    return new ProductPipelineProfile(
        1,
        "test-mapping-gap-resume",
        "1",
        List.of(userInput),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "requirement-analysis",
                List.of(userInput),
                List.of(brief),
                new ApprovalPolicy(brief, List.of(brief)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-input",
                "design-input",
                List.of(brief),
                List.of(semantic, ids),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning",
                List.of(brief, semantic),
                List.of(plan),
                new ApprovalPolicy(plan, List.of(plan)),
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("requirement-analysis", "design-input", "planning"));
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
            new DependencyClosureEntry("design-input", "1", "c2"),
            new DependencyClosureEntry("planning", "1", "c3")),
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
