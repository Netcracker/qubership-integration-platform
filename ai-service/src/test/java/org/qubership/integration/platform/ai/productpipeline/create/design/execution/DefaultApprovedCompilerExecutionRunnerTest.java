package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
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
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerExecutionSeed;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

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
    ChainSemanticGraphCompiler graphCompiler = mock(ChainSemanticGraphCompiler.class);
    when(graphCompiler.compile(any(), any(), any()))
        .thenReturn(successfulEngineResult().graph());
    when(engine.execute(
            any(CompilerDagExecutionRequest.class), any(String.class), any(BiConsumer.class)))
        .thenAnswer(
            invocation -> {
              capturedRequest.set(invocation.getArgument(0));
              capturedAttemptId.set(invocation.getArgument(1));
              return Uni.createFrom().item(successfulEngineResult());
            });
    runner =
        new DefaultApprovedCompilerExecutionRunner(
            engine,
            runStore,
            artifactStore,
            graphCompiler,
            new ClasspathCompilerContractRepository());
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
        samplePlan(),
        sampleRevision(),
        List.of(sampleBinding()),
        manifest,
        "attempt-2",
        (skillId, status) -> {});

    CompilerDagExecutionRequest request = capturedRequest.get();
    assertEquals(RUN_ID, request.runId());
    assertEquals(CONVERSATION_ID, request.conversationId());
    assertNotEquals(request.runId(), request.conversationId());
    assertEquals("attempt-2", capturedAttemptId.get());
    assertTrue(
        request.effectiveSeed().presentArtifactTypes().contains("CHAIN_SEMANTIC_REVISION"));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("CHAIN_PLAN_GRAPH"));
    assertEquals(
        List.of(sampleBinding()),
        ((SkillArtifactPayload.ServiceCallBindingsPayload)
                request
                    .effectiveSeed()
                    .artifacts()
                    .stream()
                    .filter(artifact -> artifact.type() == SkillArtifactType.SERVICE_CALL_BINDINGS)
                    .findFirst()
                    .orElseThrow()
                    .payload())
            .bindings());
    assertTrue(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.STRUCTURE_GENERATOR_SKILL));
    assertTrue(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.PATTERN_SELECTOR_SKILL));
    assertTrue(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.TRIGGER_GENERATOR_SKILL));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("SELECTED_PATTERN"));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("CONFIGURED_TRIGGER_SET"));
  }

  @Test
  void executeRejectsADesignPlanPinnedToADifferentSemanticRevision() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    planForRevision("revision-other"),
                    sampleRevision(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(thrown.getMessage().contains("does not match the approved semantic revision"));
  }

  @Test
  void executeRejectsALiveMappingIntentCollectionThatDiffersFromTheRevision() {
    storeBrief(
        ordersBriefWith(
            List.of(
                new MappingIntent(
                    "map-live",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "call-1",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.orderId",
                            "$.orderId",
                            null,
                            MappingRuleStatus.USER_DEFINED))))));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    samplePlan(),
                    sampleRevision(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(
        thrown.getMessage().contains("Live mapping-intent collection differs from the approved"));
  }

  @Test
  void executeAcceptsLiveMappingIntentsWhenRevisionRefsAreProjectedEdgeIds() {
    MappingIntent liveIntent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "customerId", null)));
    storeBrief(ordersBriefWith(List.of(liveIntent)));
    persistRun();

    runner.execute(
        samplePlan(),
        SemanticFixtures.linearOrdersWithMapping(),
        List.of(sampleBinding()),
        sampleManifest(),
        "attempt-1",
        (skillId, status) -> {});

    RequirementBrief stored =
        artifactStore.payload(
            artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow(),
            RequirementBrief.class);
    assertEquals("trigger-http", stored.mappingIntents().getFirst().sourceRef());
    assertEquals("node-call", stored.mappingIntents().getFirst().targetRef());
  }

  @Test
  void executeRejectsLiveMappingIntentsWhenTheMappingIntentIdSetDiffers() {
    storeBrief(
        ordersBriefWith(
            List.of(
                new MappingIntent(
                    "map-other",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "node-call",
                    MappingPort.REQUEST,
                    List.of(new MappingIntentRule("id", "customerId", null))))));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    samplePlan(),
                    SemanticFixtures.linearOrdersWithMapping(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(
        thrown.getMessage().contains("Live mapping-intent collection differs from the approved"));
  }

  @Test
  void executeRejectsLiveMappingIntentsWhenRulesDifferFromTheRevision() {
    storeBrief(
        ordersBriefWith(
            List.of(
                new MappingIntent(
                    "map-init",
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "node-call",
                    MappingPort.REQUEST,
                    List.of(new MappingIntentRule("id", "orderId", null))))));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    samplePlan(),
                    SemanticFixtures.linearOrdersWithMapping(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(
        thrown.getMessage().contains("Live mapping-intent collection differs from the approved"));
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
    return planForRevision("revision-orders");
  }

  private static DesignExecutionPlan planForRevision(String semanticRevisionId) {
    return new DesignExecutionPlan(
        "1",
        semanticRevisionId,
        "cip-design-planner",
        "chain-semantic-revision/" + semanticRevisionId,
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

  private static RequirementBrief ordersBriefWith(List<MappingIntent> mappingIntents) {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map OM output",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(mappingIntents);
  }

  private void storeBrief(RequirementBrief brief) {
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.REQUIREMENT_BRIEF,
            "1",
            "requirement-analysis",
            "1",
            brief,
            List.of(),
            null,
            new ArtifactProvenance(
                RUN_ID,
                "requirement-analysis",
                "create-chain",
                "2",
                "profile-sha",
                "requirement-analysis",
                "1",
                "closure")));
  }

  private void persistRun() {
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
  }

  private static ChainSemanticRevision sampleRevision() {
    return SemanticFixtures.linearOrders();
  }

  private static ResolvedServiceCallBinding sampleBinding() {
    return new ResolvedServiceCallBinding(
        "call-orders",
        "call-orders",
        "EXTERNAL",
        "sys-orders",
        "sg-orders",
        "spec-orders",
        "op-orders",
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-orders",
        "");
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

  @Test
  void scopeDagDoesNotPullUnplannedGeneratorsThroughAssembler() {
    ResolvedCompilerDag full =
        new ResolvedCompilerDag(
            List.of(
                generationNode("cip-naming-generator", List.of()),
                generationNode("cip-trigger-generator", List.of("cip-naming-generator")),
                generationNode("cip-quartz-scheduler-generator", List.of("cip-naming-generator")),
                terminalNode(
                    "cip-chain-assembler",
                    "Assembly",
                    List.of("cip-trigger-generator", "cip-quartz-scheduler-generator")),
                terminalNode(
                    "cip-structural-validator",
                    "Validation",
                    List.of("cip-chain-assembler"))),
            List.of(
                edge("cip-naming-generator", "cip-trigger-generator"),
                edge("cip-naming-generator", "cip-quartz-scheduler-generator"),
                edge("cip-trigger-generator", "cip-chain-assembler"),
                edge("cip-quartz-scheduler-generator", "cip-chain-assembler"),
                edge("cip-chain-assembler", "cip-structural-validator")),
            "full-dag");

    ResolvedCompilerDag scoped =
        DefaultApprovedCompilerExecutionRunner.scopeDag(
            full,
            List.of(
                "cip-trigger-generator", "cip-chain-assembler", "cip-structural-validator"));

    Set<String> skillIds =
        scoped.nodes().stream().map(ResolvedCompilerNode::skillId).collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of(
            "cip-naming-generator",
            "cip-trigger-generator",
            "cip-chain-assembler",
            "cip-structural-validator"),
        skillIds);
    assertFalse(skillIds.contains("cip-quartz-scheduler-generator"));
    assertEquals(
        List.of("cip-trigger-generator"),
        node(scoped, "cip-chain-assembler").dependsOn());
  }

  private static ResolvedCompilerNode generationNode(String skillId, List<String> dependsOn) {
    return terminalNode(skillId, "Generation", dependsOn);
  }

  private static ResolvedCompilerNode terminalNode(
      String skillId, String phase, List<String> dependsOn) {
    return new ResolvedCompilerNode(
        skillId,
        phase,
        null,
        List.of(),
        List.of(),
        dependsOn,
        null,
        List.of(),
        List.of(),
        true,
        List.of(),
        0,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }

  private static CompilerPipelineDependency edge(String producer, String consumer) {
    return new CompilerPipelineDependency(producer, consumer, List.of());
  }

  private static ResolvedCompilerNode node(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream()
        .filter(n -> skillId.equals(n.skillId()))
        .findFirst()
        .orElseThrow();
  }
}
