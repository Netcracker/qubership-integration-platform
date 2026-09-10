package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.mapping.MappingContractBlockedException;
import org.qubership.integration.platform.ai.plan.mapping.MappingGenerationPipeline;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionInputs;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

class MappingContractProductionTransportTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T12:30:00Z");
  private static final String RUN_ID = "run-mapping-transport-1";
  private static final String CONVERSATION_ID = "conv-mapping-transport-1";
  private static final String CATALOG_HASH = "catalog-hash";
  private static final String SKILL_HASH = "skill-hash-script";
  private static final String ADDON_HASH = "addon-hash-script";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private CompilationArtifacts artifacts;
  private ProductPipelineArtifactStore artifactStore;
  private DefaultCompilerDagExecutionEngine engine;
  private CipDesignExecutorJavaAdapter adapter;
  private Revision storedBrief;
  private CompilerDagExecutionRequest engineRequest;
  private ExecutionInputs adapterInputs;

  @BeforeEach
  void setUp() throws Exception {
    PlanCompilationTestSupport.memory();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    artifacts =
        new CompilationArtifacts(new InMemoryArtifactBlobStore(), MAPPER, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    InMemorySkillWorkspaceStore workspaceStore =
        new InMemorySkillWorkspaceStore(new ChainPlanStore());
    SkillExecutorRegistry skillRegistry = mock(SkillExecutorRegistry.class);
    when(skillRegistry.require("cip-script-generator")).thenReturn(new MustNotRunScriptExecutor());
    QipKnowledgePackRepository packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.activeVersion()).thenReturn(new QipKnowledgePackVersion("v1", "v1"));
    CanonicalGraphDigest digest = new CanonicalGraphDigest(MAPPER);
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(any())).thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(any(), any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerValidationPipeline validationPipeline =
        new CompilerValidationPipeline(
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            securityValidator,
            qualityValidator);
    MappingGenerationPipeline pipeline =
        new MappingGenerationPipeline(artifacts, MAPPER, contextBuilder());
    engine =
        new DefaultCompilerDagExecutionEngine(
            workspaceStore,
            skillRegistry,
            mock(CompilerNodeExecutionAdapterRegistry.class),
            packRepository,
            new GraphAssemblyService(digest),
            validationPipeline,
            artifactStore,
            pipeline);

    MappingIntent intent = unknownTargetIntent();
    RequirementBrief brief =
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary")
            .withMappingIntents(List.of(intent));
    storedBrief = appendRunArtifact(Kind.REQUIREMENT_BRIEF, "1", brief);
    persistSide("trigger-http", MappingPort.OUTPUT, sourceSchema());
    persistSide("call-1", MappingPort.REQUEST, targetSchema());

    ChainSemanticRevision revision = revisionWith(intent);
    ChainPlanGraph graph = sampleGraph();
    ResolvedServiceCallBinding binding = sampleBinding();
    ResolvedCompilerDag dag = scriptGeneratorDag();
    RunManifest manifest = emptySourceReferencesManifest(dag);
    engineRequest =
        new CompilerDagExecutionRequest(
            RUN_ID,
            CONVERSATION_ID,
            manifest,
            brief,
            revision,
            dag,
            List.of("cip-script-generator"),
            List.of(),
            CompilerExecutionSeed.forCreate(
                CONVERSATION_ID, brief, revision, graph, List.of(binding)));

    ApprovedCompilerExecutionRunner engineRunner =
        new ApprovedCompilerExecutionRunner() {
          @Override
          public CompilerDagExecutionResult execute(
              DesignExecutionPlan approvedPlan,
              ChainSemanticRevision ignoredRevision,
              List<ResolvedServiceCallBinding> ignoredBindings,
              RunManifest runManifest,
              String attemptId,
              StageRepairEvidence repairEvidence,
              ChainPlanGraph priorGraph,
              BiConsumer<String, String> skillProgress) {
            return engine.execute(engineRequest, skillProgress).await().indefinitely();
          }
        };
    ExecutorCatalogBindingAdapter bindingAdapter = mock(ExecutorCatalogBindingAdapter.class);
    when(bindingAdapter.resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any()))
        .thenReturn(List.of(new BindingResolutionResult.Resolved(binding)));
    CompilerPlanValidator planValidator = mock(CompilerPlanValidator.class);
    when(planValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    adapter =
        new CipDesignExecutorJavaAdapter(
            engineRunner, bindingAdapter, artifactStore, planValidator);
    adapterInputs = adapterInputs(revision, manifest);
  }

  @Test
  void productionManifestFillsConsumedBriefIdentityOnFindings() {
    assertTrue(engineRequest.runManifest().sourceReferences().isEmpty());
    MappingContractBlockedException blocked =
        assertThrows(
            MappingContractBlockedException.class,
            () -> engine.execute(engineRequest, (skillId, status) -> {}).await().indefinitely());
    PlanValidationFinding finding =
        blocked.findings().stream()
            .filter(candidate -> "MAPPING_UNKNOWN_TARGET".equals(candidate.code()))
            .findFirst()
            .orElseThrow();
    assertEquals("$.preserved.executionId", finding.mappingDetails().targetPath());
    assertEquals(storedBrief.artifactId(), finding.mappingDetails().consumedBriefArtifactId());
    assertEquals(storedBrief.contentHash(), finding.mappingDetails().consumedBriefContentHash());
    assertFalse(finding.mappingDetails().consumedBriefArtifactId().isBlank());
  }

  @Test
  void findingsTravelPipelineEngineAndAdapterAsMappingContract() {
    ExecutionResult result = adapter.executeAfterApproval(adapterInputs);

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, result.outcomeClass());
    assertEquals(RecoveryCauseCode.MAPPING_CONTRACT, result.recoveryCause().causeCode());
    assertNotEquals(RecoveryCauseCode.MISSING_BRIEF_FACTS, result.recoveryCause().causeCode());
    PlanValidationFinding finding = result.recoveryCause().findings().getFirst();
    assertEquals("MAPPING_UNKNOWN_TARGET", finding.code());
    assertEquals("$.preserved.executionId", finding.mappingDetails().targetPath());
    assertEquals(storedBrief.artifactId(), finding.mappingDetails().consumedBriefArtifactId());
    assertEquals(storedBrief.contentHash(), finding.mappingDetails().consumedBriefContentHash());
  }

  private ExecutionInputs adapterInputs(ChainSemanticRevision revision, RunManifest manifest) {
    DesignPlanReport report = new DesignPlanReport("1", "# plan\n");
    DesignExecutionPlan plan = samplePlan();
    IdsDocument ids =
        new IdsDocument(
            "1",
            IdsDocument.Mode.PROVIDED,
            "brief-1",
            "brief-hash",
            "flow-hash",
            "renderer-1",
            "# IDS\n");
    Reference idsRef = appendRunArtifact(Kind.IDS_DOCUMENT, "1", ids).reference();
    Reference revisionRef =
        appendRunArtifact(
                Kind.CHAIN_SEMANTIC_REVISION, ChainSemanticRevision.CURRENT_SCHEMA_VERSION, revision)
            .reference();
    Reference reportRef = appendRunArtifact(Kind.DESIGN_PLAN_REPORT, "1", report).reference();
    Reference planRef = appendRunArtifact(Kind.DESIGN_EXECUTION_PLAN, "1", plan).reference();
    Reference implementationRef =
        appendRunArtifact(Kind.IMPLEMENTATION_PLAN, "1", new ImplementationPlan("plan text"))
            .reference();
    Reference manifestRef = appendRunArtifact(Kind.RUN_MANIFEST, "1", manifest).reference();
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationRef,
            implementationRef.contentHash(),
            List.of(idsRef, revisionRef, reportRef, planRef, implementationRef),
            "tester",
            "approved",
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = appendRunArtifact(Kind.APPROVAL_RECORD, "2", approval).reference();
    return new ExecutionInputs(
        RUN_ID,
        CONVERSATION_ID,
        approvalRef,
        approval,
        report,
        reportRef,
        plan,
        planRef,
        revision,
        revisionRef,
        ids,
        idsRef,
        new ImplementationPlan("plan text"),
        implementationRef,
        manifest,
        manifestRef,
        List.of(),
        null,
        null,
        null);
  }

  private Revision appendRunArtifact(Kind kind, String schemaVersion, Object payload) {
    return artifactStore.append(
        new AppendCommand(
            RUN_ID,
            kind,
            schemaVersion,
            "test-producer",
            "1",
            payload,
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
                "closure")));
  }

  private void persistSide(String serviceCallId, MappingPort direction, JsonNode schema) {
    artifacts.append(
        new AppendCommand(
            CONVERSATION_ID,
            Kind.MAPPING_SCHEMA_SIDE,
            "1",
            "test",
            "1",
            new MappingSchemaSide(
                "1",
                serviceCallId,
                "op-1",
                direction,
                "application/json",
                null,
                "sha-test",
                "test-provenance",
                schema),
            List.of(),
            null));
  }

  private static JsonNode sourceSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": {
            "executionId": { "type": "string" },
            "subject": { "type": "string" }
          }
        }
        """);
  }

  private static JsonNode targetSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": { "Subject": { "type": "string" } },
          "required": ["Subject"]
        }
        """);
  }

  private static MappingIntent unknownTargetIntent() {
    return new MappingIntent(
        "salesforce-create-task",
        "trigger-http",
        MappingPort.OUTPUT,
        "node-call",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule("$.subject", "$.Subject", null, MappingRuleStatus.PROPOSED),
            new MappingIntentRule(
                "$.executionId",
                "$.preserved.executionId",
                "preserve for response",
                MappingRuleStatus.PROPOSED)));
  }

  private static ChainSemanticRevision revisionWith(MappingIntent intent) {
    return SemanticFixtures.linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(intent),
        List.of());
  }

  private static ChainPlanGraph sampleGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("id", "Orders"),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "Trigger", null, 1, List.of()),
            new ChainPlanNode("node-call", "service-call", "Call", null, 2, List.of())),
        List.of());
  }

  private static ResolvedServiceCallBinding sampleBinding() {
    return new ResolvedServiceCallBinding(
        "node-call",
        "call-1",
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-1",
        "http",
        "POST",
        "/orders",
        "createOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-call-1",
        "");
  }

  private static ResolvedCompilerDag scriptGeneratorDag() {
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
                scriptOwnership)),
        List.of(),
        "dag-script");
  }

  private static RunManifest emptySourceReferencesManifest(ResolvedCompilerDag dag) {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler",
            "1",
            "pkg-digest",
            1,
            "1",
            CATALOG_HASH,
            dag,
            List.of("cip-script-generator"),
            Map.of("cip-script-generator", SKILL_HASH),
            Map.of("cip-script-generator", ADDON_HASH),
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

  private static DesignExecutionPlan samplePlan() {
    return new DesignExecutionPlan(
        "1",
        "revision-orders",
        "cip-design-planner",
        "chain-semantic-revision/revision-orders",
        "design-input-hash",
        "2024.4",
        ApprovalPolicy.CATALOG_FIRST_V1,
        List.of(
            new DesignExecutionPlan.Step(
                "step-1-cip-script-generator",
                1,
                "Step 1",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-script-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-content-hash",
        Map.of("cip-script-generator", SKILL_HASH),
        Map.of("cip-script-generator", ADDON_HASH),
        CATALOG_HASH,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH);
  }

  private static CompilerSkillContextBuilder contextBuilder() {
    QipKnowledgePackRepository repository = mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(any())).thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(new CompilerGeneratorSpecIndex(List.of()));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));
    return new CompilerSkillContextBuilder(
        MAPPER,
        repository,
        addonRepository,
        mock(CompilerSkillRuntimeEligibility.class),
        mock(KnowledgeClient.class),
        mock(KnowledgeContextProvider.class));
  }

  private static final class MustNotRunScriptExecutor implements SkillExecutor {
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
      throw new AssertionError("mapping contract must block before the script generator runs");
    }
  }
}
