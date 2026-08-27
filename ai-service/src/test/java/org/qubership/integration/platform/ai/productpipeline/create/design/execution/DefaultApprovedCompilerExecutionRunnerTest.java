package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

class DefaultApprovedCompilerExecutionRunnerTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T13:00:00Z");
  private static final String RUN_ID = "run-engine-conv-1";
  private static final String CONVERSATION_ID = "conv-engine-real-1";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CompilerDagExecutionEngine engine;
  private DefaultApprovedCompilerExecutionRunner runner;
  private final AtomicReference<CompilerDagExecutionRequest> capturedRequest =
      new AtomicReference<>();
  private final AtomicReference<String> capturedAttemptId = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    runStore = new ProductPipelineRunStore(blobs, mapper, clock);
    artifactStore =
        new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    engine = mock(CompilerDagExecutionEngine.class);
    when(engine.execute(
            any(CompilerDagExecutionRequest.class), any(String.class), any(BiConsumer.class)))
        .thenAnswer(
            invocation -> {
              capturedRequest.set(invocation.getArgument(0));
              capturedAttemptId.set(invocation.getArgument(1));
              return Uni.createFrom().item(successfulEngineResult());
            });
    runner = new DefaultApprovedCompilerExecutionRunner(engine, runStore, artifactStore);
  }

  @Test
  void engineRequestReceivesConversationIdResolvedFromRunStore() {
    RunManifest manifest = sampleManifest();
    Reference manifestRef =
        artifactStore
            .append(
                new AppendCommand(
                    RUN_ID,
                    Kind.RUN_MANIFEST,
                    "1",
                    "test",
                    "1",
                    manifest,
                    List.of(),
                    null,
                    new ArtifactProvenance(
                        RUN_ID,
                        "design-execution",
                        "create-chain",
                        "2",
                        "profile-sha",
                        "design-execution",
                        "1",
                        "closure")))
            .reference();
    runStore.create(
        new RunSnapshot(
            RUN_ID,
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "design-execution",
            List.of(),
            manifestRef));

    runner.execute(
        samplePlan(), sampleFlow(), List.of(), manifest, "attempt-2", (skillId, status) -> {});

    CompilerDagExecutionRequest request = capturedRequest.get();
    assertEquals(RUN_ID, request.runId());
    assertEquals(CONVERSATION_ID, request.conversationId());
    assertNotEquals(request.runId(), request.conversationId());
    assertEquals("attempt-2", capturedAttemptId.get());
  }

  private static CompilerDagExecutionResult successfulEngineResult() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "ok",
        List.of("cip-trigger-generator"),
        new PlanningPatchLedger(List.of(), List.of()),
        graph,
        new GraphAssemblyResult(1, graph, "graph-digest", List.of(), List.of(), List.of()),
        new CompilerValidationBundle(
            1,
            "graph-digest",
            List.of(
                new CompilerValidationPass(
                    "graph", new ValidationResult(true, List.of(), "ok")))));
  }

  private static DesignExecutionPlan samplePlan() {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        ApprovalPolicy.CATALOG_FIRST_V1,
        List.of(
            new DesignExecutionPlan.Step(
                "step-1-cip-trigger-generator",
                1,
                "Step 1",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-content-hash",
        Map.of("cip-trigger-generator", "skill-hash-trigger"),
        Map.of("cip-trigger-generator", "addon-hash-trigger"),
        "catalog-hash",
        ApprovalPolicy.CATALOG_FIRST_V1_HASH);
  }

  private static NormalizedDesignFlow sampleFlow() {
    return new NormalizedDesignFlow(
        "1",
        "flow-1",
        "Pets",
        "",
        new NormalizedDesignFlow.Trigger("http", "client", "HTTP", "/pets", "GET", List.of()),
        List.of(new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of())),
        List.of(
            new NormalizedDesignFlow.Step(
                "call-1", "service-call", "client", "petstore", "GET /pets", "", List.of())),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static RunManifest sampleManifest() {
    ResolvedCompilerNode node =
        new ResolvedCompilerNode(
            "cip-trigger-generator",
            "Generation",
            null,
            List.of(),
            List.of("GRAPH_PATCH_ARTIFACT"),
            List.of(),
            "captureGraphPatch",
            List.of(),
            List.of(),
            true,
            List.of(),
            0,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler",
            "1",
            "pkg-digest",
            1,
            "1",
            "catalog-hash",
            new ResolvedCompilerDag(List.of(node), List.of(), "dag-digest"),
            List.of("cip-trigger-generator"),
            Map.of("cip-trigger-generator", "skill-hash-trigger"),
            Map.of("cip-trigger-generator", "addon-hash-trigger"),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "profile-sha",
        "baseline",
        "baseline-digest",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(),
        pin);
  }
}
