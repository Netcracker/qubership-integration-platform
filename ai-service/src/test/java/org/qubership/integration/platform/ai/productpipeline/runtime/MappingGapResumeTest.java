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
import org.qubership.integration.platform.ai.plan.MappingQuerySelector;
import org.qubership.integration.platform.ai.plan.MappingTurnAdapter;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.IntentChange;
import org.qubership.integration.platform.ai.plan.MappingTurnInterpreter;
import org.qubership.integration.platform.ai.plan.MappingTurnResult;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnTelemetry;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Mapping-gap resume: pass-through confirmation, describe wait, and describe-prose through
 * MappingTurnProcessor against the current brief.
 */
class MappingGapResumeTest {

  private static final Instant FIXED = Instant.parse("2026-09-02T12:00:00Z");
  private static final String RUN_ID = "run-mapping-gap-resume";
  private static final String CONV_ID = "conv-mapping-gap-resume";
  private static final String DESCRIBE_PROSE =
      "Map task-start into create-task: name to Subject. Then map create-task into task-result:"
          + " commandType is completeTask.";
  private static final String REQUEST_ONLY =
      "On the create-task request, copy name to Subject.";
  private static final String FIRST_HOP_ONLY =
      "Map task-start -> create-task: copy name to Subject.";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;
  private Clock clock;
  private MappingTurnAdapter mappingAdapter;
  private MappingTurnTelemetry mappingTelemetry;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = threeStageProfile();
    mappingAdapter = coveringAdapter();
    mappingTelemetry = new MappingTurnTelemetry();
    support = newSupport();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void passThroughSkipsUncoveredHopsAndLeavesWithoutMappingRows() {
    waitAtMappingGap();
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type("pass_through");

    RequirementBrief brief = storedBrief();
    assertTrue(brief.mappingIntents().isEmpty());
    assertEquals(2, brief.skippedTransitions().size());
    assertTrue(MappingGapCoverage.uncovered(brief).isEmpty());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals("design-input", run().run().currentStageId());
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
  void mappingProseWritesIntentsAndDoesNotReopenAnalysis() {
    waitAtMappingGap();
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type(DESCRIBE_PROSE);

    assertDescribeAppliedWithoutRecapture(before);
  }

  @Test
  void mappingProseAfterDescribeMappingsWritesIntentsWithoutRecapture() {
    waitAtMappingGap();
    type("describe_mappings");
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type(DESCRIBE_PROSE);

    assertDescribeAppliedWithoutRecapture(before);
  }

  @Test
  void requestOnlyDescribeKeepsTheGapUntilTheSecondTransitionIsCovered() {
    waitAtMappingGap();
    mappingAdapter = requestOnlyAdapter();

    type(REQUEST_ONLY);

    assertEquals("design-input", run().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    RequirementBrief afterFirst = storedBrief();
    assertEquals(1, afterFirst.mappingIntents().size());
    assertEquals(1, MappingGapCoverage.uncovered(afterFirst).size());
    assertEquals(
        PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    MappingGapWait.View remainder = MappingGapWait.parse(PipelineGates.strip(latestWaitingPrompt()));
    assertEquals(List.of("create-task -> task-result"), remainder.missingEdges());

    mappingAdapter = coveringAdapter();
    type(DESCRIBE_PROSE);

    assertTrue(MappingGapCoverage.uncovered(storedBrief()).isEmpty());
    assertEquals("design-input", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
  }

  @Test
  void mappingGapQueryStaysOnCardWithVisibleReply() {
    waitAtMappingGap();
    mappingAdapter = queryAdapter();

    List<PipelineSignal> signals = type("Which transitions are pass-through?");

    assertEquals("design-input", run().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(
        PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(message -> message.text() != null && !message.text().isBlank()));
    assertTrue(storedBrief().mappingIntents().isEmpty());
  }

  @Test
  void mappingGapClarificationStaysOnCardWithVisibleReply() {
    waitAtMappingGap();
    mappingAdapter =
        (brief, message) ->
            new Clarification("AMBIGUOUS_TRANSITION", List.of("create-task", "task-result"));

    List<PipelineSignal> signals = type("Which hop writes Subject?");

    assertEquals("design-input", run().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(
        PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(message -> message.text() != null && message.text().contains("AMBIGUOUS_TRANSITION")));
    assertTrue(storedBrief().mappingIntents().isEmpty());
  }

  @Test
  void inventedSecondHopStaysUncovered() {
    waitAtMappingGap();
    mappingAdapter = inventingInterpreterAdapter();

    type(FIRST_HOP_ONLY);

    RequirementBrief brief = storedBrief();
    assertEquals(1, brief.mappingIntents().size());
    assertEquals("task-start", brief.mappingIntents().getFirst().sourceRef());
    assertEquals("create-task", brief.mappingIntents().getFirst().targetRef());
    assertEquals(1, MappingGapCoverage.uncovered(brief).size());
    assertEquals("create-task", MappingGapCoverage.uncovered(brief).getFirst().sourceInteractionId());
    assertEquals("task-result", MappingGapCoverage.uncovered(brief).getFirst().targetInteractionId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    MappingGapWait.View remainder = MappingGapWait.parse(PipelineGates.strip(latestWaitingPrompt()));
    assertEquals(List.of("create-task -> task-result"), remainder.missingEdges());
  }

  @Test
  void rejectedHopNamesItsInteractionIds() {
    waitAtMappingGap();
    mappingAdapter =
        (brief, message) ->
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "task-result",
                    List.of(new MappingIntentRule("name", "Subject", null))));

    List<PipelineSignal> signals = type("Map task-start straight to task-result.");

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(2, MappingGapCoverage.uncovered(storedBrief()).size());
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(message -> message.text().contains("task-start -> task-result")));
  }

  @Test
  void skipRemainingAfterFirstHopLeavesMappedHopAndSkipsTheRest() {
    waitAtMappingGap();
    mappingAdapter = requestOnlyAdapter();
    type(REQUEST_ONLY);

    type("pass_through");

    RequirementBrief brief = storedBrief();
    assertEquals(1, brief.mappingIntents().size());
    assertEquals(1, brief.skippedTransitions().size());
    assertEquals("create-task", brief.skippedTransitions().getFirst().sourceInteractionId());
    assertEquals("task-result", brief.skippedTransitions().getFirst().targetInteractionId());
    assertTrue(MappingGapCoverage.uncovered(brief).isEmpty());
    assertEquals(RunStatus.RUNNING, run().run().status());
  }

  @Test
  void emptyRuleSiblingKeepsTheValidHop() {
    waitAtMappingGap();
    mappingAdapter =
        (brief, message) ->
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null))),
                new AddIntent("create-task", "task-result", List.of()));

    type(DESCRIBE_PROSE);

    RequirementBrief brief = storedBrief();
    assertEquals(1, brief.mappingIntents().size());
    assertEquals("task-start", brief.mappingIntents().getFirst().sourceRef());
    assertFalse(brief.mappingIntents().getFirst().mappingIntentId().isBlank());
    assertEquals(1, MappingGapCoverage.uncovered(brief).size());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    MappingGapWait.View remainder = MappingGapWait.parse(PipelineGates.strip(latestWaitingPrompt()));
    assertEquals(List.of("create-task -> task-result"), remainder.missingEdges());
    MappingTurnTelemetry.Event event = mappingTelemetry.events().getLast();
    assertEquals(1, event.appliedHopCount());
    assertEquals(1, event.omittedHopCount());
    assertEquals(1, event.uncoveredRemainderSize());
    assertEquals("STAY", event.stayOrLeave());
  }

  @Test
  void rejectedEmptyRulesStayWithVisibleReasonAndOriginalRemainder() {
    waitAtMappingGap();
    mappingAdapter =
        (brief, message) ->
            MappingTurnResult.changes(new AddIntent("task-start", "create-task", List.of()));

    List<PipelineSignal> signals = type(FIRST_HOP_ONLY);

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(2, MappingGapCoverage.uncovered(storedBrief()).size());
    MappingGapWait.View remainder = MappingGapWait.parse(PipelineGates.strip(latestWaitingPrompt()));
    assertTrue(remainder.missingEdges().contains("task-start -> create-task"));
    assertTrue(remainder.missingEdges().contains("create-task -> task-result"));
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(
                message ->
                    message.text() != null
                        && !message.text().isBlank()
                        && message.text().contains("still needs a mapping rule or a skip")));
  }

  @Test
  void emptyChangesStayWithVisibleReason() {
    waitAtMappingGap();
    mappingAdapter = (brief, message) -> MappingTurnResult.changes();

    List<PipelineSignal> signals = type("??");

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(2, MappingGapCoverage.uncovered(storedBrief()).size());
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(message -> message.text() != null && !message.text().isBlank()));
  }

  @Test
  void mappingGapTurnRecordsCoverageTelemetryWithoutRawProse() {
    waitAtMappingGap();

    type(DESCRIBE_PROSE);

    assertFalse(mappingTelemetry.events().isEmpty());
    MappingTurnTelemetry.Event event = mappingTelemetry.events().getLast();
    assertEquals("CHANGES", event.outcomeType());
    assertEquals(List.of("ADD_INTENT"), event.operationKinds());
    assertEquals("LEAVE", event.stayOrLeave());
    assertEquals(0, event.uncoveredRemainderSize());
    assertEquals(2, event.appliedHopCount());
    assertEquals(0, event.omittedHopCount());
    assertEquals("APPLIED", event.validationResult());
    assertTrue(event.latencyMs() >= 0);
    assertFalse(event.toString().contains(DESCRIBE_PROSE));
    assertFalse(event.toString().contains("completeTask"));
    assertFalse(event.toString().contains("Subject"));
  }

  @Test
  void hashConfirmationWithoutSkipRecordsDoesNotCloseTheCard() {
    waitAtMappingGap();
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.USER_INPUT,
            "1",
            "product-pipeline-runtime",
            "1",
            new UserInput(
                "hash-confirmation",
                "design-input",
                new MappingGapPassThroughConfirmation(
                        committedBriefHash(),
                        List.of(
                            new MappingGapPassThroughConfirmation.TransitionRef(
                                "task-start", "create-task"),
                            new MappingGapPassThroughConfirmation.TransitionRef(
                                "create-task", "task-result")))
                    .toJson(),
                FIXED),
            List.of(),
            null,
            provenance()));

    runtime.executeStage(RUN_ID, "design-input").collect().asList().await().indefinitely();

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertEquals(2, MappingGapCoverage.uncovered(storedBrief()).size());
  }

  @Test
  void restoreAfterPassThroughKeepsSkipRecordsWithoutHashSideChannel() {
    waitAtMappingGap();
    type("pass_through");

    ProductPipelineRunSupport restored = restoreSupport();
    restored
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RequirementBrief hydrated =
        (RequirementBrief) restored.runAttributes(RUN_ID).get("requirementBrief");
    assertEquals(2, hydrated.skippedTransitions().size());
    assertTrue(hydrated.mappingIntents().isEmpty());
    assertTrue(MappingGapCoverage.uncovered(hydrated).isEmpty());
  }

  @Test
  void restoreAfterDescribeProseHydratesBriefMappingIntents() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);

    ProductPipelineRunSupport restored = restoreSupport();
    restored
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals("design-input", run().run().currentStageId());
    RequirementBrief hydrated = (RequirementBrief) restored.runAttributes(RUN_ID).get("requirementBrief");
    assertEquals(2, hydrated.mappingIntents().size());
    assertTrue(MappingGapCoverage.uncovered(hydrated).isEmpty());
    assertNotEquals(DESCRIBE_PROSE, restored.runAttributes(RUN_ID).get("userText"));
  }

  private void assertDescribeAppliedWithoutRecapture(
      SemanticRecoveryState.RemainingAttempts before) {
    assertEquals("design-input", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    RequirementBrief brief = storedBrief();
    assertEquals(2, brief.mappingIntents().size());
    assertTrue(MappingGapCoverage.uncovered(brief).isEmpty());
    assertTrue(
        brief.mappingIntents().stream()
            .anyMatch(
                intent ->
                    "task-start".equals(intent.sourceRef())
                        && "create-task".equals(intent.targetRef())));
    assertTrue(
        brief.mappingIntents().stream()
            .anyMatch(
                intent ->
                    "create-task".equals(intent.sourceRef())
                        && "task-result".equals(intent.targetRef())));
    UserInput latestAnalysis = latestUserInputTargeting("requirement-analysis");
    assertNotEquals(DESCRIBE_PROSE, latestAnalysis.text());
    assertNotEquals(DESCRIBE_PROSE, support.runAttributes(RUN_ID).get("userText"));
    assertEquals(before, remaining());
  }

  private RequirementBrief storedBrief() {
    Object fromAttributes = support.runAttributes(RUN_ID).get("requirementBrief");
    if (fromAttributes instanceof RequirementBrief brief) {
      return brief;
    }
    return artifactStore
        .latest(RUN_ID, Kind.REQUIREMENT_BRIEF)
        .map(revision -> artifactStore.payload(revision, RequirementBrief.class))
        .orElseThrow();
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
    return newSupport();
  }

  private ProductPipelineRunSupport newSupport() {
    return ProductPipelineRunSupport.builder(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(analysisCapability(), designInputCapability(), planningCapability())),
            clock)
        .mappingTurnAdapter((brief, message) -> mappingAdapter.interpret(brief, message))
        .mappingTurnTelemetry(mappingTelemetry)
        .build();
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
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Uncovered flow transitions",
            "ref",
            "draft",
            List.of())
        .withFlow(
            new RequirementFlow(
                List.of(
                    new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                    new Interaction(
                        "create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
                    new Interaction(
                        "task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                List.of(
                    new Transition("task-start", "create-task"),
                    new Transition("create-task", "task-result"))));
  }

  private static MappingTurnAdapter coveringAdapter() {
    return (brief, message) ->
        MappingTurnResult.changes(
            new AddIntent(
                "task-start",
                "create-task",
                List.of(new MappingIntentRule("name", "Subject", null))),
            new AddIntent(
                "create-task",
                "task-result",
                List.of(new MappingIntentRule("", "commandType", "Set to completeTask."))));
  }

  private static MappingTurnAdapter requestOnlyAdapter() {
    return (brief, message) ->
        MappingTurnResult.changes(
            new AddIntent(
                "task-start",
                "create-task",
                List.of(new MappingIntentRule("name", "Subject", null))));
  }

  private static MappingTurnAdapter inventingInterpreterAdapter() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            MappingTurnCapture.Kind.CHANGES,
            List.of(
                new IntentChange(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null)),
                    null),
                new IntentChange(
                    "create-task",
                    "task-result",
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")),
                    null)),
            List.of(),
            "",
            List.of());
    return new MappingTurnInterpreter((flow, intents, message) -> capture);
  }

  private static MappingTurnAdapter queryAdapter() {
    return (brief, message) ->
        new Query(
            new MappingQuerySelector(
                null,
                null,
                null,
                null,
                null,
                false,
                MappingQuerySelector.Coverage.PASS_THROUGH));
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
