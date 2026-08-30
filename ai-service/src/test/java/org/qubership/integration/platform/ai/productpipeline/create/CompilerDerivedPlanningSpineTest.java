package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.ChainStructureCaptureTool;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.executor.StreamingSkillExecutor;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

class CompilerDerivedPlanningSpineTest {

  private InMemorySkillWorkspaceStore workspaceStore;
  private SkillExecutorRegistry skillRegistry;
  private CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;
  private CreateRunBindingStore bindingStore;
  private QipKnowledgePackRepository packRepository;
  private CompilerDerivedPlanningSpine spine;

  @BeforeEach
  void setUp() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    workspaceStore =
        new InMemorySkillWorkspaceStore(new ChainPlanStore());
    skillRegistry = mock(SkillExecutorRegistry.class);
    javaAdapterRegistry = mock(CompilerNodeExecutionAdapterRegistry.class);
    bindingStore = mock(CreateRunBindingStore.class);
    packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.activeVersion()).thenReturn(new QipKnowledgePackVersion("v1", "v1"));
    CanonicalGraphDigest digest = new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    GraphAssemblyService graphAssemblyService = new GraphAssemblyService(digest);
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerValidationPipeline validationPipeline =
        new CompilerValidationPipeline(
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            securityValidator,
            qualityValidator);
    spine =
        new CompilerDerivedPlanningSpine(
            workspaceStore,
            skillRegistry,
            javaAdapterRegistry,
            bindingStore,
            packRepository,
            graphAssemblyService,
            validationPipeline);
  }

  @Test
  void streamsLlmSkillBeforeHarvestingCapture() {
    String conversationId = "conv-stream-first";
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));

    AtomicBoolean streamed = new AtomicBoolean(false);
    AtomicInteger runAfterStream = new AtomicInteger(0);
    StreamingSkillExecutor patternExecutor =
        new StreamingPatternExecutor(streamed, runAfterStream);
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(patternExecutor);

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
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

    var outcome = spine.execute(request(conversationId)).await().indefinitely();

    assertTrue(streamed.get(), "LLM_SKILL must call runStreaming before harvest");
    assertEquals(1, runAfterStream.get(), "run() must harvest after streaming");
    assertEquals(
        List.of("cip-pattern-selector", "cip-chain-assembler", "cip-structural-validator"),
        outcome.executedSkillIds());
  }

  @Test
  void executesPinnedDagAndStopsAfterMandatoryValidation() {
    String conversationId = "conv-derived";
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    SkillExecutor patternExecutor = new PatternExecutor();
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(patternExecutor);

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
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

    var outcome = spine.execute(request(conversationId)).await().indefinitely();

    assertEquals(
        List.of("cip-pattern-selector", "cip-chain-assembler", "cip-structural-validator"),
        outcome.executedSkillIds());
    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertTrue(workspace.get(SkillArtifactType.REQUIREMENT_BRIEF).isPresent());
    assertTrue(workspace.get(SkillArtifactType.PRE_BUILD_VALIDATION).isPresent());
  }

  @Test
  void v1Characterization_recordsStableOutcomeArtifactsAndMetadata() {
    String conversationId = "conv-v1-characterization";
    ResolvedCompilerDag dag = dagWithMandatoryValidation();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(new PatternExecutor());

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
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

    CanonicalGraphDigest digest =
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    String expectedGraphDigest = digest.sha256(graphForAssembly());

    CompilerPlanningRunner.PlanningSpineOutcome outcome =
        spine.execute(request(conversationId)).await().indefinitely();

    assertEquals(
        List.of("cip-pattern-selector", "cip-chain-assembler", "cip-structural-validator"),
        outcome.executedSkillIds());
    assertEquals("GP-01", outcome.selectedPatternId());
    assertEquals("summary", outcome.selectedPatternSummary());
    assertEquals(graphForAssembly(), outcome.graph());
    assertEquals(expectedGraphDigest, digest.sha256(outcome.graph()));
    assertTrue(outcome.validationResult().valid());
    assertEquals("ok", outcome.validationResult().summary());
    assertEquals(List.of(), outcome.ownerSkills());

    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    GraphAssemblyResult assembly =
        workspace
            .get(SkillArtifactType.GRAPH_ASSEMBLY_RESULT)
            .map(a -> ((SkillArtifactPayload.GraphAssemblyResultPayload) a.payload()).result())
            .orElseThrow();
    assertEquals(1, assembly.schemaVersion());
    assertEquals(expectedGraphDigest, assembly.graphDigest());
    assertEquals(List.of(), assembly.orderedPatchReferences());
    assertEquals(List.of(), assembly.ownershipFacts());
    assertEquals(List.of(), assembly.rejectedPatches());

    CompilerValidationBundle validationBundle =
        workspace
            .get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE)
            .map(a -> ((SkillArtifactPayload.CompilerValidationBundlePayload) a.payload()).bundle())
            .orElseThrow();
    assertEquals(1, validationBundle.schemaVersion());
    assertEquals(expectedGraphDigest, validationBundle.graphDigest());
    assertTrue(validationBundle.approvalEligible());
    assertEquals(
        List.of("cip-structural-validator"),
        validationBundle.passes().stream().map(CompilerValidationPass::validatorSkillId).toList());

    assertTrue(workspace.get(SkillArtifactType.GRAPH_PATCH_ARTIFACT).isEmpty());
    assertEquals(
        "cip-requirement-analyzer",
        workspace.get(SkillArtifactType.REQUIREMENT_BRIEF).orElseThrow().producerSkillId());
    assertEquals(
        "planning-seed",
        workspace.get(SkillArtifactType.RAW_USER_REQUEST).orElseThrow().producerSkillId());
  }

  @Test
  void v1Characterization_structureFailureClassificationIsUnavailable() {
    String conversationId = "conv-v1-characterization-fail";
    ResolvedCompilerDag dag = dagWithStructureThenAssembly();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-structure-generator"))
        .thenReturn(new FailedStructureExecutor());

    PlanningSkillArtifactUnavailableException failure =
        assertThrows(
            PlanningSkillArtifactUnavailableException.class,
            () -> spine.execute(request(conversationId)).await().indefinitely());

    assertEquals("cip-structure-generator", failure.skillId());
    assertEquals(
        Set.of(SkillArtifactType.CHAIN_STRUCTURE.name()), failure.missingArtifactTypes());
    assertTrue(
        failure.getMessage() == null
            || failure.getMessage().contains("cip-structure-generator")
            || failure.getMessage().contains("CHAIN_STRUCTURE"));
    verifyNoInteractions(javaAdapterRegistry);
  }

  @Test
  void doesNotFallbackToLegacyDefaultSkillOrder() {
    String conversationId = "conv-empty";
    ResolvedCompilerDag dag =
        new ResolvedCompilerDag(
            List.of(
                new ResolvedCompilerNode(
                    "cip-requirement-analyzer",
                    "Discovery",
                    null,
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(),
                    null,
                    List.of(),
                    List.of(),
                    true,
                    List.of(),
                    0,
                    0,
                    true,
                    CompilerNodeExecutionMode.PRE_SATISFIED,
                    null)),
            List.of(),
            "empty");
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));

    var outcome = spine.execute(request(conversationId)).await().indefinitely();

    assertTrue(outcome.executedSkillIds().isEmpty());
  }

  @Test
  void persistsAssemblyAndBundleOnlyAfterMandatoryValidatorsComplete() {
    String conversationId = "conv-assembly-bundle";
    ResolvedCompilerDag dag = dagWithAssemblyAndValidationPasses();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-structure-generator")).thenReturn(new StructureExecutor());

    CompilerNodeExecutionAdapter adapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-element-validator")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-structural-validator")).thenReturn(adapter);
    when(adapter.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    spine.execute(request(conversationId)).await().indefinitely();

    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertTrue(workspace.get(SkillArtifactType.GRAPH_ASSEMBLY_RESULT).isPresent());
    assertTrue(workspace.get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE).isPresent());
  }

  @Test
  @SuppressWarnings("java:S5778")
  void doesNotPersistBundleWhenMandatoryValidatorFails() {
    String conversationId = "conv-validator-fail";
    ResolvedCompilerDag dag = dagWithAssemblyAndValidationPasses();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-structure-generator")).thenReturn(new StructureExecutor());

    CompilerNodeExecutionAdapter adapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-element-validator")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-structural-validator")).thenReturn(adapter);
    when(adapter.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()))
        .thenThrow(new IllegalStateException("validator failed"));

    CompilerPlanningRequest planningRequest = request(conversationId);
    IllegalStateException failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> spine.execute(planningRequest).await().indefinitely());
    assertTrue(failure.getMessage().contains("validator failed"));

    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertFalse(workspace.get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE).isPresent());
  }

  @Test
  void continuesWhenMandatoryLlmGeneratorFailsWithContractFailure() {
    String conversationId = "conv-llm-fail-open";
    ResolvedCompilerDag dag = dagWithMandatoryNamingThenAssembly();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-naming-generator")).thenReturn(new FailedNamingExecutor());

    CompilerNodeExecutionAdapter assemblyAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(assemblyAdapter);
    when(assemblyAdapter.execute(eq(node(dag, "cip-chain-assembler")), org.mockito.Mockito.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    CompilerNodeExecutionAdapter validatorAdapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("structural-validation")).thenReturn(validatorAdapter);
    when(validatorAdapter.execute(eq(node(dag, "cip-structural-validator")), org.mockito.Mockito.any()))
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
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.NAMING_MANIFEST,
            "prior-run",
            new SkillArtifactPayload.NamingManifestPayload(
                new org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest(
                    1, "Prior.Internal.Chain", Map.of("http-trigger", "Receive"), List.of(), List.of()))));

    var outcome = spine.execute(request(conversationId)).await().indefinitely();

    assertEquals(
        List.of("cip-naming-generator", "cip-chain-assembler", "cip-structural-validator"),
        outcome.executedSkillIds());
    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertTrue(workspace.get(SkillArtifactType.NAMING_MANIFEST).isPresent());
    assertEquals(
        "Prior.Internal.Chain",
        ((SkillArtifactPayload.NamingManifestPayload)
                workspace.get(SkillArtifactType.NAMING_MANIFEST).orElseThrow().payload())
            .manifest()
            .chainName());
  }

  @Test
  void keepsPlanningAliveWhenFailedStructureSkillHasAcceptedFallbackArtifacts() {
    String conversationId = "conv-structure-fallback";
    ResolvedCompilerDag dag = dagWithStructureThenAssembly();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-structure-generator"))
        .thenReturn(new FailedStructureExecutor());
    CompilerNodeExecutionAdapter assemblyAdapter =
        mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly"))
        .thenReturn(assemblyAdapter);
    when(
        assemblyAdapter.execute(
            eq(node(dag, "cip-chain-assembler")),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              assertTrue(
                  workspaceStore
                      .getOrCreate(conversationId)
                      .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
                      .isPresent());
              return new CompilerNodeExecutionResult(List.of(), List.of());
            });
    ChainStructure priorStructure =
        new ChainStructure(graphForAssembly(), List.of(), List.of());
    workspaceStore.putArtifact(
        conversationId,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            "prior-structure",
            new SkillArtifactPayload.ChainStructurePayload(
                priorStructure)));
    CompilerPlanningRunner.PlanningSpineOutcome outcome =
        spine.execute(request(conversationId)).await().indefinitely();

    assertTrue(
        outcome.executedSkillIds().contains("cip-chain-assembler"));
    assertEquals(
        priorStructure,
        workspaceStore
            .getOrCreate(conversationId)
            .get(SkillArtifactType.CHAIN_STRUCTURE)
            .map(
                artifact ->
                    ((SkillArtifactPayload.ChainStructurePayload) artifact.payload())
                        .structure())
            .orElseThrow());
    assertEquals(
        graphForAssembly(),
        workspaceStore
            .getOrCreate(conversationId)
            .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
            .map(
                artifact ->
                    ((SkillArtifactPayload.ChainPlanGraphPayload) artifact.payload())
                        .graph())
            .orElseThrow());
  }

  @Test
  void stopsBeforeDownstreamWhenFailedStructureSkillHasNoFallbackArtifacts() {
    String conversationId = "conv-structure-missing";
    ResolvedCompilerDag dag = dagWithStructureThenAssembly();
    when(bindingStore.load(conversationId))
        .thenReturn(OptionalBinding.present(bindingFor(conversationId, dag)));
    when(skillRegistry.require("cip-structure-generator"))
        .thenReturn(new FailedStructureExecutor());

    PlanningSkillArtifactUnavailableException failure =
        assertThrows(
            PlanningSkillArtifactUnavailableException.class,
            () -> spine.execute(request(conversationId)).await().indefinitely());

    assertEquals("cip-structure-generator", failure.skillId());
    assertEquals(
        Set.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
        failure.missingArtifactTypes());
    assertFalse(
        workspaceStore
            .getOrCreate(conversationId)
            .get(SkillArtifactType.CHAIN_STRUCTURE)
            .isPresent());
    verifyNoInteractions(javaAdapterRegistry);
  }

  private static CompilerPlanningRequest request(String conversationId) {
    return new CompilerPlanningRequest(
        conversationId,
        "run-1",
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "draft",
            "Create greetings chain",
            List.of()),
        new IdsBypass("skip", "create-chain-v1", "1"),
        "24.4",
        List.of(),
        List.of(),
        null);
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

  private static ResolvedCompilerDag dagWithMandatoryNamingThenAssembly() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-naming-generator",
                "Planning",
                null,
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
                List.of("cip-naming-generator"),
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
        "naming-fail-open");
  }

  private static ResolvedCompilerDag dagWithStructureThenAssembly() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-structure-generator",
                "Planning",
                "GEN-03",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(
                    SkillArtifactType.CHAIN_STRUCTURE.name(),
                    SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of("cip-requirement-analyzer"),
                "captureChainStructure",
                List.of(),
                List.of("always_ready"),
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
                List.of(
                    SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name(),
                    SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of("cip-structure-generator"),
                null,
                List.of(),
                List.of("always_ready"),
                true,
                List.of(),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly")),
        List.of(),
        "structure-fallback-diagnostic");
  }

  private static ResolvedCompilerDag dagWithAssemblyAndValidationPasses() {
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-structure-generator",
                "Planning",
                null,
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of("cip-requirement-analyzer"),
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
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of("cip-structure-generator"),
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
                "cip-element-validator",
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
                "cip-element-validator"),
            new ResolvedCompilerNode(
                "cip-structural-validator",
                "Validation",
                null,
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                List.of("cip-element-validator"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                3,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "cip-structural-validator")),
        List.of(),
        "assembly-validation");
  }

  private static CreateRunBinding bindingFor(String conversationId, ResolvedCompilerDag dag) {
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
    RunManifest manifest =
        new RunManifest(
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
    return new CreateRunBinding(
        conversationId, manifest.runId(), manifest, Instant.now());
  }

  private static ResolvedCompilerNode node(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream().filter(node -> node.skillId().equals(skillId)).findFirst().orElseThrow();
  }

  private static final class FailedNamingExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-naming-generator";
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
      return Uni.createFrom().item(SkillExecutionResult.failed("naming capture contract failure"));
    }
  }

  private static final class FailedStructureExecutor
      implements SkillExecutor {

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
      return Set.of(
          SkillArtifactType.CHAIN_STRUCTURE,
          SkillArtifactType.CHAIN_PLAN_GRAPH);
    }

    @Override
    public Uni<SkillExecutionResult> run(
        SkillRunContext context, SkillWorkspace workspace) {
      return Uni.createFrom().item(
          SkillExecutionResult.failed(
              ChainStructureCaptureTool.CAPTURE_REQUIRED_MESSAGE));
    }
  }

  private static final class PatternExecutor implements SkillExecutor {
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
    public Uni<SkillExecutionResult> run(
        SkillRunContext context, SkillWorkspace workspace) {
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

  private static final class StreamingPatternExecutor implements StreamingSkillExecutor {
    private final AtomicBoolean streamed;
    private final AtomicInteger runAfterStream;

    private StreamingPatternExecutor(AtomicBoolean streamed, AtomicInteger runAfterStream) {
      this.streamed = streamed;
      this.runAfterStream = runAfterStream;
    }

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
    public Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace) {
      streamed.set(true);
      return Multi.createFrom().empty();
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      if (streamed.get()) {
        runAfterStream.incrementAndGet();
      }
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

  private static final class StructureExecutor implements SkillExecutor {
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
    public io.smallrye.mutiny.Uni<SkillExecutionResult> run(
        SkillRunContext context, SkillWorkspace workspace) {
      var graph =
          new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
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
                              "externalRoute", "false")))),
              List.of());
      return io.smallrye.mutiny.Uni.createFrom()
          .item(
              SkillExecutionResult.completed(
                  List.of(
                      SkillArtifact.of(
                          SkillArtifactType.CHAIN_STRUCTURE,
                          "cip-structure-generator",
                          new SkillArtifactPayload.ChainStructurePayload(
                              new ChainStructure(graph, List.of(), List.of())))),
                  "ok"));
    }
  }

  private static final class OptionalBinding {
    private OptionalBinding() {}

    private static java.util.Optional<CreateRunBinding> present(CreateRunBinding binding) {
      return java.util.Optional.of(binding);
    }
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
}
