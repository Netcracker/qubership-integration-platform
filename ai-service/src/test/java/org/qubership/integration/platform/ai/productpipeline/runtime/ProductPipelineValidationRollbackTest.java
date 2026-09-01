package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineValidationRollbackTest {

  private static final Instant FIXED = Instant.parse("2026-07-27T12:00:00Z");
  private static final String RUN_ID = "run-validation-rollback-1";

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
  }

  @Test
  void planningValidationFailureHaltsWithRetryInsteadOfReopeningBriefApproval() {
    ScriptedCapability analysis =
        new ScriptedCapability(
            "requirement-analysis",
            new StageOutcome(
                StageOutcomeClass.CANDIDATE,
                List.of(
                    new ArtifactCandidate(
                        Kind.REQUIREMENT_BRIEF, Map.of("goal", "pending pets"), List.of())),
                "brief ready",
                null));
    ScriptedCapability planning =
        new ScriptedCapability(
            "planning",
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
                null));

    profile = twoStageProfile();
    runtime =
        new CreateChainTestOrchestrator(
            ProductPipelineRunSupport.builder(
                    runStore,
                    artifactStore,
                    new StageCapabilityRegistry(List.of(analysis, planning)),
                    Clock.fixed(FIXED, ZoneOffset.UTC))
                .build(),
            runStore);

    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-rollback", RUN_ID, profile, sampleManifest(RUN_ID)))
        .collect()
        .asList()
        .await()
        .indefinitely();

    List<PipelineSignal> afterAnalysisInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "build pending pets"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    PipelineSignal.WaitingForApproval briefWaiting =
        afterAnalysisInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("requirement-analysis", briefWaiting.stageId());

    long revision = runStore.load(RUN_ID).orElseThrow().run().runRevision();
    List<PipelineSignal> afterApprove =
        runtime
            .approve(new ApproveCommand(RUN_ID, briefWaiting.candidate(), revision))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertValidationHalt(afterApprove);

    var doc = runStore.load(RUN_ID).orElseThrow();
    assertEquals(RunStatus.WAITING_FOR_INPUT, doc.run().status());
    assertEquals("planning", doc.run().currentStageId());
    assertEquals(
        StageStatus.WAITING_FOR_INPUT,
        doc.run().stages().stream()
            .filter(s -> s.stageId().equals("planning"))
            .findFirst()
            .orElseThrow()
            .status());
  }

  private void assertValidationHalt(List<PipelineSignal> signals) {
    assertTrue(
        signals.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        "expected WaitingForInput halt after planning validation");
    assertTrue(
        signals.stream().noneMatch(PipelineSignal.Failed.class::isInstance),
        "validation halt must not fail the run");
  }

  private static ProductPipelineProfile twoStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 2);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "test-validation-rollback",
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

  private RunManifest sampleManifest(String runId) {
    return new RunManifest(
        runId,
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
      // Analysis still waits for acceptInput. Planning must not — approve clears stage-local
      // userText so IDS path stages cannot misread "Agree" as a design-mode reply.
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
