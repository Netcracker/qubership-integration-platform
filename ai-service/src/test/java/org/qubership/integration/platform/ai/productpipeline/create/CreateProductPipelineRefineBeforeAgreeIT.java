package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementBriefCapture;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
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
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Mock-LLM e2e: each gated CREATE stage receives a refine-before-Agree turn. Analysis uses the real
 * {@link RequirementAnalysisCapability} + {@link RequirementBriefTool} path so a second
 * {@code captureRequirementBrief} must succeed after capture-slot clear (no stream failure).
 */
class CreateProductPipelineRefineBeforeAgreeIT {

  private static final Instant FIXED = Instant.parse("2026-07-27T15:00:00Z");
  private static final String RUN_ID = "run-refine-before-agree-e2e";
  private static final String CONVERSATION_ID = "conv-refine-before-agree-e2e";

  private ProductPipelineRunStore runStore;
  private ProductPipelineRuntime runtime;
  private ProductPipelineProfile profile;
  private CaptureSession captureSession;
  private final AtomicInteger discoveryCalls = new AtomicInteger();
  private final AtomicInteger analysisCaptures = new AtomicInteger();
  private final AtomicInteger planningCalls = new AtomicInteger();
  private final AtomicReference<String> lastAnalysisMessage = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    ProductPipelineArtifactStore artifactStore = new ProductPipelineArtifactStore(artifacts);
    captureSession = new CaptureSession();
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    profile = createProfile();
    runtime =
        new ProductPipelineRuntime(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(
                    discovery(),
                    importStage(),
                    realAnalysis(captureSession, feedbackStore),
                    planning())),
            clock);
  }

  @Test
  void eachStageRefinesBeforeAgreeWithoutCaptureCrash() {
    runtime
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, profile, sampleManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    Reference discoveryFirst = acceptInput("create greetings API").candidate();
    Reference discoverySecond = acceptInput("add quartz scheduler").candidate();
    assertNotEquals(discoveryFirst, discoverySecond);
    assertEquals(2, discoveryCalls.get());

    Reference analysisFirst = approveAndAwaitApproval(discoverySecond).candidate();
    assertEquals("requirement-analysis", loadCurrentStageId());
    assertEquals(1, analysisCaptures.get());
    assertTrue(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION_ID)));

    // Change-request re-runs analysis: capture slot must clear so the tool path does not throw.
    Reference analysisSecond = acceptInput("add quartz trigger").candidate();
    assertNotEquals(analysisFirst, analysisSecond);
    assertEquals(2, analysisCaptures.get());
    assertTrue(lastAnalysisMessage.get().contains("add quartz trigger"));
    RequirementBrief refined =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION_ID),
                RequirementBrief.class)
            .orElseThrow();
    assertTrue(refined.goal().contains("quartz"));

    Reference planningFirst = approveAndAwaitApproval(analysisSecond).candidate();
    assertEquals("planning", loadCurrentStageId());
    assertEquals(1, planningCalls.get());

    Reference planningSecond = acceptInput("prefer async split").candidate();
    assertNotEquals(planningFirst, planningSecond);
    assertEquals(2, planningCalls.get());

    List<PipelineSignal> afterPlan =
        runtime
            .approve(new ApproveCommand(RUN_ID, planningSecond, runStore.load(RUN_ID).orElseThrow().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(afterPlan.stream().anyMatch(PipelineSignal.Completed.class::isInstance));
    assertEquals(RunStatus.PLAN_APPROVED, runStore.load(RUN_ID).orElseThrow().run().status());
    assertFalse(
        afterPlan.stream().anyMatch(PipelineSignal.Failed.class::isInstance),
        "refine-before-agree must not fail the stream");
  }

  private StageCapability realAnalysis(
      CaptureSession session, CaptureAttemptFeedbackStore feedbackStore) {
    RequirementBriefTool briefTool =
        new RequirementBriefTool(
            session,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    return new RequirementAnalysisCapability(
        knowledge,
        knowledge,
        new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
        session,
        feedbackStore,
        null,
        null,
        (conversationId, userMessage) -> {
          lastAnalysisMessage.set(userMessage);
          org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
          RequirementDraft approved =
              ProductCapabilityCaptureContext.approvedDraft()
                  .orElseGet(RequirementFactFixtures::greetingsApprovedDraft);
          int call = analysisCaptures.incrementAndGet();
          String goal = call == 1 ? "Greetings brief" : "Greetings with quartz";
          String result =
              briefTool.captureRequirementBrief(
                  new RequirementBriefCapture(
                      goal,
                      List.of(),
                      List.of(),
                      List.of(),
                      goal,
                      null,
                      approved.planningText(),
                      approved.facts(),
                      List.of()));
          assertTrue(result.contains("Requirement brief captured"), result);
          return Multi.createFrom().item(ChatEvent.token("analysis-ok"));
        },
        null);
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
        int call = discoveryCalls.incrementAndGet();
        RequirementDraft base = RequirementFactFixtures.greetingsApprovedDraft();
        String assembled =
            call == 1 ? base.assembledText() : base.assembledText() + "\nRefine: " + userText;
        RequirementDraft draft =
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
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
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

  private StageCapability planning() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return PlanningCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        int call = planningCalls.incrementAndGet();
        String label = call == 1 ? "plan-v1" : "plan-v2:" + context.attributeAsString("userText");
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.IMPLEMENTATION_PLAN, Map.of("plan", label), List.of()),
                            new ArtifactCandidate(
                                Kind.PLAN_VALIDATION_RESULT, Map.of("ok", label), List.of()),
                            new ArtifactCandidate(
                                Kind.CHAIN_PLAN_GRAPH, Map.of("graph", label), List.of())),
                        "plan " + call,
                        null)));
      }
    };
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
            .approve(
                new ApproveCommand(
                    RUN_ID, candidate, runStore.load(RUN_ID).orElseThrow().run().runRevision()))
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
                    "expected WaitingForApproval after approve, got " + signals));
  }

  private String loadCurrentStageId() {
    return runStore.load(RUN_ID).orElseThrow().run().currentStageId();
  }

  private ProductPipelineProfile createProfile() {
    return new ProductPipelineProfile(
        1,
        "create-chain-refine-e2e",
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
            "artifact-test",
            "1.0.0",
            "1.0.0",
            "digest-test",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static FakeKnowledgeClient knowledgeWithMandatoryObjects() {
    FakeKnowledgeClient client = FakeKnowledgeClient.defaultFixture();
    client.put(
        "CORPORATE_CIP_STANDARDS",
        "Standard",
        "CORPORATE_CIP_STANDARDS",
        "Corporate CIP standards.",
        List.of("CORPORATE_CIP_STANDARDS"));
    client.put(
        "pattern-standards",
        "Standard",
        "pattern-standards",
        "Pattern standards.",
        List.of("pattern-standards"));
    client.put(
        "element-standards",
        "Standard",
        "element-standards",
        "Element standards.",
        List.of("element-standards"));
    return client;
  }
}
