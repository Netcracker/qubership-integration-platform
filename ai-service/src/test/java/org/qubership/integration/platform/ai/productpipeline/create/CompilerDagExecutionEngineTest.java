package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
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

    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    assertTrue(workspace.get(SkillArtifactType.REQUIREMENT_BRIEF).isPresent());
    assertTrue(workspace.get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE).isPresent());
  }

  @Test
  void engineImplementsSharedInterface() {
    assertTrue(engine instanceof CompilerDagExecutionEngine);
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
            List.of(new ArtifactTypeRef("requirement-brief", 1)));
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
                        "externalRoute", "false")))),
        List.of());
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
}
