package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompilerDag;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
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

/** Contract tests for a compiler run seeded from an imported chain rather than an empty plan. */
class SeededCompilerExecutionTest {

  private static final String EDIT_RUN_ID = "edit-run-1";
  private static final String GENERATOR = "cip-service-call-generator";

  private InMemorySkillWorkspaceStore workspaceStore;
  private SkillExecutorRegistry skillRegistry;
  private CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;
  private DefaultCompilerDagExecutionEngine engine;

  @BeforeEach
  void setUp() {
    PlanCompilationTestSupport.memory();
    workspaceStore = new InMemorySkillWorkspaceStore(new ChainPlanStore());
    skillRegistry = mock(SkillExecutorRegistry.class);
    javaAdapterRegistry = mock(CompilerNodeExecutionAdapterRegistry.class);
    QipKnowledgePackRepository packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.activeVersion()).thenReturn(new QipKnowledgePackVersion("v1", "v1"));
    CanonicalGraphDigest digest =
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    engine =
        new DefaultCompilerDagExecutionEngine(
            workspaceStore,
            skillRegistry,
            javaAdapterRegistry,
            packRepository,
            new GraphAssemblyService(digest),
            new CompilerValidationPipeline(
                graph -> new ValidationResult(true, List.of(), "ok"),
                graph -> new ValidationResult(true, List.of(), "ok"),
                graph -> new ValidationResult(true, List.of(), "ok"),
                securityValidator,
                qualityValidator),
            new org.qubership.integration.platform.ai.productpipeline.artifact
                .ProductPipelineArtifactStore(
                new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts(
                    new org.qubership.integration.platform.ai.compiler.artifact
                        .InMemoryArtifactBlobStore(),
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(
                            new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
                    java.time.Clock.systemUTC())));
  }

  @Test
  void editRunStartsFromTheImportedGraphAndRunsOnlyTheGenerationBoundary() {
    CompilerDagExecutionResult result = runEdit();

    assertEquals(StageOutcomeClass.SUCCEEDED, result.outcomeClass());
    assertEquals(
        List.of(GENERATOR, "cip-chain-assembler", "cip-element-validator"),
        result.executedSkillIds());
    assertEquals(
        "op-new",
        result.graph().nodes().get(0).properties().stream()
            .filter(p -> "integrationOperationId".equals(p.key()))
            .findFirst()
            .orElseThrow()
            .value());
  }

  @Test
  void editSeedCarriesTheImportedStructureIdentitiesIntentAndBindings() {
    runEdit();

    SkillWorkspace workspace = workspaceStore.getOrCreate(EDIT_RUN_ID);
    assertEquals(
        importedGraph(),
        workspace
            .get(SkillArtifactType.CHAIN_STRUCTURE)
            .map(a -> ((SkillArtifactPayload.ChainStructurePayload) a.payload()).structure().graph())
            .orElseThrow());
    assertTrue(workspace.get(SkillArtifactType.CHAIN_EDIT_INTENT).isPresent());
    assertTrue(workspace.get(SkillArtifactType.SERVICE_CALL_BINDINGS).isPresent());
    assertTrue(workspace.get(SkillArtifactType.RAW_USER_REQUEST).isPresent());
  }

  @Test
  void editSeedFabricatesNoCreateOnlyArtifacts() {
    CompilerExecutionSeed seed = editSeed();

    Set<SkillArtifactType> seeded =
        seed.artifacts().stream()
            .map(SkillArtifact::type)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(seeded.contains(SkillArtifactType.REQUIREMENT_BRIEF));
    assertFalse(seeded.contains(SkillArtifactType.NAMING_MANIFEST));
    assertFalse(seeded.contains(SkillArtifactType.SELECTED_PATTERN));
    assertFalse(seeded.contains(SkillArtifactType.ELEMENT_SKELETON));
    assertTrue(seed.preSatisfiedSkillIds().contains("cip-structure-generator"));
    assertTrue(seed.preSatisfiedSkillIds().contains("cip-naming-generator"));
  }

  @Test
  void anIsolatedRunCannotObserveWhatAnEarlierRunLeftInItsWorkspace() {
    workspaceStore.putArtifact(
        EDIT_RUN_ID,
        SkillArtifact.of(
            SkillArtifactType.NAMING_MANIFEST,
            "someone-else",
            new SkillArtifactPayload.NamingManifestPayload(
                new NamingManifest(1, "Leaked.Chain", Map.of(), List.of(), List.of()))));

    runEdit();

    assertTrue(
        workspaceStore.getOrCreate(EDIT_RUN_ID).get(SkillArtifactType.NAMING_MANIFEST).isEmpty());
  }

  private CompilerDagExecutionResult runEdit() {
    ResolvedCompilerDag cut = editDag();
    when(skillRegistry.require(GENERATOR)).thenReturn(new RebindExecutor());

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("cip-element-validator")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            EDIT_RUN_ID,
            "conv-edit",
            ChainEditCompilerDag.pinnedManifest(createManifest(), EDIT_RUN_ID, cut),
            null,
            null,
            cut,
            List.of(),
            List.of(),
            editSeed());

    return engine.execute(request, (skillId, status) -> {}).await().indefinitely();
  }

  private static CompilerExecutionSeed editSeed() {
    return CompilerExecutionSeed.forEdit(
        EDIT_RUN_ID,
        "point the order lookup at the new operation",
        importedGraph(),
        null,
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of("call-orders"),
            "use the new operation",
            null,
            List.of()),
        List.of(),
        Set.of());
  }

  private static ChainPlanGraph importedGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("sales", "Sales"),
        List.of(
            new ChainPlanNode(
                "call-orders",
                "service-call",
                "Call orders",
                null,
                null,
                List.of(new PlanProperty("integrationOperationId", "op-old")))),
        List.of());
  }

  private static ResolvedCompilerDag editDag() {
    return ChainEditCompilerDag.cut(
        fullDag(), Set.of(GENERATOR), editSeed().presentArtifactTypes());
  }

  private static ResolvedCompilerDag fullDag() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-structure-generator",
                "Planning",
                null,
                List.of("NAMING_MANIFEST"),
                List.of("CHAIN_STRUCTURE", "CHAIN_PLAN_GRAPH"),
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
                null),
            new ResolvedCompilerNode(
                GENERATOR,
                "Generation",
                null,
                List.of("CHAIN_PLAN_GRAPH", "REQUIREMENT_BRIEF", "RAW_USER_REQUEST"),
                List.of("CHAIN_PLAN_GRAPH", "GRAPH_PATCH"),
                List.of("cip-structure-generator"),
                "captureGraphPatch",
                List.of(),
                List.of(),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null,
                new GraphPatchOwnershipPolicy(
                    false,
                    false,
                    Set.of("service-call"),
                    Set.of(),
                    Map.of("service-call", Set.of("integrationOperationId")))),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of("CHAIN_STRUCTURE", "GRAPH_PATCH_ARTIFACT"),
                List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH"),
                List.of(GENERATOR, "cip-structure-generator"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-element-validator",
                "Validation",
                null,
                List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH", "NAMING_MANIFEST"),
                List.of("PRE_BUILD_VALIDATION", "COMPILER_VALIDATION_BUNDLE"),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                3,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "cip-element-validator")),
        List.of(),
        "full-digest");
  }

  private static RunManifest createManifest() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler-v2",
            "1.0.0",
            "package-digest",
            2,
            "v1",
            "index-digest",
            fullDag(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(new ArtifactTypeRef("chain-plan-graph", 1)),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        "create-run-1",
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "create-chain@2",
        "reference-baseline-v1",
        "reference-baseline-v1",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "artifact", "1.0.0", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "2026.1",
        List.of(),
        pin);
  }

  private static final class RebindExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return GENERATOR;
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
              "patch-1",
              GENERATOR,
              List.of(),
              List.of(),
              List.of(
                  new PropertyPatch(
                      GraphPatchOperation.UPDATE,
                      "call-orders",
                      new PlanProperty("integrationOperationId", "op-new"))),
              List.of(),
              List.of(),
              "rebind");
      return Uni.createFrom()
          .item(
              SkillExecutionResult.completed(
                  List.of(
                      SkillArtifact.of(
                          SkillArtifactType.GRAPH_PATCH,
                          GENERATOR,
                          new SkillArtifactPayload.GraphPatchPayload(patch))),
                  "ok"));
    }
  }
}
