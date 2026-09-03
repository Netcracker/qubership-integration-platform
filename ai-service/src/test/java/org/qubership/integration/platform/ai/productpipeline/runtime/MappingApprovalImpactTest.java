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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.MappingQuerySelector;
import org.qubership.integration.platform.ai.plan.MappingTurnAdapter;
import org.qubership.integration.platform.ai.plan.MappingTurnResult;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation.TransitionRef;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * After an approved mapping change, analysis reopens and downstream artifacts are superseded. A
 * query, clarification, or stale reject leaves the wait and the plan in place.
 */
class MappingApprovalImpactTest {

  private static final Instant FIXED = Instant.parse("2026-09-03T12:00:00Z");
  private static final String RUN_ID = "run-mapping-approval-impact";
  private static final String CONV_ID = "conv-mapping-approval-impact";
  private static final String CHANGE_MESSAGE = "Subject comes from title";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;
  private Clock clock;
  private MappingTurnAdapter mappingAdapter;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = threeStageProfile();
    mappingAdapter = changeAdapter();
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(analysisCapability(), planningCapability(), executionCapability())),
                clock)
            .mappingTurnAdapter((brief, message) -> mappingAdapter.interpret(brief, message))
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void authorChangeToApprovedMappingReopensBriefAndSupersedesDownstreamArtifacts() {
    waitAtPlanningApproval();
    storeDownstreamArtifacts();
    String briefHash = committedHash(Kind.REQUIREMENT_BRIEF);
    String revisionHash = committedHash(Kind.CHAIN_SEMANTIC_REVISION);
    String planHash = committedHash(Kind.IMPLEMENTATION_PLAN);
    String designPlanHash = committedHash(Kind.DESIGN_EXECUTION_PLAN);
    String envelopeHash = committedHash(Kind.MAPPING_ENVELOPE);
    String patchHash = committedHash(Kind.GRAPH_PATCH_ARTIFACT);
    String validationHash = committedHash(Kind.COMPILER_VALIDATION_BUNDLE);
    SemanticRecoveryState.RemainingAttempts before = remaining();

    type(CHANGE_MESSAGE);

    assertEquals("requirement-analysis", run().run().currentStageId());
    assertEquals(RunStatus.RUNNING, run().run().status());
    assertEquals(StageStatus.RUNNING, snapshot("requirement-analysis").status());
    assertEquals(StageStatus.PENDING, snapshot("planning").status());
    assertEquals(StageStatus.PENDING, snapshot("design-execution").status());
    assertTrue(snapshot("planning").outputRefs().isEmpty());
    assertTrue(snapshot("design-execution").outputRefs().isEmpty());
    Map<String, Object> attributes = support.runAttributes(RUN_ID);
    assertEquals(briefHash, attributes.get(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR));
    Object superseded = attributes.get(ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR);
    assertInstanceOf(List.class, superseded);
    List<?> hashes = (List<?>) superseded;
    assertTrue(hashes.contains(revisionHash));
    assertTrue(hashes.contains(planHash));
    assertTrue(hashes.contains(designPlanHash));
    assertTrue(hashes.contains(envelopeHash));
    assertTrue(hashes.contains(patchHash));
    assertTrue(hashes.contains(validationHash));
    RequirementBrief updated = (RequirementBrief) attributes.get("requirementBrief");
    assertEquals("$.title", updated.mappingIntents().getFirst().rules().getFirst().sourcePath());
    assertEquals(CHANGE_MESSAGE, attributes.get("userText"));
    assertEquals(before, remaining());
  }

  @Test
  void queryDoesNotReopenTheBriefOrInvalidateAPlan() {
    waitAtPlanningApproval();
    storeDownstreamArtifacts();
    mappingAdapter = queryAdapter();
    long revisionBefore = run().run().runRevision();
    ReferenceLike before = approvalBinding();

    List<PipelineSignal> signals = type("Which transitions are pass-through?");

    assertStillAtPlanningApproval(revisionBefore, before);
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .anyMatch(message -> message.text() != null && !message.text().isBlank()));
    assertNoSupersession();
  }

  @Test
  void clarificationDoesNotReopenTheBriefOrInvalidateAPlan() {
    waitAtPlanningApproval();
    storeDownstreamArtifacts();
    mappingAdapter =
        (brief, message) ->
            new Clarification("AMBIGUOUS_TRANSITION", List.of("create-task", "task-result"));
    long revisionBefore = run().run().runRevision();
    ReferenceLike before = approvalBinding();

    type("Which hop writes Subject?");

    assertStillAtPlanningApproval(revisionBefore, before);
    assertNoSupersession();
  }

  @Test
  void rejectedStaleResultDoesNotReopenTheBriefOrInvalidateAPlan() {
    waitAtPlanningApproval();
    storeDownstreamArtifacts();
    mappingAdapter = (brief, message) -> MappingTurnResult.changes();
    MappingIntent intent = mappedBrief().mappingIntents().getFirst();
    String confirmation =
        new MappingGapPassThroughConfirmation(
                "stale-revision",
                List.of(new TransitionRef(intent.sourceRef(), intent.targetRef())))
            .toJson();
    long revisionBefore = run().run().runRevision();
    ReferenceLike before = approvalBinding();

    type(confirmation);

    assertStillAtPlanningApproval(revisionBefore, before);
    assertNoSupersession();
  }

  private void waitAtPlanningApproval() {
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
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("planning", run().run().currentStageId());
  }

  private void storeDownstreamArtifacts() {
    append(
        Kind.CHAIN_SEMANTIC_REVISION,
        ChainSemanticRevision.CURRENT_SCHEMA_VERSION,
        SemanticFixtures.linearOrders());
    append(Kind.DESIGN_EXECUTION_PLAN, "1", Map.of("semanticRevisionId", "rev-1"));
    append(Kind.MAPPING_ENVELOPE, "1", Map.of("digest", "envelope-1"));
    append(Kind.GRAPH_PATCH_ARTIFACT, "1", Map.of("patchId", "patch-1"));
    append(Kind.COMPILER_VALIDATION_BUNDLE, "1", Map.of("bundle", "ok"));
  }

  private void append(Kind kind, String schemaVersion, Object payload) {
    artifactStore.append(
        new AppendCommand(
            RUN_ID, kind, schemaVersion, "test", "1", payload, List.of(), null, provenance()));
  }

  private ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID,
        "planning",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "planning",
        "1",
        "closure-sha");
  }

  private List<PipelineSignal> type(String text) {
    return support
        .recordInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void assertStillAtPlanningApproval(long revisionBefore, ReferenceLike before) {
    assertEquals("planning", run().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals(revisionBefore, run().run().runRevision());
    assertEquals(before.approvable(), snapshot("planning").approvableReference());
    assertEquals(StageStatus.WAITING_FOR_APPROVAL, snapshot("planning").status());
    assertEquals(StageStatus.PENDING, snapshot("design-execution").status());
  }

  private void assertNoSupersession() {
    Map<String, Object> attributes = support.runAttributes(RUN_ID);
    assertFalse(attributes.containsKey(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR));
    assertFalse(attributes.containsKey(ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR));
  }

  private ReferenceLike approvalBinding() {
    StageSnapshot snapshot = snapshot("planning");
    return new ReferenceLike(snapshot.approvableReference());
  }

  private SemanticRecoveryState.RemainingAttempts remaining() {
    return support.captureSemanticRecoveryState(RUN_ID).remaining();
  }

  private String committedHash(Kind kind) {
    return artifactStore.latest(RUN_ID, kind).map(Revision::contentHash).orElseThrow();
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

  private MappingTurnAdapter changeAdapter() {
    return (brief, message) ->
        MappingTurnResult.changes(
            new UpdateRule(
                brief.mappingIntents().getFirst().mappingIntentId(),
                "Subject",
                "title",
                null,
                null));
  }

  private MappingTurnAdapter queryAdapter() {
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

  private static StageCapability analysisCapability() {
    return new ScriptedCapability(
        "requirement-analysis",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, mappedBrief(), List.of())),
            "brief ready",
            null));
  }

  private static StageCapability planningCapability() {
    return new ScriptedCapability(
        "planning",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(
                new ArtifactCandidate(
                    Kind.IMPLEMENTATION_PLAN, new ImplementationPlan("plan"), List.of())),
            "plan ready",
            null));
  }

  private static StageCapability executionCapability() {
    return new ScriptedCapability(
        "design-execution",
        new StageOutcome(
            StageOutcomeClass.CONTRACT_FAILURE, List.of(), "execution must not run", null));
  }

  private static RequirementBrief mappedBrief() {
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map onTaskStart into createTask",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withFlow(
            new RequirementFlow(
                List.of(
                    new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                    new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", "")),
                List.of(new Transition("task-start", "create-task"))))
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-task-start-to-create-task",
                    "task-start",
                    MappingPort.OUTPUT,
                    "create-task",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "name", "Subject", null, MappingRuleStatus.USER_DEFINED)))));
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef userInput = new ArtifactTypeRef("user-input", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    ArtifactTypeRef graph = new ArtifactTypeRef("chain-plan-graph", 1);
    return new ProductPipelineProfile(
        1,
        "test-mapping-approval-impact",
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
                "planning",
                "planning",
                List.of(brief),
                List.of(plan),
                new ApprovalPolicy(plan, List.of(plan)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "design-execution",
                List.of(plan),
                List.of(graph),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "CHAIN_READY"),
        List.of("requirement-analysis", "planning", "design-execution"));
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
            new DependencyClosureEntry("planning", "1", "c2"),
            new DependencyClosureEntry("design-execution", "1", "c3")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private record ReferenceLike(Object approvable) {}

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
