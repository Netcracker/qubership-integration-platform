package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.MappingGenerationPipeline;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;
import io.smallrye.mutiny.Uni;

class CompilerDagExecutionEngineTest {

  private InMemorySkillWorkspaceStore workspaceStore;
  private SkillExecutorRegistry skillRegistry;
  private CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;
  private QipKnowledgePackRepository packRepository;
  private DefaultCompilerDagExecutionEngine engine;

  @BeforeEach
  void setUp() {
    PlanCompilationTestSupport.memory();
    workspaceStore = new InMemorySkillWorkspaceStore(new ChainPlanStore());
    skillRegistry = mock(SkillExecutorRegistry.class);
    javaAdapterRegistry = mock(CompilerNodeExecutionAdapterRegistry.class);
    packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.activeVersion()).thenReturn(new QipKnowledgePackVersion("v1", "v1"));
    CanonicalGraphDigest digest =
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    GraphAssemblyService graphAssemblyService = new GraphAssemblyService(digest);
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerValidationPipeline validationPipeline =
        new CompilerValidationPipeline(
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            securityValidator,
            qualityValidator);
    engine =
        new DefaultCompilerDagExecutionEngine(
            workspaceStore,
            skillRegistry,
            javaAdapterRegistry,
            packRepository,
            graphAssemblyService,
            validationPipeline);
  }

  @Test
  void executeRunsProvidedDagAndReturnsSucceededResult() {
    String conversationId = "conv-engine-direct";
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(new PatternExecutor());

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(
            eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
        .thenReturn(
            new CompilerNodeExecutionResult(
                List.of(
                    SkillArtifact.of(
                        SkillArtifactType.PRE_BUILD_VALIDATION,
                        "cip-structural-validator",
                        new SkillArtifactPayload.ValidationResultPayload(
                            new ValidationResult(true, List.of(), "ok")))),
                List.of()));

    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            "seed",
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(graphForAssembly(), List.of(), List.of()))));

    AtomicInteger progressEvents = new AtomicInteger();
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of());

    CompilerDagExecutionResult result =
        engine
            .execute(request, (skillId, status) -> progressEvents.incrementAndGet())
            .await()
            .indefinitely();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertNull(result.message());
    assertEquals(
        List.of("cip-pattern-selector", "cip-chain-assembler", "cip-structural-validator"),
        result.executedSkillIds());
    assertEquals(graphForAssembly(), result.graph());
    assertNotNull(result.assemblyResult());
    assertEquals(List.of(), result.assemblyResult().orderedPatchReferences());
    assertEquals(List.of(), result.patchLedger().orderedReferences());
    assertNotNull(result.validationBundle());
    assertTrue(result.validationBundle().approvalEligible());
    assertTrue(progressEvents.get() >= 2);
    assertEquals(List.of(), result.degradationFindings());

    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertTrue(workspace.get(SkillArtifactType.REQUIREMENT_BRIEF).isPresent());
    assertTrue(workspace.get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE).isPresent());
  }

  @Test
  void aFailedStructureSkillDoesNotPassTheSeededStructureOffAsItsOutput() {
    String conversationId = "conv-structure-failed";
    ResolvedCompilerDag dag = dagWithStructureGenerator();
    when(skillRegistry.require("cip-structure-generator")).thenReturn(new FailingStructureExecutor());

    // An edit run seeds the imported graph as CHAIN_STRUCTURE before any skill runs. The failed
    // skill must not inherit it, or the caller reads an untouched graph as a completed stage.
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            CompilerExecutionSeed.SEED_PRODUCER,
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(graphForAssembly(), List.of(), List.of()))));

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of());

    assertThrows(
        PlanningSkillArtifactUnavailableException.class,
        () -> engine.execute(request, (skillId, status) -> {}).await().indefinitely());
  }

  @Test
  void engineImplementsSharedInterface() {
    assertTrue(engine instanceof CompilerDagExecutionEngine);
  }

  @Test
  void requireArtifactsFailsClosedWhenMaterializationMapIsMissing() {
    CompilerDagExecutionResult result =
        new CompilerDagExecutionResult(
            StageOutcomeClass.SUCCEEDED,
            null,
            List.of(),
            null,
            graphForAssembly(),
            null,
            null,
            List.of(),
            Set.of("CHAIN_SEMANTIC_REVISION", "CHAIN_PLAN_GRAPH"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                result.requireArtifacts(
                    Set.of("CHAIN_SEMANTIC_REVISION", "CHAIN_PLAN_GRAPH", "MATERIALIZATION_MAP")));

    assertEquals(
        "Compiler run completed without required artifact: MATERIALIZATION_MAP", error.getMessage());
  }

  @Test
  void createCompileSucceedsWithoutMaterializationMap() {
    String conversationId = "conv-missing-mmap";
    ChainSemanticRevision revision = simpleRevision();
    ChainPlanGraph graph = simpleGraph();
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(new PatternExecutor());
    stubAssemblyAndValidator(dag);
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_SEMANTIC_REVISION,
            CompilerExecutionSeed.SEMANTIC_COMPILER_PRODUCER,
            new SkillArtifactPayload.ChainSemanticRevisionPayload(revision)));
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            CompilerExecutionSeed.SEMANTIC_COMPILER_PRODUCER,
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            "seed",
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(graph, List.of(), List.of()))));

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            revision,
            dag,
            List.of(),
            List.of());

    CompilerDagExecutionResult result =
        engine.execute(request, (skillId, status) -> {}).await().indefinitely();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertFalse(result.presentArtifactTypes().contains("MATERIALIZATION_MAP"));
  }

  @Test
  void editCompileFailsClosedWhenSeededMaterializationMapIsDropped() {
    String conversationId = "conv-dropped-mmap";
    ChainPlanGraph graph = simpleGraph();
    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("trigger", "el-1"), Map.of(), Map.of());
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(skillRegistry.require("cip-pattern-selector"))
        .thenReturn(new DropMaterializationMapExecutor());
    stubAssemblyAndValidator(dag);

    CompilerExecutionSeed seed =
        new CompilerExecutionSeed(
            conversationId,
            true,
            "keep the imported catalog join",
            List.of(
                SkillArtifact.of(
                    SkillArtifactType.REQUIREMENT_BRIEF,
                    CompilerExecutionSeed.REQUIREMENT_ANALYZER_SKILL,
                    new SkillArtifactPayload.RequirementBriefPayload(brief())),
                SkillArtifact.of(
                    SkillArtifactType.CHAIN_PLAN_GRAPH,
                    CompilerExecutionSeed.SEED_PRODUCER,
                    new SkillArtifactPayload.ChainPlanGraphPayload(graph)),
                SkillArtifact.of(
                    SkillArtifactType.CHAIN_STRUCTURE,
                    CompilerExecutionSeed.SEED_PRODUCER,
                    new SkillArtifactPayload.ChainStructurePayload(
                        new ChainStructure(graph, List.of(), List.of()))),
                SkillArtifact.of(
                    SkillArtifactType.MATERIALIZATION_MAP,
                    CompilerExecutionSeed.SEED_PRODUCER,
                    new SkillArtifactPayload.MaterializationMapPayload(map))),
            Set.of(CompilerExecutionSeed.REQUIREMENT_ANALYZER_SKILL));

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of(),
            seed);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> engine.execute(request, (skillId, status) -> {}).await().indefinitely());

    assertEquals(
        "Compiler run completed without required artifact: MATERIALIZATION_MAP", error.getMessage());
  }

  @Test
  void skipsLlmWhenManifestMarksGeneratorSkipped() {
    String conversationId = "conv-manifest-skipped";
    ResolvedCompilerDag dag = dagWithXsltGenerator();
    CountingXsltExecutor xslt = new CountingXsltExecutor();
    when(skillRegistry.require(XSLT_SKILL)).thenReturn(xslt);
    stubAssemblyAndValidator(dag);
    CompilerExecutionSeed seed = seedChainStructure(conversationId);
    workspaceStore.putArtifact(
        conversationId, generatorPlanManifest(GeneratorPlanStatus.SKIPPED));

    List<String> progress = new ArrayList<>();
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of(),
            seed);

    CompilerDagExecutionResult result =
        engine
            .execute(
                request,
                (skillId, status) -> progress.add(skillId + ":" + status))
            .await()
            .indefinitely();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(
        List.of(XSLT_SKILL, "cip-chain-assembler", "cip-structural-validator"),
        result.executedSkillIds());
    assertEquals(0, xslt.runCount());
    verify(skillRegistry, never()).require(XSLT_SKILL);
    assertTrue(
        progress.stream().noneMatch(event -> event.startsWith(XSLT_SKILL + ":")),
        "skipped generator must not emit activity progress");
  }

  @Test
  void runsGeneratorWhenManifestIsMissing() {
    String conversationId = "conv-manifest-missing";
    ResolvedCompilerDag dag = dagWithXsltGenerator();
    CountingXsltExecutor xslt = new CountingXsltExecutor();
    when(skillRegistry.require(XSLT_SKILL)).thenReturn(xslt);
    stubAssemblyAndValidator(dag);
    CompilerExecutionSeed seed = seedChainStructure(conversationId);

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of(),
            seed);

    CompilerDagExecutionResult result =
        engine.execute(request, (skillId, status) -> {}).await().indefinitely();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(1, xslt.runCount());
    assertTrue(result.executedSkillIds().contains(XSLT_SKILL));
  }

  @Test
  void runsGeneratorWhenManifestMarksReady() {
    String conversationId = "conv-manifest-ready";
    ResolvedCompilerDag dag = dagWithXsltGenerator();
    CountingXsltExecutor xslt = new CountingXsltExecutor();
    when(skillRegistry.require(XSLT_SKILL)).thenReturn(xslt);
    stubAssemblyAndValidator(dag);
    CompilerExecutionSeed seed = seedChainStructure(conversationId);
    workspaceStore.putArtifact(
        conversationId, generatorPlanManifest(GeneratorPlanStatus.READY));

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            "run-1",
            conversationId,
            manifestFor(dag),
            brief(),
            null,
            dag,
            List.of(),
            List.of(),
            seed);

    CompilerDagExecutionResult result =
        engine.execute(request, (skillId, status) -> {}).await().indefinitely();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(1, xslt.runCount());
  }

  private static RequirementBrief brief() {
    return new RequirementBrief(
        "Greetings",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "summary",
        "draft",
        "Create greetings chain",
        List.of());
  }

  private static RunManifest manifestFor(ResolvedCompilerDag dag) {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler-v2",
            "1.0.0",
            "digest",
            2,
            "v1",
            "index-digest",
            dag,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        "run-1",
        null,
        List.of(),
        "product",
        "create-chain-v1",
        "1",
        "create-chain-v1@1",
        "reference-baseline-v1",
        "reference-baseline-v1",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "artifact",
            "1.0.0",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(),
        pin);
  }

  private static ResolvedCompilerDag dagWithMandatoryValidation() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-pattern-selector",
                "Planning",
                null,
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.SELECTED_PATTERN.name()),
                List.of("cip-requirement-analyzer"),
                "captureSelectedPattern",
                List.of(),
                List.of(),
                true,
                List.of(),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of(SkillArtifactType.SELECTED_PATTERN.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of("cip-pattern-selector"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-structural-validator",
                "Validation",
                null,
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.PRE_BUILD_VALIDATION.name()),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "structural-validation")),
        List.of(),
        "dag");
  }

  private static final String XSLT_SKILL = "cip-xslt-generator";

  private static ResolvedCompilerDag dagWithXsltGenerator() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                XSLT_SKILL,
                "Generation",
                "GEN-XSLT",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(),
                List.of("cip-requirement-analyzer"),
                "captureGraphPatch",
                List.of(),
                List.of(),
                true,
                List.of(),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(XSLT_SKILL),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-structural-validator",
                "Validation",
                null,
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.PRE_BUILD_VALIDATION.name()),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "structural-validation")),
        List.of(),
        "dag-xslt");
  }

  private CompilerExecutionSeed seedChainStructure(String conversationId) {
    return CompilerExecutionSeed.forCreate(conversationId, brief())
        .with(
            SkillArtifact.of(
                SkillArtifactType.CHAIN_STRUCTURE,
                "seed",
                new SkillArtifactPayload.ChainStructurePayload(
                    new ChainStructure(graphForAssembly(), List.of(), List.of()))));
  }

  private void stubAssemblyAndValidator(ResolvedCompilerDag dag) {
    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(
            eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
        .thenReturn(
            new CompilerNodeExecutionResult(
                List.of(
                    SkillArtifact.of(
                        SkillArtifactType.PRE_BUILD_VALIDATION,
                        "cip-structural-validator",
                        new SkillArtifactPayload.ValidationResultPayload(
                            new ValidationResult(true, List.of(), "ok")))),
                List.of()));
  }

  private static SkillArtifact generatorPlanManifest(GeneratorPlanStatus xsltStatus) {
    return SkillArtifact.of(
        SkillArtifactType.GENERATOR_PLAN_MANIFEST,
        "generator-plan-manifest",
        new SkillArtifactPayload.GeneratorPlanManifestPayload(
            new GeneratorPlanManifest(
                "v1",
                List.of(
                    new GeneratorPlan(
                        "GEN-XSLT", XSLT_SKILL, xsltStatus, List.of(), List.of())))));
  }

  private static ResolvedCompilerDag dagWithStructureGenerator() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-structure-generator",
                "Planning",
                null,
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(),
                "captureChainStructure",
                List.of(),
                List.of(),
                true,
                List.of(),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null)),
        List.of(),
        "dag");
  }

  private static ResolvedCompilerNode node(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream()
        .filter(n -> n.skillId().equals(skillId))
        .findFirst()
        .orElseThrow();
  }

  private static org.qubership.integration.platform.ai.plan.model.ChainPlanGraph graphForAssembly() {
    return new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
        "1.0",
        new org.qubership.integration.platform.ai.plan.model.ChainSection("sales", "Sales"),
        List.of(
            new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                "trigger",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                        "contextPath", "/sales"),
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                        "httpMethodRestrict", "POST"),
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                        "accessControlType", "NONE"),
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                        "externalRoute", "false")))),
        List.of());
  }

  private static ChainSemanticRevision simpleRevision() {
    return new ChainSemanticRevision(
        ChainSemanticRevision.CURRENT_SCHEMA_VERSION,
        "revision-1",
        "Sales",
        CompilerContract.V1,
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "trigger",
                "script-1",
                0,
                new SemanticProvenance(List.of()),
                null)),
        List.of(
            new SemanticNode.Trigger(
                "trigger", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("script-1", "script", new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "e1", "trigger", "script-1", null, new SemanticRoute.Sequence(), null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ChainPlanGraph simpleGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Sales", null, null, null, "revision-1", CompilerContract.V1),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "trigger", null, null, List.of()),
            new ChainPlanNode("script-1", "script", "script-1", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger", "script-1", null)));
  }

  private static final class CountingXsltExecutor implements SkillExecutor {
    private final AtomicInteger runs = new AtomicInteger();

    @Override
    public String skillId() {
      return XSLT_SKILL;
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.REQUIREMENT_BRIEF);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of();
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      runs.incrementAndGet();
      return Uni.createFrom().item(SkillExecutionResult.completed(List.of(), "xslt"));
    }

    int runCount() {
      return runs.get();
    }
  }

  private static final class FailingStructureExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-structure-generator";
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.REQUIREMENT_BRIEF);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.CHAIN_STRUCTURE);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      return Uni.createFrom().item(SkillExecutionResult.failed("capture rejected"));
    }
  }

  private static class PatternExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-pattern-selector";
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.REQUIREMENT_BRIEF);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.SELECTED_PATTERN);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      return Uni.createFrom()
          .item(
              SkillExecutionResult.completed(
                  List.of(
                      SkillArtifact.of(
                          SkillArtifactType.SELECTED_PATTERN,
                          "cip-pattern-selector",
                          new SkillArtifactPayload.SelectedPatternPayload(
                              new SelectedPattern(
                                  "GP-01", "Pattern", "reason", null, List.of(), "summary")))),
                  "ok"));
    }
  }

  /** Drops the seeded catalog join so fail-closed can observe a missing MATERIALIZATION_MAP. */
  private static final class DropMaterializationMapExecutor extends PatternExecutor {
    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      workspace.remove(SkillArtifactType.MATERIALIZATION_MAP);
      return super.run(context, workspace);
    }
  }

  @Test
  void aSkippedGeneratorAndTheManifestItFellBackOnAreRecordedAsNonBlockerFindings() {
    String conversationId = "conv-naming-fallback-findings";
    ResolvedCompilerDag dag = dagWithNamingGenerator();
    when(skillRegistry.require(NAMING_SKILL)).thenReturn(new FailingNamingExecutor());
    stubAssemblyAndValidator(dag);
    seedStructure(conversationId);
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.NAMING_MANIFEST,
            "prior-run",
            new SkillArtifactPayload.NamingManifestPayload(
                new NamingManifest(1, "Prior.Internal.Chain", Map.of(), List.of(), List.of()))));

    CompilerDagExecutionResult result = executeDag(conversationId, dag);

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(
        List.of(PlanningDegradations.GENERATOR_SKIPPED, PlanningDegradations.FALLBACK_SUBSTITUTED),
        result.degradationFindings().stream().map(PlanValidationFinding::code).toList());
    assertTrue(result.degradationFindings().stream().noneMatch(PlanValidationFinding::blocker));
    assertTrue(
        result.degradationFindings().get(1).message().contains(NAMING_SKILL),
        result.degradationFindings().get(1).message());
  }

  @Test
  void aNamingGeneratorThatLeavesNothingBehindRecordsTheSoftDefaultChainName() {
    String conversationId = "conv-naming-default-findings";
    ResolvedCompilerDag dag = dagWithNamingGenerator();
    when(skillRegistry.require(NAMING_SKILL)).thenReturn(new FailingNamingExecutor());
    stubAssemblyAndValidator(dag);
    seedStructure(conversationId);

    CompilerDagExecutionResult result = executeDag(conversationId, dag);

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(
        List.of(PlanningDegradations.GENERATOR_SKIPPED, PlanningDegradations.DEFAULT_CHAIN_NAME),
        result.degradationFindings().stream().map(PlanValidationFinding::code).toList());
    assertTrue(result.degradationFindings().stream().noneMatch(PlanValidationFinding::blocker));
    assertTrue(
        result.degradationFindings().get(1).message().contains("Generated.Internal.Chain"),
        result.degradationFindings().get(1).message());
  }

  @Test
  void emptyMappingIntentsFillCompleteTaskScriptBodyWithoutInventingIntent() {
    String conversationId = "conv-complete-task-fill";
    ResolvedCompilerDag dag = dagWithScriptGenerator();
    when(skillRegistry.require("cip-script-generator"))
        .thenReturn(new FillingCompleteTaskScriptExecutor());
    stubAssemblyAndValidator(dag);
    DefaultCompilerDagExecutionEngine mapped = engineWithMappingPipeline();

    CompilerDagExecutionResult result = executeCompleteTaskDag(mapped, conversationId, dag);

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    ChainPlanNode script =
        result.graph().nodes().stream()
            .filter(node -> SemanticFixtures.COMPLETE_TASK_NODE_ID.equals(node.nodeId()))
            .findFirst()
            .orElseThrow();
    assertEquals("script", script.type());
    assertTrue(
        script.properties().stream()
            .anyMatch(
                property ->
                    "script".equals(property.key())
                        && property.value() != null
                        && !property.value().isBlank()));
    assertTrue(SemanticFixtures.linearOrdersWithCompleteTask().mappingIntents().isEmpty());
    assertEquals(1, result.patchLedger().orderedReferences().size());
  }

  @Test
  void emptyPatchFailsWhenCompleteTaskScriptBodyStaysBlank() {
    String conversationId = "conv-complete-task-empty-patch";
    ResolvedCompilerDag dag = dagWithScriptGenerator();
    when(skillRegistry.require("cip-script-generator"))
        .thenReturn(new EmptyPatchScriptExecutor());
    stubAssemblyAndValidator(dag);
    DefaultCompilerDagExecutionEngine mapped = engineWithMappingPipeline();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> executeCompleteTaskDag(mapped, conversationId, dag));
    assertTrue(
        thrown.getMessage().contains(SemanticFixtures.COMPLETE_TASK_NODE_ID), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("without script bodies"), thrown.getMessage());
  }

  private CompilerDagExecutionResult executeDag(String conversationId, ResolvedCompilerDag dag) {
    return engine
        .execute(
            new CompilerDagExecutionRequest(
                "run-1",
                conversationId,
                manifestFor(dag),
                brief(),
                null,
                dag,
                List.of(),
                List.of()),
            (skillId, status) -> {})
        .await()
        .indefinitely();
  }

  private void seedStructure(String conversationId) {
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            "seed",
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(graphForAssembly(), List.of(), List.of()))));
  }

  private static final String NAMING_SKILL = "cip-naming-generator";

  private static ResolvedCompilerDag dagWithNamingGenerator() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                NAMING_SKILL,
                "Planning",
                "GEN-NAMING",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                List.of("cip-requirement-analyzer"),
                "captureNamingManifest",
                List.of(),
                List.of(),
                true,
                List.of(),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(NAMING_SKILL),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-structural-validator",
                "Validation",
                null,
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.PRE_BUILD_VALIDATION.name()),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "structural-validation")),
        List.of(),
        "dag-naming");
  }

  private CompilerDagExecutionResult executeCompleteTaskDag(
      DefaultCompilerDagExecutionEngine mapped,
      String conversationId,
      ResolvedCompilerDag dag) {
    RequirementBrief completeTaskBrief = completeTaskBrief();
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithCompleteTask();
    ChainPlanGraph graph = completeTaskGraph();
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forCreate(
            conversationId, completeTaskBrief, revision, graph, List.of());
    return mapped
        .execute(
            new CompilerDagExecutionRequest(
                "run-1",
                conversationId,
                manifestFor(dag),
                completeTaskBrief,
                revision,
                dag,
                List.of(),
                List.of(),
                seed),
            (skillId, status) -> {})
        .await()
        .indefinitely();
  }

  private DefaultCompilerDagExecutionEngine engineWithMappingPipeline() {
    CanonicalGraphDigest digest = new CanonicalGraphDigest(new ObjectMapper());
    GraphAssemblyService graphAssemblyService = new GraphAssemblyService(digest);
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerValidationPipeline validationPipeline =
        new CompilerValidationPipeline(
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            securityValidator,
            qualityValidator);
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.systemUTC());
    MappingGenerationPipeline pipeline =
        new MappingGenerationPipeline(artifacts, mapper, mappingContextBuilder(mapper));
    return new DefaultCompilerDagExecutionEngine(
        workspaceStore,
        skillRegistry,
        javaAdapterRegistry,
        packRepository,
        graphAssemblyService,
        validationPipeline,
        new ProductPipelineArtifactStore(artifacts),
        pipeline);
  }

  private static CompilerSkillContextBuilder mappingContextBuilder(ObjectMapper mapper) {
    QipKnowledgePackRepository repository = mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(new CompilerGeneratorSpecIndex(List.of()));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));
    return new CompilerSkillContextBuilder(
        mapper,
        repository,
        addonRepository,
        mock(CompilerSkillRuntimeEligibility.class),
        mock(KnowledgeClient.class),
        mock(KnowledgeContextProvider.class));
  }

  private static ResolvedCompilerDag dagWithScriptGenerator() {
    GraphPatchOwnershipPolicy scriptOwnership =
        new GraphPatchOwnershipPolicy(
            false, false, Set.of(), Set.of(), Map.of("script", Set.of("script")));
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-script-generator",
                "Implementation",
                null,
                List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
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
                null,
                scriptOwnership),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of("cip-script-generator"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-structural-validator",
                "Validation",
                null,
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.PRE_BUILD_VALIDATION.name()),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "structural-validation")),
        List.of(),
        "dag-script");
  }

  private static ChainPlanGraph completeTaskGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("id", "Orders"),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "Trigger", null, 1, List.of()),
            new ChainPlanNode(
                SemanticFixtures.COMPLETE_TASK_NODE_ID,
                "script",
                "completeTask",
                null,
                2,
                List.of()),
            new ChainPlanNode("node-call", "service-call", "Call", null, 3, List.of())),
        List.of(
            new ChainPlanEdge(
                "edge-1", "trigger-http", SemanticFixtures.COMPLETE_TASK_NODE_ID, null),
            new ChainPlanEdge(
                "edge-2", SemanticFixtures.COMPLETE_TASK_NODE_ID, "node-call", null)));
  }

  private static RequirementBrief completeTaskBrief() {
    return new RequirementBrief("Orders", List.of(), List.of(), List.of(), List.of(), "summary")
        .withFacts(
            List.of(
                new RequirementFact(
                    SemanticFixtures.COMPLETE_TASK_FACT_ID,
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "",
                    "Respond with commandType=completeTask")));
  }

  private static final class FillingCompleteTaskScriptExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-script-generator";
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.GRAPH_PATCH);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      GraphPatch patch =
          new GraphPatch(
              "fill-complete-task",
              "cip-script-generator",
              List.of(),
              List.of(),
              List.of(
                  new PropertyPatch(
                      GraphPatchOperation.ADD,
                      SemanticFixtures.COMPLETE_TASK_NODE_ID,
                      new PlanProperty(
                          "script",
                          "exchange.in.body = 'completeTask'\nreturn exchange.in.body"))),
              List.of(),
              List.of(),
              "Fill completeTask body");
      return Uni.createFrom()
          .item(
              SkillExecutionResult.completed(
                  List.of(
                      SkillArtifact.of(
                          SkillArtifactType.GRAPH_PATCH,
                          "cip-script-generator",
                          new SkillArtifactPayload.GraphPatchPayload(patch))),
                  "filled"));
    }
  }

  private static final class EmptyPatchScriptExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-script-generator";
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.GRAPH_PATCH);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      GraphPatch patch =
          new GraphPatch(
              "empty",
              "cip-script-generator",
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              "No script changes");
      return Uni.createFrom()
          .item(
              SkillExecutionResult.completed(
                  List.of(
                      SkillArtifact.of(
                          SkillArtifactType.GRAPH_PATCH,
                          "cip-script-generator",
                          new SkillArtifactPayload.GraphPatchPayload(patch))),
                  "empty"));
    }
  }

  private static final class FailingNamingExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return NAMING_SKILL;
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.REQUIREMENT_BRIEF);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.NAMING_MANIFEST);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      return Uni.createFrom().item(SkillExecutionResult.failed("naming capture rejected"));
    }
  }
}
