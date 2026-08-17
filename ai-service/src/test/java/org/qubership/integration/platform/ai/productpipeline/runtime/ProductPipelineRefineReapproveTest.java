package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Deterministic CREATE pipeline rewind/re-approve coverage: on each gated stage, send a change
 * request before Agree and assert the second candidate replaces the first.
 */
class ProductPipelineRefineReapproveTest {

  private static final Instant FIXED = Instant.parse("2026-07-27T12:00:00Z");
  private static final String RUN_ID = "run-refine-reapprove-1";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;

  private final AtomicInteger discoveryCalls = new AtomicInteger();
  private final AtomicInteger analysisCalls = new AtomicInteger();
  private final AtomicInteger planningCalls = new AtomicInteger();
  private final AtomicReference<String> lastDiscoveryText = new AtomicReference<>();
  private final AtomicReference<String> lastAnalysisText = new AtomicReference<>();
  private final AtomicReference<String> lastPlanningText = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = createProfile();
    runtime =
        new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(discovery(), importStage(), analysis(), planning())),
            Clock.fixed(FIXED, ZoneOffset.UTC)), runStore);
  }

  @Test
  void discoveryAnalysisAndPlanningSupportRefineThenReapprove() {
    startRun();

    Reference discoveryFirst = acceptInput("create greetings API").candidate();
    Reference discoverySecond = acceptInput("add quartz scheduler").candidate();
    assertNotEquals(discoveryFirst, discoverySecond);
    assertEquals(2, discoveryCalls.get());
    assertEquals("add quartz scheduler", lastDiscoveryText.get());

    Reference analysisFirst = approveAndAwaitApproval(discoverySecond).candidate();
    assertEquals("requirement-analysis", loadRun().run().currentStageId());
    assertEquals(1, analysisCalls.get());

    Reference analysisSecond = acceptInput("add retry on http trigger").candidate();
    assertNotEquals(analysisFirst, analysisSecond);
    assertEquals(2, analysisCalls.get());
    assertEquals("add retry on http trigger", lastAnalysisText.get());

    Reference planningFirst = approveAndAwaitApproval(analysisSecond).candidate();
    assertEquals("planning", loadRun().run().currentStageId());
    assertEquals(1, planningCalls.get());

    Reference planningSecond = acceptInput("prefer async split").candidate();
    assertNotEquals(planningFirst, planningSecond);
    assertEquals(2, planningCalls.get());
    assertEquals("prefer async split", lastPlanningText.get());

    List<PipelineSignal> afterPlan =
        runtime
            .approve(
                new ApproveCommand(RUN_ID, planningSecond, loadRun().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(
        afterPlan.stream().anyMatch(PipelineSignal.Completed.class::isInstance),
        "expected terminal Completed after planning Agree, got " + afterPlan);

    ProductPipelineRunDocument doc = loadRun();
    assertEquals(RunStatus.PLAN_APPROVED, doc.run().status());
    assertEquals(StageStatus.SUCCEEDED, stage(doc, "planning").status());

    ApprovalRecordV2 approval =
        artifactStore.payload(
            artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
                .filter(item -> "2".equals(item.schemaVersion()))
                .reduce((first, second) -> second)
                .orElseThrow(),
            ApprovalRecordV2.class);
    assertNull(approval.bindingResolutionPolicy());
    assertNull(approval.bindingResolutionPolicyHash());
    assertEquals(planningSecond, approval.target());
  }

  private PipelineSignal.WaitingForApproval acceptInput(String text) {
    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, text))
            .collect()
            .asList()
            .await()
            .indefinitely();
    return signals.stream()
        .filter(PipelineSignal.WaitingForApproval.class::isInstance)
        .map(PipelineSignal.WaitingForApproval.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected WaitingForApproval, got " + signals));
  }

  private PipelineSignal.WaitingForApproval approveAndAwaitApproval(Reference candidate) {
    List<PipelineSignal> signals =
        runtime
            .approve(new ApproveCommand(RUN_ID, candidate, loadRun().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    return signals.stream()
        .filter(PipelineSignal.WaitingForApproval.class::isInstance)
        .map(PipelineSignal.WaitingForApproval.class::cast)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "expected next-stage WaitingForApproval after approve, got " + signals));
  }

  private void startRun() {
    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-refine", RUN_ID, profile, sampleManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private ProductPipelineRunDocument loadRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private static org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot stage(
      ProductPipelineRunDocument doc, String stageId) {
    return doc.run().stages().stream()
        .filter(s -> s.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private StageCapability discovery() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        String userText = context.attributeAsString("userText");
        if (userText == null || userText.isBlank()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
        }
        lastDiscoveryText.set(userText);
        int call = discoveryCalls.incrementAndGet();
        RequirementDraft base = RequirementFactFixtures.greetingsApprovedDraft();
        String assembled =
            call == 1 ? base.assembledText() : base.assembledText() + "\nRefine: " + userText;
        RequirementDraft candidate =
            new RequirementDraft(
                true,
                assembled,
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                "brainstorming",
                "1",
                null,
                null,
                null,
                false,
                base.facts());
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, candidate, List.of())),
                        "draft " + call,
                        null)));
      }
    };
  }

  private static StageCapability importStage() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return SpecificationImportCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        Object approved = context.attributes().get("approvedDraft");
        RequirementDraft draft =
            approved instanceof RequirementDraft requirementDraft
                ? requirementDraft
                : RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "import skipped",
                        null)));
      }
    };
  }

  private StageCapability analysis() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementAnalysisCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        String userText = context.attributeAsString("userText");
        lastAnalysisText.set(userText);
        int call = analysisCalls.incrementAndGet();
        String goal = call == 1 ? "brief-v1" : "brief-v2:" + userText;
        RequirementBrief brief =
            new RequirementBrief(goal, List.of("fact"), List.of(), List.of(), List.of(), goal);
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                        "analyzed " + call,
                        null)));
      }
    };
  }

  private StageCapability planning() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return PlanningCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        String userText = context.attributeAsString("userText");
        lastPlanningText.set(userText);
        int call = planningCalls.incrementAndGet();
        String planLabel = call == 1 ? "plan-v1" : "plan-v2:" + userText;
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.IMPLEMENTATION_PLAN, Map.of("plan", planLabel), List.of()),
                            new ArtifactCandidate(
                                Kind.PLAN_VALIDATION_RESULT,
                                Map.of("validation", planLabel),
                                List.of()),
                            new ArtifactCandidate(
                                Kind.CHAIN_PLAN_GRAPH, Map.of("graph", planLabel), List.of())),
                        "plan " + call,
                        null)));
      }
    };
  }

  private ProductPipelineProfile createProfile() {
    return new ProductPipelineProfile(
        1,
        "create-chain-refine",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                RequirementDiscoveryCapability.CAPABILITY_ID,
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(new ArtifactTypeRef("requirement-draft", 2)),
                new ApprovalPolicy(new ArtifactTypeRef("requirement-draft", 2)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "import-stage",
                SpecificationImportCapability.CAPABILITY_ID,
                List.of(new ArtifactTypeRef("requirement-draft", 2)),
                List.of(new ArtifactTypeRef("requirement-draft", 2)),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "requirement-analysis",
                RequirementAnalysisCapability.CAPABILITY_ID,
                List.of(new ArtifactTypeRef("requirement-draft", 2)),
                List.of(new ArtifactTypeRef("requirement-brief", 1)),
                new ApprovalPolicy(new ArtifactTypeRef("requirement-brief", 1)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                PlanningCapability.CAPABILITY_ID,
                List.of(new ArtifactTypeRef("requirement-brief", 1)),
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
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of(
            RequirementDiscoveryCapability.CAPABILITY_ID,
            SpecificationImportCapability.CAPABILITY_ID,
            RequirementAnalysisCapability.CAPABILITY_ID,
            PlanningCapability.CAPABILITY_ID));
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
        List.of(new DependencyClosureEntry(PlanningCapability.CAPABILITY_ID, "1", "c1")),
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
