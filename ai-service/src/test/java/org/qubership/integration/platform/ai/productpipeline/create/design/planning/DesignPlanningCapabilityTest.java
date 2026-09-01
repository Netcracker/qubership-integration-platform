package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class DesignPlanningCapabilityTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:00:00Z");
  private static final String RUN_ID = "run-design-planning-1";
  private static final String SEED_CAPABILITY = "seed-design-inputs";
  private static final String PINNED_SKILL_HASH = "pinned-cip-design-planner-hash";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = designPlanningProfile();
    runtime =
        new CreateChainTestOrchestrator(
            ProductPipelineRunSupport.builder(
                    runStore,
                    artifactStore,
                    new StageCapabilityRegistry(
                        List.of(new SeedDesignInputsCapability(), designPlanningCapability())),
                    Clock.fixed(FIXED, ZoneOffset.UTC))
                .compilerRunPinResolver(stubPinResolver())
                .build(),
            runStore);
  }

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
  }

  @Test
  void workerThreadToolsReachTheTurnSink() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, null, "conv-1");
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
              ToolInvocationSink.onInvoke("runOnce");
              ToolInvocationSink.onComplete("runOnce");
              return validReport();
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            planner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);
    try {
      capability.execute(sampleContext()).collect().asList().await().indefinitely();
    } finally {
      ToolInvocationSink.unbind();
    }

    assertTrue(
        out.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "skill".equals(step.kind())
                        && "Planning the implementation".equals(step.label())
                        && "running".equals(step.status())),
        () -> "expected cip-design-planner running on the turn sink, got: " + out);
    assertTrue(
        out.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "tool".equals(step.kind())
                        && "Running run once".equals(step.label())),
        () -> "expected worker tool steps on the turn sink, got: " + out);
  }

  @Test
  void approvalTargetIsImplementationPlanWithCatalogFirstPolicyAndReusableInputs() {
    startRun();
    PipelineSignal.WaitingForApproval waiting =
        acceptInput("seed").stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected WaitingForApproval"));

    StageSnapshot beforeApproval = currentStage("design-planning");
    Set<Kind> beforeKinds =
        beforeApproval.outputRefs().stream().map(Reference::kind).collect(Collectors.toSet());
    assertFalse(beforeKinds.contains(Kind.CHAIN_PLAN_GRAPH));
    assertFalse(beforeKinds.contains(Kind.GRAPH_ASSEMBLY_RESULT));
    assertFalse(beforeKinds.contains(Kind.PLAN_VALIDATION_RESULT));
    assertFalse(beforeKinds.contains(Kind.COMPILER_VALIDATION_BUNDLE));

    Reference idsRef = refOf(beforeApproval, Kind.IDS_DOCUMENT);
    Reference revisionRef = refOf(beforeApproval, Kind.CHAIN_SEMANTIC_REVISION);
    Reference reportRef = refOf(beforeApproval, Kind.DESIGN_PLAN_REPORT);
    Reference projectionRef = refOf(beforeApproval, Kind.DESIGN_EXECUTION_PLAN);
    Reference implementationPlanRef = refOf(beforeApproval, Kind.IMPLEMENTATION_PLAN);
    assertEquals(implementationPlanRef, waiting.candidate());

    // Same refs as the seed stage — no duplicate IDS/flow revisions.
    assertEquals(refOf(currentStage("seed"), Kind.IDS_DOCUMENT), idsRef);
    assertEquals(refOf(currentStage("seed"), Kind.CHAIN_SEMANTIC_REVISION), revisionRef);

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID, waiting.candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ApprovalRecordV2 approval = latestApprovalV2();
    assertEquals(
        Set.of(idsRef, revisionRef, reportRef, projectionRef, implementationPlanRef),
        Set.copyOf(approval.approvedCandidates()));
    assertEquals(implementationPlanRef, approval.target());
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1, approval.bindingResolutionPolicy());
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1_HASH, approval.bindingResolutionPolicyHash());
  }

  @Test
  void emitsDesignPlannerSkillProgressAroundExecution() {
    List<CapabilitySignal> signals =
        designPlanningCapability()
            .execute(sampleContext())
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && CipDesignPlannerAdapter.SKILL_ID.equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && CipDesignPlannerAdapter.SKILL_ID.equals(sp.skillId())
                        && "completed".equals(sp.status())));
  }

  @Test
  void mapsPlannerContractFailureToContractFailureOutcome() {
    CipDesignPlannerAdapter failingPlanner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
              throw new PlannerContractException("forced contract failure");
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            failingPlanner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);

    StageOutcome outcome =
        capability.execute(sampleContext()).collect().asList().await().indefinitely().stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("forced contract failure"));
  }

  @Test
  void repairTurnCarriesHaltEvidenceAndFollowUpToThePlannerRunner() {
    AtomicReference<Optional<String>> seenRepairEvidence = new AtomicReference<>();
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
              seenRepairEvidence.set(repairEvidence);
              return validReport();
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            planner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);

    capability
        .execute(sampleContextWithHaltAttributes())
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertTrue(seenRepairEvidence.get().isPresent());
    String evidence = seenRepairEvidence.get().get();
    assertTrue(evidence.contains("CONTRACT_FAILURE"), evidence);
    assertTrue(evidence.contains("design-planning"), evidence);
    assertTrue(evidence.contains("missing trigger coverage for step-call"), evidence);
    assertTrue(evidence.contains("planner report rejected: missing trigger step"), evidence);
    assertTrue(evidence.contains("keep the trigger on Orders API"), evidence);
  }

  @Test
  void aRejectedPlanStaysWithTheHaltThatRejectedIt() {
    // Well formed enough for the parser, so the report exists by the time the projector refuses it
    // for covering no trigger.
    String reportWithoutTrigger =
        validReport().replace("(cip-trigger-generator)", "(cip-script-generator)");
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) ->
                reportWithoutTrigger,
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            planner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);

    StageOutcome outcome =
        capability.execute(sampleContext()).collect().asList().await().indefinitely().stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    DesignPlanReport kept =
        outcome.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.DESIGN_PLAN_REPORT)
            .map(candidate -> (DesignPlanReport) candidate.payload())
            .findFirst()
            .orElseThrow(() -> new AssertionError("halt dropped the report it rejected"));
    assertTrue(kept.markdown().contains("Generate Service Call element"), kept.markdown());
    assertTrue(
        outcome.candidates().stream()
            .noneMatch(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_PLAN),
        "the projection never existed, so nothing may claim it did");
  }

  @Test
  void repairTurnCarriesTheRejectedPlanToThePlannerRunner() {
    AtomicReference<Optional<String>> seenRepairEvidence = new AtomicReference<>();
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
              seenRepairEvidence.set(repairEvidence);
              return validReport();
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            planner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);
    Reference rejectedPlan = appendRejectedPlanReport("1. Generate nothing at all");

    capability
        .execute(sampleContextWithHaltAttributes(rejectedPlan))
        .collect()
        .asList()
        .await()
        .indefinitely();

    String evidence = seenRepairEvidence.get().orElseThrow();
    assertTrue(evidence.contains("rejectedPlan"), evidence);
    assertTrue(evidence.contains("1. Generate nothing at all"), evidence);
  }

  @Test
  void firstTurnCarriesNoRepairEvidenceToThePlannerRunner() {
    AtomicReference<Optional<String>> seenRepairEvidence = new AtomicReference<>();
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
              seenRepairEvidence.set(repairEvidence);
              return validReport();
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            planner,
            new DesignPlanProjector(),
            new DesignImplementationPlanRenderer(),
            artifactStore);

    capability.execute(sampleContext()).collect().asList().await().indefinitely();

    assertEquals(Optional.empty(), seenRepairEvidence.get());
  }

  @Test
  void repairEvidenceTextFormatsEveryHaltField() {
    StageRepairEvidence repair =
        new StageRepairEvidence(
            "CONTRACT_FAILURE",
            "design-planning",
            "missing trigger coverage for step-call",
            "planner report rejected: missing trigger step",
            "keep the trigger on Orders API");

    String text = DesignPlanningCapability.repairEvidenceText(repair, "");

    assertTrue(text.contains("outcomeClass: CONTRACT_FAILURE"), text);
    assertTrue(text.contains("failedStageId: design-planning"), text);
    assertTrue(text.contains("validationFindings:\nmissing trigger coverage"), text);
    assertTrue(text.contains("errorEvidence:\nplanner report rejected"), text);
    assertTrue(text.contains("authorFollowUp: keep the trigger on Orders API"), text);
  }

  @Test
  void repairEvidenceTextOmitsStageIdFollowUpAndAddsMappingIntentHint() {
    StageRepairEvidence repair =
        new StageRepairEvidence(
            "CONTRACT_FAILURE",
            "design-planning",
            "planner report mapping-generator step is missing mappingIntentId",
            "planner report mapping-generator step is missing mappingIntentId",
            "requirement-analysis");

    String text = DesignPlanningCapability.repairEvidenceText(repair, "1. Encode mapping (cip-script-generator)");

    assertFalse(text.contains("authorFollowUp:"), text);
    assertTrue(text.contains("repairHint:"), text);
    assertTrue(text.contains("mappingIntentId=<id>"), text);
    assertTrue(text.contains("rejectedPlan:"), text);
  }

  @Test
  void rendererPreservesPlannerReportTextInOrder() {
    DesignPlanReport report = new DesignPlanReport("1", validReport());
    ChainSemanticRevision revision = sampleRevision();
    DesignExecutionPlan projection =
        new DesignPlanProjector()
            .project(
                report,
                revision,
                samplePin(
                    revision,
                    sampleDag(),
                    Map.of(CipDesignPlannerAdapter.SKILL_ID, PINNED_SKILL_HASH),
                    Map.of(CipDesignPlannerAdapter.SKILL_ID, "addon-hash")));
    ImplementationPlan plan =
        new DesignImplementationPlanRenderer().render(report, projection, revision);

    int previousIndex = -1;
    for (DesignExecutionPlan.Step step : projection.steps()) {
      int index = plan.planText().indexOf(step.reportText());
      assertTrue(index >= 0, "missing reportText: " + step.reportText());
      assertTrue(index > previousIndex, "reportText order changed for " + step.stepId());
      previousIndex = index;
      assertTrue(plan.planText().contains(step.stepId()));
    }
    assertTrue(plan.planText().contains(ApprovalPolicy.CATALOG_FIRST_V1));
    assertTrue(plan.scriptOutcomes().isEmpty(), "pass-through mappings do not require scripts");
  }

  @Test
  void plannerInputRequiresLiteralMappingIntentIdToken() {
    String input =
        DesignPlanningCapability.buildPlannerInput(
            sampleIds(), SemanticFixtures.linearOrdersWithMapping(), "2024.4");

    assertTrue(input.contains("mappingIntentId=<id>"), input);
    assertTrue(input.contains("mappingIntentId=map-init"), input);
  }

  @Test
  void plannerInputDoesNotRequestScriptsForPassThroughMappings() {
    String input =
        DesignPlanningCapability.buildPlannerInput(sampleIds(), sampleRevision(), "2024.4");

    assertTrue(input.contains("No mapping intents. Do not plan mapping scripts."), input);
  }

  @Test
  void plannerInputDoesNotListSyntheticKnownIdentities() {
    String input =
        DesignPlanningCapability.buildPlannerInput(sampleIds(), sampleRevision(), "2024.4");

    assertFalse(input.contains("Known identities. Reference only these names"), input);
    assertFalse(input.contains(sampleRevision().chainIdentity() + " Service"), input);
  }

  @Test
  void legacyApprovalRecordV2OmitsBindingResolutionPolicyFields() throws Exception {
    ApprovalRecordV2 v1Approval =
        new ApprovalRecordV2(
            new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
            "hash-plan",
            List.of(
                new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
                new Reference(Kind.PLAN_VALIDATION_RESULT, "val-1", "hash-val"),
                new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "hash-graph")),
            "user",
            null,
            Instant.parse("2026-07-22T12:00:00Z"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertNull(v1Approval.bindingResolutionPolicy());
    assertNull(v1Approval.bindingResolutionPolicyHash());

    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    String serializedV1Approval =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(v1Approval);
    assertFalse(serializedV1Approval.contains("bindingResolutionPolicy"));
    assertEquals(
        readFixture("product-pipelines/approval/approval-record-v2-legacy.json").trim(),
        serializedV1Approval.trim());
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPinResolver resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.doNothing().when(resolver).verifyAvailable(org.mockito.ArgumentMatchers.any());
    return resolver;
  }

  private DesignPlanningCapability designPlanningCapability() {
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> validReport(),
            new CipDesignPlannerReportParser());
    return new DesignPlanningCapability(
        planner,
        new DesignPlanProjector(),
        new DesignImplementationPlanRenderer(),
        artifactStore);
  }

  private List<PipelineSignal> startRun() {
    return runtime
        .startOrResume(
            new StartOrResumeCommand("conv-design-planning", RUN_ID, profile, sampleManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private List<PipelineSignal> acceptInput(String text) {
    return runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private StageSnapshot currentStage(String stageId) {
    return runStore.load(RUN_ID).orElseThrow().run().stages().stream()
        .filter(stage -> stage.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private static Reference refOf(StageSnapshot stage, Kind kind) {
    return stage.outputRefs().stream()
        .filter(ref -> ref.kind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing " + kind + " in " + stage.outputRefs()));
  }

  private ApprovalRecordV2 latestApprovalV2() {
    return artifactStore.payload(
        artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
            .filter(item -> item.schemaVersion().equals("2"))
            .reduce((first, second) -> second)
            .orElseThrow(),
        ApprovalRecordV2.class);
  }

  private StageExecutionContext sampleContext() {
    IdsDocument ids = sampleIds();
    ChainSemanticRevision revision = sampleRevision();
    Reference idsRef = new Reference(Kind.IDS_DOCUMENT, "ids-1", "ids-hash");
    Reference revisionRef =
        new Reference(Kind.CHAIN_SEMANTIC_REVISION, revision.revisionId(), "revision-hash");
    return new StageExecutionContext(
        RUN_ID,
        "conv-1",
        "design-planning",
        "exec-1",
        "attempt-1",
        profile,
        sampleManifest(),
        List.of(idsRef, revisionRef),
        Map.of("idsDocument", ids, "chainSemanticRevision", revision));
  }

  /** Appends a plan report the way the runtime records the output of a halted planning attempt. */
  private Reference appendRejectedPlanReport(String markdown) {
    return artifactStore
        .append(
            new AppendCommand(
                RUN_ID,
                Kind.DESIGN_PLAN_REPORT,
                "1",
                DesignPlanningCapability.CAPABILITY_ID,
                "1",
                new DesignPlanReport("1", markdown),
                List.of(),
                null,
                new ArtifactProvenance(
                    RUN_ID,
                    "design-planning",
                    "test-design-planning",
                    "1",
                    "profile-sha",
                    DesignPlanningCapability.CAPABILITY_ID,
                    "1",
                    "closure-sha")))
        .reference();
  }

  /** {@link #sampleContext()} plus the halt attributes the runtime writes before a recoverable halt. */
  private StageExecutionContext sampleContextWithHaltAttributes(Reference... priorOutputs) {
    StageExecutionContext base = sampleContext();
    Map<String, Object> attributes = new HashMap<>(base.attributes());
    if (priorOutputs.length > 0) {
      attributes.put(StageRepairEvidence.PRIOR_OUTPUT_REFS_ATTR, List.of(priorOutputs));
    }
    attributes.put(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
        "planner report rejected: missing trigger step");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, "CONTRACT_FAILURE");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-planning");
    attributes.put(
        ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR,
        "missing trigger coverage for step-call");
    attributes.put(
        ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR, "keep the trigger on Orders API");
    return new StageExecutionContext(
        base.runId(),
        base.conversationId(),
        base.stageId(),
        base.executionKey(),
        base.attemptId(),
        base.profile(),
        base.runManifest(),
        base.inputRefs(),
        attributes);
  }

  private ProductPipelineProfile designPlanningProfile() {
    return new ProductPipelineProfile(
        1,
        "test-design-planning",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "seed",
                SEED_CAPABILITY,
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(
                    new ArtifactTypeRef("ids-document", 1),
                    new ArtifactTypeRef("chain-semantic-revision", 1)),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-planning",
                DesignPlanningCapability.CAPABILITY_ID,
                List.of(
                    new ArtifactTypeRef("ids-document", 1),
                    new ArtifactTypeRef("chain-semantic-revision", 1),
                    new ArtifactTypeRef("run-manifest", 1)),
                List.of(),
                List.of(
                    new ArtifactTypeRef("design-plan-report", 1),
                    new ArtifactTypeRef("design-execution-plan", 1),
                    new ArtifactTypeRef("implementation-plan", 2)),
                List.of(),
                new ApprovalPolicy(
                    new ArtifactTypeRef("implementation-plan", 2),
                    List.of(
                        new ArtifactTypeRef("ids-document", 1),
                        new ArtifactTypeRef("chain-semantic-revision", 1),
                        new ArtifactTypeRef("design-plan-report", 1),
                        new ArtifactTypeRef("design-execution-plan", 1),
                        new ArtifactTypeRef("implementation-plan", 2)),
                    ApprovalPolicy.CATALOG_FIRST_V1,
                    ApprovalPolicy.CATALOG_FIRST_V1_HASH),
                null,
                new RetryPolicy(0, 1L),
                null)),
        new TerminalPolicy("design-planning", "PLAN_APPROVED"),
        List.of(SEED_CAPABILITY, DesignPlanningCapability.CAPABILITY_ID));
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
        List.of(new DependencyClosureEntry(DesignPlanningCapability.CAPABILITY_ID, "1", "c1")),
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
        new CompilerRunPin(
            "compiler",
            "1",
            "digest",
            2,
            "1",
            "catalog-hash",
            sampleDag(),
            List.of(DesignPlanningCapability.CAPABILITY_ID),
            Map.of(CipDesignPlannerAdapter.SKILL_ID, PINNED_SKILL_HASH),
            Map.of(CipDesignPlannerAdapter.SKILL_ID, "addon-hash"),
            List.of(),
            Kind.CHAIN_SEMANTIC_REVISION.name(),
            sampleRevision().schemaVersion(),
            sampleRevision().revisionId(),
            "design-input-hash",
            sampleRevision().compilerContractVersion(),
            "contract-sha"));
  }

  private static String readFixture(String path) throws Exception {
    try (InputStream in =
        DesignPlanningCapabilityTest.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static IdsDocument sampleIds() {
    return new IdsDocument(
        "1",
        IdsDocument.Mode.PROVIDED,
        "user-ids",
        "source-hash",
        "flow-hash",
        "ids-document-parser@1",
        """
        # IDS
        ## Integration flow for CIP Chain - Orders
        ```mermaid
        sequenceDiagram
          autonumber
          participant Client
          participant CIP
          participant Orders
          Client->>CIP: createOrder
          CIP->>Orders: createOrder
        ```
        """);
  }

  private static ChainSemanticRevision sampleRevision() {
    return SemanticFixtures.linearOrders();
  }

  private static CompilerRunPin samplePin(
      ChainSemanticRevision revision,
      ResolvedCompilerDag dag,
      Map<String, String> skillHashes,
      Map<String, String> addonHashes) {
    return new CompilerRunPin(
        "compiler",
        "1",
        "digest",
        2,
        "1",
        "catalog-hash",
        dag,
        List.of(DesignPlanningCapability.CAPABILITY_ID),
        skillHashes,
        addonHashes,
        List.of(),
        Kind.CHAIN_SEMANTIC_REVISION.name(),
        revision.schemaVersion(),
        revision.revisionId(),
        "design-input-hash",
        revision.compilerContractVersion(),
        "contract-sha");
  }

  private static ResolvedCompilerDag sampleDag() {
    return DesignPlanProjectorTestSupport.sampleDag();
  }

  private static String validReport() {
    return DesignPlanProjectorTestSupport.validReport();
  }

  private static final class SeedDesignInputsCapability implements StageCapability {
    @Override
    public String capabilityId() {
      return SEED_CAPABILITY;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      if (!context.attributes().containsKey("userText")) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need seed input")));
      }
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  new StageOutcome(
                      StageOutcomeClass.SUCCEEDED,
                      List.of(
                          new ArtifactCandidate(Kind.IDS_DOCUMENT, sampleIds(), List.of()),
                          new ArtifactCandidate(
                              Kind.CHAIN_SEMANTIC_REVISION, sampleRevision(), List.of())),
                      "seeded design inputs",
                      null)));
    }
  }

  /** Shared fixtures mirroring DesignPlanProjectorTest without package-private coupling. */
  private static final class DesignPlanProjectorTestSupport {
    private static String validReport() {
      return """
          1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
          2. Find API Orders API for Orders Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
          3. Get API operation specification Orders API for Orders Service in APIHub (APIHub MCP get_rest_api_operations_specification)
          4. Resolve External integration target Orders Service from the retrieved spec (binding for cip-service-call-generator)
          5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
          6. Generate Service Call element for Orders Service.createOrder bound to the retrieved spec (cip-service-call-generator)
          7. Generate execution structure and element ordering (cip-structure-generator)
          8. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
          9. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
          10. Validate the assembled chain (cip-chain-validator)
          If you agree, reply **Agree** or **Execute plan** to proceed.
          """
          .trim();
    }

    private static ResolvedCompilerDag sampleDag() {
      return new ResolvedCompilerDag(
          List.of(
              node(
                  "cip-requirement-analyzer",
                  List.of(SkillArtifactType.RAW_USER_REQUEST.name()),
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(),
                  0),
              node(
                  "cip-naming-generator",
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                  List.of("cip-requirement-analyzer"),
                  1),
              node(
                  "cip-trigger-generator",
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(),
                  2),
              node(
                  "cip-service-call-generator",
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of("cip-trigger-generator"),
                  3),
              node(
                  "cip-script-generator",
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of("cip-service-call-generator"),
                  4),
              node(
                  "cip-structure-generator",
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  List.of(
                      "cip-trigger-generator",
                      "cip-service-call-generator",
                      "cip-script-generator"),
                  5),
              node(
                  "cip-chain-assembler",
                  List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                  List.of("cip-structure-generator"),
                  6),
              node(
                  "cip-chain-validator",
                  List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                  List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                  List.of("cip-chain-assembler"),
                  7)),
          List.of(),
          "dag-digest");
    }

    private static ResolvedCompilerNode node(
        String skillId,
        List<String> consumes,
        List<String> produces,
        List<String> dependsOn,
        int level) {
      return new ResolvedCompilerNode(
          skillId,
          "Planning",
          null,
          consumes,
          produces,
          dependsOn,
          null,
          List.of(),
          List.of(),
          true,
          List.of(),
          level,
          0,
          true,
          CompilerNodeExecutionMode.LLM_SKILL,
          null);
    }
  }
}
