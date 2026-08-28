package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DefaultChainSemanticIdsRenderer;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolutions;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerReportParser;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignImplementationPlanRenderer;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCapability;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * create-chain@2 shared design runtime lifecycle gate. Shell product E2E pins create-chain@2 for
 * new CREATE runs; create-chain@1 backward-compat coverage is CreateChainProductPipelineRestartIT.
 */
class CreateChainSharedDesignRuntimeIT {

  private static final Instant FIXED = Instant.parse("2026-07-30T14:00:00Z");
  private static final String RUN_ID = "run-shared-design-1";
  private static final String CONVERSATION_ID = "conv-shared-design-1";
  private static final String PINNED_PLANNER_HASH = "pinned-cip-design-planner-hash";
  private static final String VALID_IDS =
      """
      # Integration Design Specification

      ## Integration Process

      ### Integration flow for CIP Chain - Pets

      ```mermaid
      sequenceDiagram
          autonumber
          participant Client as Client
          participant Petstore as Petstore Ext
          Client->>Petstore: GET /pets
      ```
      """;

  private InMemoryArtifactBlobStore blobStore;
  private ObjectMapper mapper;
  private Clock clock;
  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineProfile v2Profile;
  private ProductPipelineProfile v1Profile;

  private CatalogSystemReadTool catalogReadTool;
  private ApiHubMcpTools apiHubMcpTools;
  private CatalogMutationGateway catalogMutationGateway;
  private ApprovedCompilerExecutionRunner approvedCompilerExecutionRunner;
  private StageCapability materializationCapability;

  private final AtomicInteger discoveryCalls = new AtomicInteger();
  private final AtomicInteger analysisCalls = new AtomicInteger();
  private final AtomicInteger plannerCalls = new AtomicInteger();
  private ChainSemanticRevision offeredRevision;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      v2Profile = ProductPipelineProfileParser.parse(in);
    }
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      v1Profile = ProductPipelineProfileParser.parse(in);
    }
    catalogReadTool = mock(CatalogSystemReadTool.class);
    apiHubMcpTools = mock(ApiHubMcpTools.class);
    catalogMutationGateway = mock(CatalogMutationGateway.class);
    approvedCompilerExecutionRunner = mock(ApprovedCompilerExecutionRunner.class);
    // CipDesignExecutorJavaAdapter calls the 5-arg progress overload; stubbing only the
    // 4-arg default leaves the mock returning null and fails design-execution with NPE.
    when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
        .thenReturn(successfulEngineResult());
    materializationCapability = mock(StageCapability.class);
    when(materializationCapability.capabilityId()).thenReturn(MaterializationCapability.CAPABILITY_ID);
    when(materializationCapability.execute(any()))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.SUCCEEDED,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.MATERIALIZATION_RESULT, Map.of("ok", true), List.of()),
                                new ArtifactCandidate(
                                    Kind.CATALOG_CHAIN_SNAPSHOT, sampleFacts(), List.of()),
                                new ArtifactCandidate(
                                    Kind.RECONCILE_RESULT, Map.of("matches", true), List.of()),
                                new ArtifactCandidate(
                                    Kind.DESIGN_EXECUTION_RESULT,
                                    Map.of("outcome", "complete"),
                                    List.of()),
                                new ArtifactCandidate(
                                    Kind.MATERIALIZATION_MAP,
                                    Map.of("chainId", "catalog-chain-1"),
                                    List.of())),
                            "materialized",
                            null))));
    discoveryCalls.set(0);
    analysisCalls.set(0);
    plannerCalls.set(0);
    offeredRevision = petsRevision();
  }

  @Test
  @Disabled("PROVIDED IDS is fail-closed; do not restore it as a success path")
  void provideBypassesRequirementStagesPlansAndExecutesAfterApproval() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(runtime);

    List<PipelineSignal> afterIds =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, VALID_IDS))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL,
        loadRun().run().status(),
        () ->
            "status="
                + loadRun().run().status()
                + " stage="
                + loadRun().run().currentStageId()
                + " signals="
                + afterIds);
    assertEquals(0, discoveryCalls.get());
    assertEquals(0, analysisCalls.get());
    assertTrue(hasKind(Kind.IDS_DOCUMENT));
    assertEquals(IdsDocument.Mode.PROVIDED, latestIds().mode());

    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    assertTrue(hasKind(Kind.DESIGN_PLAN_REPORT));
    assertTrue(hasKind(Kind.DESIGN_EXECUTION_PLAN));
    assertTrue(hasKind(Kind.IMPLEMENTATION_PLAN));

    implementApprovedPlan(runtime);
    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    verify(approvedCompilerExecutionRunner).execute(any(), any(), anyList(), any(), any(), any());
    verify(materializationCapability).execute(any());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void generateRequiresBriefAndIdsApprovalBeforeExecution() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(runtime);

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertTrue(discoveryCalls.get() > 0);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
    assertEquals("requirement-analysis", loadRun().run().currentStageId());

    List<PipelineSignal> afterBriefApprove = approveLatestWaitingReturning(runtime);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
    // design-input has no gate of its own; the brief approval carries the run to the plan gate.
    assertEquals("design-planning", loadRun().run().currentStageId());
    assertTrue(hasKind(Kind.CHAIN_SEMANTIC_REVISION));
    assertEquals(IdsDocument.Mode.DERIVED, latestIds().mode());
    assertFalse(latestIds().markdown().isBlank(), () -> "signals=" + afterBriefApprove);

    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    assertTrue(hasKind(Kind.IDS_DOCUMENT));
    assertTrue(hasKind(Kind.IMPLEMENTATION_PLAN));
    assertEquals(IdsDocument.Mode.DERIVED, latestIds().mode());

    implementApprovedPlan(runtime);
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        loadRun().run().status(),
        () -> "after generate implement: " + runDebug());
    verify(approvedCompilerExecutionRunner).execute(any(), any(), anyList(), any(), any(), any());
    verify(materializationCapability).execute(any());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void generatePathPersistsTwoEntrySharedDownstream() {
    offeredRevision = SemanticFixtures.twoEntrySharedDownstream();
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(runtime);
    runGenerateToMaterialized(runtime, "Create a pets HTTP integration");
    assertPersistedSemanticMatches(offeredRevision);
    assertTrue(hasKind(Kind.CHAIN_PLAN_GRAPH));
    assertTrue(hasKind(Kind.MATERIALIZATION_MAP));
  }

  @Test
  void generatePathPersistsConditionReconvergence() {
    offeredRevision = SemanticFixtures.conditionReconvergence();
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(runtime);
    runGenerateToMaterialized(runtime, "Create a pets HTTP integration");
    assertPersistedSemanticMatches(offeredRevision);
    assertTrue(hasKind(Kind.CHAIN_PLAN_GRAPH));
    assertTrue(hasKind(Kind.MATERIALIZATION_MAP));
  }

  @Test
  void deriveProducesIdsWithoutIdsApprovalWaitThenExecutes() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(deriveCatalogHitStubs());
    startV2(runtime);

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(runtime);

    List<PipelineSignal> afterDerive =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "Derive minimal IDS"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(
        IdsDocument.Mode.DERIVED,
        latestIds().mode(),
        () -> "signals=" + afterDerive + " status=" + loadRun().run().status());
    assertTrue(hasKind(Kind.IDS_DOCUMENT));
    assertTrue(hasKind(Kind.CHAIN_SEMANTIC_REVISION));
    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL,
        loadRun().run().status(),
        () -> "after derive: " + runDebug());
    assertEquals("design-planning", loadRun().run().currentStageId());
    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());

    implementApprovedPlan(runtime);
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        loadRun().run().status(),
        () -> "after derive implement: " + runDebug());
    verify(approvedCompilerExecutionRunner).execute(any(), any(), anyList(), any(), any(), any());
    verify(materializationCapability).execute(any());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void v2DiscoveryAdvancesWithoutDraftApprovalWhileAnalysisStillRequiresBrief() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(runtime);
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertTrue(discoveryCalls.get() > 0);
    assertTrue(analysisCalls.get() > 0);
    assertEquals("requirement-analysis", loadRun().run().currentStageId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
    assertTrue(hasKind(Kind.REQUIREMENT_DRAFT));
    assertTrue(hasKind(Kind.REQUIREMENT_BRIEF));
  }

  @Test
  void omAndSalesforceCallsKeepIndependentCatalogResolutions() {
    CreateChainTestOrchestrator runtime = runtimeWithOmWfmDesignStack();
    startV2(runtime);

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Call Order Management then Salesforce WFM"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(runtime);

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Derive minimal IDS"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals("design-planning", loadRun().run().currentStageId(), () -> runDebug());
    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status(), () -> runDebug());

    implementApprovedPlan(runtime);
    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status(), () -> runDebug());

    CatalogBindingResolutions resolutions =
        artifactStore.payload(
            artifactStore.history(RUN_ID, Kind.CATALOG_BINDING_RESOLUTIONS).stream()
                .reduce((a, b) -> b)
                .orElseThrow(),
            CatalogBindingResolutions.class);
    assertEquals(2, resolutions.resolutions().size(), resolutions.toString());
    CatalogBindingResolution om =
        resolutions.resolutions().stream()
            .filter(binding -> "op-result".equals(binding.integrationOperationId()))
            .findFirst()
            .orElseThrow();
    CatalogBindingResolution wfm =
        resolutions.resolutions().stream()
            .filter(binding -> "op-create".equals(binding.integrationOperationId()))
            .findFirst()
            .orElseThrow();
    assertEquals("sys-om", om.systemId());
    assertEquals("sys-wfm", wfm.systemId());
    assertFalse(om.serviceCallId().equals(wfm.serviceCallId()));
  }

  @Test
  void catalogHitCallOrderSkipsApiHubAndImport() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    seedApprovedImplementationWaiting(runtime);

    implementApprovedPlan(runtime);

    InOrder inOrder =
        inOrder(catalogReadTool, apiHubMcpTools, catalogMutationGateway,
            approvedCompilerExecutionRunner, materializationCapability);
    inOrder.verify(catalogReadTool).searchCatalogSystems(anyString());
    inOrder
        .verify(approvedCompilerExecutionRunner)
        .execute(any(), any(), anyList(), any(), any(), any());
    inOrder.verify(materializationCapability).execute(any());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void catalogMissAtExecutionStopsWithoutSearchingOrImporting() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
        .thenReturn(successfulEngineResult());

    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(null);
    seedApprovedImplementationWaiting(runtime);
    implementApprovedPlan(runtime);

    // API resolution belongs to briefing. A call that reaches execution without a binding is
    // missing input, not a reason to search API Hub and import a specification here.
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
    verify(approvedCompilerExecutionRunner, never())
        .execute(any(), any(), anyList(), any(), any(), any());
    verify(materializationCapability, never()).execute(any());
    assertFalse(hasKind(Kind.DESIGN_EXECUTION_RESULT));
  }

  @Test
  void ambiguousCatalogResultWaitsForInputWithoutApiHub() {
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto("op-a", "findPetsA", "GET", "/pets", "spec-1"),
                new CatalogRestClient.OperationDto("op-b", "findPetsB", "GET", "/pets", "spec-1")));

    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(null);
    seedApprovedImplementationWaiting(runtime);
    implementApprovedPlan(runtime);

    assertEquals(RunStatus.WAITING_FOR_INPUT, loadRun().run().status());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
    verify(approvedCompilerExecutionRunner, never())
        .execute(any(), any(), anyList(), any(), any(), any());
    verify(materializationCapability, never()).execute(any());
  }

  @Test
  void missingMappingIntentDefaultsToPassThroughBeforePlannerInvocation() {
    DesignInputCapability designInput = designInputCapability();
    StageCapability discovery = discoveryStub();
    StageCapability analysis =
        analysisStub(briefMissingMappings());
    DesignPlanningCapability planning = designPlanningCapability();

    CreateChainTestOrchestrator runtime =
        new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(
                    designInput,
                    discovery,
                    analysis,
                    planning,
                    designExecutionCapability(),
                    materializationCapability)),
            new ProductPipelineProfileCatalog(List.of(v2Profile)),
            stubPinResolver(),
            clock), runStore);

    startV2(runtime);
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(runtime);
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, "Generate full IDS"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    // design-input no longer parks at a gate, so both the brief approval and this turn carry the
    // run through to the planner.
    assertEquals(2, plannerCalls.get());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
  }

  @Test
  void refinementOfApprovedCandidateInvalidatesExecution() {
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    seedApprovedImplementationWaiting(runtime);
    long revision = loadRun().run().runRevision();

    assertThrows(
        org.qubership.integration.platform.ai.productpipeline.runtime.StaleApprovalException.class,
        () ->
            runtime
                .implement(new ImplementCommand(RUN_ID, "stale-plan-hash", revision))
                .collect()
                .asList()
                .await()
                .indefinitely());

    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    verify(approvedCompilerExecutionRunner, never())
        .execute(any(), any(), anyList(), any(), any(), any());
  }

  @Test
  void restartResumesFromImplementationApprovalWithoutReplanning() {
    CreateChainTestOrchestrator first = runtimeWithRealDesignStack(catalogHitStubs());
    seedApprovedImplementationWaiting(first);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    long revision = loadRun().run().runRevision();
    plannerCalls.set(0);

    CreateChainTestOrchestrator restarted =
        new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(
                    designInputCapability(),
                    discoveryStub(),
                    analysisStub(approvedBrief()),
                    designPlanningCapability(),
                    designExecutionCapability(),
                    materializationCapability)),
            new ProductPipelineProfileCatalog(List.of(v2Profile)),
            stubPinResolver(),
            clock), runStore);
    restarted
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, v2Profile, runManifestV2()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    assertEquals(revision, loadRun().run().runRevision());
    assertEquals(0, plannerCalls.get());
  }

  @Test
  void restartResumesFromThePlanGateWithoutReplanning() {
    CreateChainTestOrchestrator first = runtimeWithRealDesignStack(catalogHitStubs());
    startV2(first);
    first
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(first);
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
    assertEquals("design-planning", loadRun().run().currentStageId());
    assertEquals(IdsDocument.Mode.DERIVED, latestIds().mode());
    long revision = loadRun().run().runRevision();
    plannerCalls.set(0);

    CreateChainTestOrchestrator restarted = runtimeWithRealDesignStack(null);
    restarted
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, v2Profile, runManifestV2()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, loadRun().run().status());
    assertEquals("design-planning", loadRun().run().currentStageId());
    assertEquals(revision, loadRun().run().runRevision());
    // The restart replays the settled gate instead of replanning.
    assertEquals(0, plannerCalls.get());
  }

  @Test
  void restartResumesFromWaitingForMaterializationWithoutReExecutingGenerators() {
    AtomicInteger materializationCalls = new AtomicInteger();
    materializationCapability =
        new StageCapability() {
          @Override
          public String capabilityId() {
            return MaterializationCapability.CAPABILITY_ID;
          }

          @Override
          public Multi<CapabilitySignal> execute(StageExecutionContext context) {
            int call = materializationCalls.incrementAndGet();
            if (call == 1) {
              return Multi.createFrom()
                  .item(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.NEEDS_INPUT,
                              "materialization paused for restart")));
            }
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.SUCCEEDED,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.MATERIALIZATION_RESULT, Map.of("ok", true), List.of()),
                                new ArtifactCandidate(
                                    Kind.CATALOG_CHAIN_SNAPSHOT, sampleFacts(), List.of()),
                                new ArtifactCandidate(
                                    Kind.RECONCILE_RESULT, Map.of("matches", true), List.of()),
                                new ArtifactCandidate(
                                    Kind.DESIGN_EXECUTION_RESULT,
                                    Map.of("outcome", "complete"),
                                    List.of()),
                                new ArtifactCandidate(
                                    Kind.MATERIALIZATION_MAP,
                                    Map.of("chainId", "catalog-chain-1"),
                                    List.of())),
                            "materialized",
                            null)));
          }
        };

    CreateChainTestOrchestrator first = runtimeWithRealDesignStack(catalogHitStubs());
    seedApprovedImplementationWaiting(first);
    implementApprovedPlan(first);

    assertEquals(RunStatus.WAITING_FOR_INPUT, loadRun().run().status());
    assertEquals("materialization", loadRun().run().currentStageId());
    DesignExecutionCheckpoint checkpoint = latestExecutionCheckpoint();
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, checkpoint.phase());
    verify(approvedCompilerExecutionRunner, times(1))
        .execute(any(), any(), anyList(), any(), any(), any());
    assertEquals(1, materializationCalls.get());

    CreateChainTestOrchestrator restarted = runtimeWithRealDesignStack(null);
    restarted
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, v2Profile, runManifestV2()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, loadRun().run().status());
    assertEquals("materialization", loadRun().run().currentStageId());

    restarted
        .acceptInput(new AcceptInputCommand(RUN_ID, "resume materialization"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    assertEquals(2, materializationCalls.get());
    verify(approvedCompilerExecutionRunner, times(1))
        .execute(any(), any(), anyList(), any(), any(), any());
  }

  @Test
  void validationFailureDoesNotInvokeMaterialization() {
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto("op-1", "findPets", "GET", "/pets", "spec-1")));
    when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
        .thenReturn(
            new CompilerDagExecutionResult(
                StageOutcomeClass.VALIDATION_FAILURE,
                "graph invalid",
                List.of(),
                new PlanningPatchLedger(List.of(), List.of()),
                null,
                null,
                null));

    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(null);
    seedApprovedImplementationWaiting(runtime);
    implementApprovedPlan(runtime);

    // A validation failure is recoverable: the run halts at WAITING_FOR_INPUT so the owner can
    // diagnose and retry. Materialization must not run before that gate clears.
    assertEquals(RunStatus.WAITING_FOR_INPUT, loadRun().run().status());
    verify(materializationCapability, never()).execute(any());
    assertFalse(hasKind(Kind.DESIGN_EXECUTION_RESULT));
  }

  @Test
  void readbackMismatchOmitsDesignExecutionResult() {
    StageCapability failingMaterialization =
        new StageCapability() {
          @Override
          public String capabilityId() {
            return MaterializationCapability.CAPABILITY_ID;
          }

          @Override
          public Multi<CapabilitySignal> execute(StageExecutionContext context) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.VALIDATION_FAILURE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.CATALOG_CHAIN_SNAPSHOT, sampleFacts(), List.of()),
                                new ArtifactCandidate(
                                    Kind.RECONCILE_RESULT,
                                    Map.of("matches", false, "summary", "digest mismatch"),
                                    List.of())),
                            "catalog readback mismatch",
                            null)));
          }
        };
    materializationCapability = failingMaterialization;
    CreateChainTestOrchestrator runtime = runtimeWithRealDesignStack(catalogHitStubs());
    seedApprovedImplementationWaiting(runtime);
    implementApprovedPlan(runtime);

    // The readback mismatch is a recoverable validation failure, so the run halts at
    // WAITING_FOR_INPUT rather than recording a design-execution result.
    assertEquals(RunStatus.WAITING_FOR_INPUT, loadRun().run().status());
    assertFalse(hasKind(Kind.DESIGN_EXECUTION_RESULT));
  }

  @Test
  void persistedV1ProfileKeepsDraftAndPlanApprovalGates() {
    assertNotNull(v1Profile.stages().stream()
        .filter(s -> "requirement-discovery".equals(s.stageId()))
        .findFirst()
        .orElseThrow()
        .approval());
    assertNotNull(v1Profile.stages().stream()
        .filter(s -> "requirement-analysis".equals(s.stageId()))
        .findFirst()
        .orElseThrow()
        .approval());
    assertEquals("1", v1Profile.profileVersion());
    assertTrue(
        v2Profile.stages().stream()
            .filter(s -> "requirement-discovery".equals(s.stageId()))
            .findFirst()
            .orElseThrow()
            .approval()
            == null);
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }

  private Runnable catalogHitStubs() {
    return () -> {
      when(catalogReadTool.searchCatalogSystems(anyString()))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
      when(catalogReadTool.getApiSpecifications("sys-1"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
      when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
          .thenReturn(
              List.of(
                  new CatalogRestClient.OperationDto(
                      "op-1", "findPets", "GET", "/pets", "spec-1")));
      when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
          .thenReturn(successfulEngineResult());
    };
  }

  /** Catalog hit for Petstore Ext GET /pets derive flows. */
  private Runnable deriveCatalogHitStubs() {
    return () -> {
      when(catalogReadTool.searchCatalogSystems(anyString()))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
      when(catalogReadTool.getApiSpecifications("sys-1"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
      when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
          .thenReturn(
              List.of(
                  new CatalogRestClient.OperationDto(
                      "op-1", "findPets", "GET", "/pets", "spec-1")));
      when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
          .thenReturn(successfulEngineResult());
    };
  }

  private CreateChainTestOrchestrator runtimeWithRealDesignStack(Runnable stubs) {
    if (stubs != null) {
      stubs.run();
    }
    return new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
        runStore,
        artifactStore,
        new StageCapabilityRegistry(
            List.of(
                designInputCapability(),
                discoveryStub(List.of()),
                analysisStub(approvedBrief()),
                designPlanningCapability(),
                designExecutionCapability(),
                materializationCapability)),
        new ProductPipelineProfileCatalog(List.of(v2Profile)),
        stubPinResolver(),
        clock), runStore);
  }

  private CreateChainTestOrchestrator runtimeWithOmWfmDesignStack() {
    omWfmCatalogStubs().run();
    return new CreateChainTestOrchestrator(
        new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(
                    designInputCapability(),
                    discoveryStub(List.of(omBindingHint(), wfmBindingHint())),
                    analysisStub(omWfmBrief()),
                    designPlanningCapability(),
                    designExecutionCapability(),
                    materializationCapability)),
            new ProductPipelineProfileCatalog(List.of(v2Profile)),
            stubPinResolver(),
            clock),
        runStore);
  }

  private Runnable omWfmCatalogStubs() {
    return () -> {
      when(catalogReadTool.searchCatalogSystems("Order Management"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto(
                      "sys-om", "Order Management", "EXTERNAL", "http")));
      when(catalogReadTool.searchCatalogSystems("Salesforce WFM"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto(
                      "sys-wfm", "Salesforce WFM", "EXTERNAL", "http")));
      when(catalogReadTool.searchCatalogSystems("sys-om"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto(
                      "sys-om", "Order Management", "EXTERNAL", "http")));
      when(catalogReadTool.searchCatalogSystems("sys-wfm"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SystemDto(
                      "sys-wfm", "Salesforce WFM", "EXTERNAL", "http")));
      when(catalogReadTool.getApiSpecifications("sys-om"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SpecificationDto("spec-om", "2024.4", "sg-om", "sys-om")));
      when(catalogReadTool.getApiSpecifications("sys-wfm"))
          .thenReturn(
              List.of(
                  new CatalogRestClient.SpecificationDto(
                      "spec-wfm", "2024.4", "sg-wfm", "sys-wfm")));
      when(catalogReadTool.listCatalogOperations("spec-om", "sys-om", null))
          .thenReturn(
              List.of(
                  new CatalogRestClient.OperationDto(
                      "op-result", "onTaskResult", "POST", "/tasks/result", "spec-om")));
      when(catalogReadTool.listCatalogOperations("spec-wfm", "sys-wfm", null))
          .thenReturn(
              List.of(
                  new CatalogRestClient.OperationDto(
                      "op-create", "createTask", "POST", "/sobjects/Task", "spec-wfm")));
      when(approvedCompilerExecutionRunner.execute(any(), any(), anyList(), any(), any(), any()))
          .thenReturn(successfulEngineResult());
    };
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPinResolver resolver = mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.doNothing().when(resolver).verifyAvailable(any());
    return resolver;
  }

  private DesignInputCapability designInputCapability() {
    return new DesignInputCapability(
        (conversationId, prompt) -> {
          if (prompt != null && prompt.contains("Order Management")) {
            ProductCapabilityCaptureContext.offerSemantic(omWfmRevision());
          } else {
            ProductCapabilityCaptureContext.offerSemantic(offeredRevision);
          }
          return Multi.createFrom().empty();
        },
        new DefaultChainSemanticIdsRenderer());
  }

  private DesignPlanningCapability designPlanningCapability() {
    return new DesignPlanningCapability(
        (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) -> {
          plannerCalls.incrementAndGet();
          if (offeredRevision != null
              && "revision-two-entry".equals(offeredRevision.revisionId())) {
            return twoEntryPlannerReport();
          }
          if (offeredRevision != null
              && "revision-reconverge".equals(offeredRevision.revisionId())) {
            return reconvergePlannerReport();
          }
          if (input != null
              && (input.contains("Orders API") || input.contains("Order Management"))) {
            return input.contains("Order Management") ? omWfmPlannerReport() : ordersPlannerReport();
          }
          return petsPlannerReport();
        },
        artifactStore);
  }

  private DesignExecutionCapability designExecutionCapability() {
    org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator
        planValidator =
            mock(
                org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator
                    .class);
    when(planValidator.validate(any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CipDesignExecutorJavaAdapter adapter =
        new CipDesignExecutorJavaAdapter(
            approvedCompilerExecutionRunner,
            new DefaultExecutorCatalogBindingAdapter(
                mock(CatalogBindingMatcher.class), catalogReadTool),
            artifactStore,
            planValidator);
    return new DesignExecutionCapability(artifactStore, adapter);
  }

  private StageCapability discoveryStub() {
    return discoveryStub(List.of());
  }

  private StageCapability discoveryStub(List<CatalogBindingHint> hints) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        discoveryCalls.incrementAndGet();
        RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
        StageOutcomeClass outcomeClass =
            RequirementDiscoveryCapability.stageRequiresApproval(context)
                ? StageOutcomeClass.CANDIDATE
                : StageOutcomeClass.SUCCEEDED;
        List<ArtifactCandidate> candidates = new ArrayList<>();
        candidates.add(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of()));
        for (CatalogBindingHint hint : hints) {
          candidates.add(new ArtifactCandidate(Kind.CATALOG_BINDING_HINT, hint, List.of()));
        }
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(outcomeClass, candidates, "discovered", null)));
      }
    };
  }

  private StageCapability analysisStub(RequirementBrief brief) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementAnalysisCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        analysisCalls.incrementAndGet();
        StageOutcomeClass outcomeClass =
            RequirementAnalysisCapability.stageRequiresApproval(context)
                ? StageOutcomeClass.CANDIDATE
                : StageOutcomeClass.SUCCEEDED;
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        outcomeClass,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                        "analyzed",
                        null)));
      }
    };
  }

  private void startV2(CreateChainTestOrchestrator runtime) {
    runtime
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, v2Profile, runManifestV2()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void seedApprovedImplementationWaiting(CreateChainTestOrchestrator runtime) {
    startV2(runtime);
    runGenerateToWaitingForImplement(runtime, "Create a pets HTTP integration");
  }

  private void runGenerateToMaterialized(
      CreateChainTestOrchestrator runtime, String userText) {
    runGenerateToWaitingForImplement(runtime, userText);
    implementApprovedPlan(runtime);
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        loadRun().run().status(),
        () -> "after generate implement: " + runDebug());
  }

  private void runGenerateToWaitingForImplement(
      CreateChainTestOrchestrator runtime, String userText) {
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, userText))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(runtime);
    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status(), () -> runDebug());
  }

  private void assertPersistedSemanticMatches(ChainSemanticRevision expected) {
    ChainSemanticRevision revision = latestRevision();
    assertEquals(expected.revisionId(), revision.revisionId());
    assertEquals(
        expected.nodes().stream().map(SemanticNode::nodeId).toList(),
        revision.nodes().stream().map(SemanticNode::nodeId).toList());
    assertEquals(
        expected.executionEdges().stream().map(SemanticExecutionEdge::edgeId).toList(),
        revision.executionEdges().stream().map(SemanticExecutionEdge::edgeId).toList());
    assertTrue(hasKind(Kind.CHAIN_SEMANTIC_REVISION));
  }

  private ChainSemanticRevision latestRevision() {
    return artifactStore.payload(
        artifactStore.history(RUN_ID, Kind.CHAIN_SEMANTIC_REVISION).stream()
            .reduce((a, b) -> b)
            .orElseThrow(),
        ChainSemanticRevision.class);
  }

  private void approveLatestWaiting(CreateChainTestOrchestrator runtime) {
    approveLatestWaitingReturning(runtime);
  }

  private List<PipelineSignal> approveLatestWaitingReturning(CreateChainTestOrchestrator runtime) {
    Reference candidate =
        loadRun().run().stages().stream()
            .filter(s -> s.approvableReference() != null)
            .reduce((a, b) -> b)
            .orElseThrow()
            .approvableReference();
    return runtime
        .approve(new ApproveCommand(RUN_ID, candidate, loadRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private List<PipelineSignal> implementApprovedPlan(CreateChainTestOrchestrator runtime) {
    ApprovalRecordV2 approval =
        artifactStore.payload(
            artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
                .filter(item -> "2".equals(item.schemaVersion()))
                .reduce((a, b) -> b)
                .orElseThrow(),
            ApprovalRecordV2.class);
    return runtime
        .implement(
            new ImplementCommand(
                RUN_ID, approval.targetContentHash(), loadRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private ProductPipelineRunDocument loadRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private String runDebug() {
    ProductPipelineRunDocument doc = loadRun();
    return "status="
        + doc.run().status()
        + " stage="
        + doc.run().currentStageId()
        + " attempts="
        + doc.attempts()
        + " transitions="
        + doc.transitions();
  }

  private boolean hasKind(Kind kind) {
    return !artifactStore.history(RUN_ID, kind).isEmpty();
  }

  private IdsDocument latestIds() {
    return artifactStore.payload(
        artifactStore.history(RUN_ID, Kind.IDS_DOCUMENT).stream()
            .reduce((a, b) -> b)
            .orElseThrow(),
        IdsDocument.class);
  }

  private static ChainSemanticRevision petsRevision() {
    return SemanticFixtures.linear(
        "Pets",
        "revision-pets",
        "trigger-http",
        "node-call",
        "call-1",
        "GET /pets",
        "Petstore Ext",
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision omWfmRevision() {
    ChainSemanticRevision template = SemanticFixtures.linearOrders();
    return new ChainSemanticRevision(
        template.schemaVersion(),
        "revision-om-wfm",
        "OM to Salesforce WFM",
        template.compilerContractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "trigger-http",
                "node-om",
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation("Order Management", null))),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-om",
                "call-om-result",
                "onTaskResult",
                new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-wfm",
                "call-wfm-create-task",
                "createTask",
                new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            new SemanticExecutionEdge("edge-1", "trigger-http", "node-om", null, null, null),
            new SemanticExecutionEdge("edge-2", "node-om", "node-wfm", null, null, null)),
        List.of(),
        List.of(),
        List.of("Salesforce WFM"),
        List.of(),
        List.of());
  }

  private DesignExecutionCheckpoint latestExecutionCheckpoint() {
    return artifactStore.payload(
        artifactStore.history(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT).stream()
            .reduce((a, b) -> b)
            .orElseThrow(),
        DesignExecutionCheckpoint.class);
  }


  private RunManifest runManifestV2() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        v2Profile.profileId(),
        v2Profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(
            new DependencyClosureEntry(DesignPlanningCapability.CAPABILITY_ID, "1", "c1"),
            new DependencyClosureEntry(DesignExecutionCapability.CAPABILITY_ID, "1", "c1")),
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
        new CompilerRunPin(
            "compiler",
            "1",
            "digest",
            2,
            "1",
            "catalog-hash",
            sampleDag(),
            List.of(DesignPlanningCapability.CAPABILITY_ID),
            Map.of(
                CipDesignPlannerAdapter.SKILL_ID,
                PINNED_PLANNER_HASH,
                "cip-trigger-generator",
                "skill-hash-trigger",
                "cip-service-call-generator",
                "skill-hash-call",
                "cip-structure-generator",
                "skill-hash-structure",
                "cip-chain-assembler",
                "skill-hash-assembler",
                "cip-chain-validator",
                "skill-hash-validator",
                "cip-naming-generator",
                "skill-hash-naming",
                "cip-requirement-analyzer",
                "skill-hash-req",
                "cip-script-generator",
                "skill-hash-script"),
            Map.of(
                CipDesignPlannerAdapter.SKILL_ID,
                "addon-hash",
                "cip-trigger-generator",
                "addon-hash-trigger",
                "cip-service-call-generator",
                "addon-hash-call",
                "cip-structure-generator",
                "addon-hash-structure",
                "cip-chain-assembler",
                "addon-hash-assembler",
                "cip-chain-validator",
                "addon-hash-validator",
                "cip-naming-generator",
                "addon-hash-naming",
                "cip-requirement-analyzer",
                "addon-hash-req",
                "cip-script-generator",
                "addon-hash-script"),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private static ArtifactProvenance provenance(String capabilityId) {
    return new ArtifactProvenance(
        RUN_ID, capabilityId, "create-chain", "2", "profile-sha", capabilityId, "1", "closure");
  }

  private static ChainCatalogFacts sampleFacts() {
    return new ChainCatalogFacts(
        "catalog-chain-1", "Pets", "", 1, 0, "", List.of(), List.of(), "built_in_catalog");
  }

  private static CompilerDagExecutionResult successfulEngineResult() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("pets", "Pets"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", "trigger", null, List.of())),
            List.of());
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "ok",
        List.of("cip-trigger-generator", "cip-service-call-generator"),
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

  private static String twoEntryPlannerReport() {
    return """
        1. Analyze requirements and name chain Two entry shared downstream (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        3. Generate Kafka Trigger element with interface Kafka (cip-trigger-generator)
        4. Generate Script element for shared downstream (cip-script-generator)
        5. Generate execution structure and element ordering (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String reconvergePlannerReport() {
    return """
        1. Analyze requirements and name chain Condition reconverge (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        3. Generate Script element for Initialization (cip-script-generator)
        4. Generate Script element for Response (cip-script-generator)
        5. Generate execution structure and element ordering (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String petsPlannerReport() {
    return """
        1. Analyze requirements and name chain Pets (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Petstore Ext for Petstore Ext in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Petstore Ext for Petstore Ext in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Petstore Ext from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        6. Generate Service Call element for Petstore Ext.GET /pets bound to the retrieved spec (cip-service-call-generator)
        7. Generate Script element for Initialization (cip-script-generator)
        8. Generate Script element for Response (cip-script-generator)
        9. Generate execution structure and element ordering (cip-structure-generator)
        10. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        11. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        12. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String ordersPlannerReport() {
    return """
        1. Analyze requirements and name chain Pets (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Orders API for Orders API in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Orders API for Orders API in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Orders API from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        6. Generate Service Call element for Orders API.statement call-1 bound to the retrieved spec (cip-service-call-generator)
        7. Generate Script element for Initialization (cip-script-generator)
        8. Generate Script element for Response (cip-script-generator)
        9. Generate execution structure and element ordering (cip-structure-generator)
        10. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        11. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        12. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String omWfmPlannerReport() {
    return """
        1. Analyze requirements and name chain OM to Salesforce WFM (cip-requirement-analyzer + cip-naming-generator)
        2. Resolve External integration target Order Management from the retrieved spec (binding for cip-service-call-generator)
        3. Resolve External integration target Salesforce WFM from the retrieved spec (binding for cip-service-call-generator)
        4. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        5. Generate Service Call element for Order Management.onTaskResult bound to the retrieved spec (cip-service-call-generator)
        6. Generate Service Call element for Salesforce WFM.createTask bound to the retrieved spec (cip-service-call-generator)
        7. Generate execution structure and element ordering (cip-structure-generator)
        8. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        9. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static RequirementBrief omWfmBrief() {
    RequirementFact trigger =
        httpTrigger(
            "trigger-1",
            "http-trigger",
            "HTTP POST /tasks",
            "POST",
            "/tasks",
            "");
    RequirementFact omFact =
        serviceCall(
            "fact-om",
            "http-service-call",
            "Call Order Management onTaskResult",
            "Order Management",
            "onTaskResult",
            "call-om-result");
    RequirementFact wfmFact =
        serviceCall(
            "fact-wfm",
            "http-service-call",
            "Call Salesforce WFM createTask",
            "Salesforce WFM",
            "createTask",
            "call-wfm-create-task");
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of("HTTP POST /tasks"),
            List.of(),
            List.of(),
            List.of(),
            "Call OM then Salesforce WFM",
            "draft-1",
            "draft",
            List.of(trigger, omFact, wfmFact),
            List.of())
        .withServiceCalls(
            List.of(
                new RequirementServiceCall(
                    "call-om-result",
                    "fact-om",
                    "Order Management",
                    "onTaskResult",
                    omBindingHint()),
                new RequirementServiceCall(
                    "call-wfm-create-task",
                    "fact-wfm",
                    "Salesforce WFM",
                    "createTask",
                    wfmBindingHint())));
  }

  private static CatalogBindingHint omBindingHint() {
    return new CatalogBindingHint(
        "2",
        "call-om-result",
        "fact-om",
        "onTaskResult",
        "sys-om",
        "sg-om",
        "spec-om",
        "op-result",
        "http",
        "POST",
        "/tasks/result",
        "2024.4",
        FIXED,
        "evidence-om");
  }

  private static CatalogBindingHint wfmBindingHint() {
    return new CatalogBindingHint(
        "2",
        "call-wfm-create-task",
        "fact-wfm",
        "createTask",
        "sys-wfm",
        "sg-wfm",
        "spec-wfm",
        "op-create",
        "http",
        "POST",
        "/sobjects/Task",
        "2024.4",
        FIXED,
        "evidence-wfm");
  }

  private static ResolvedCompilerDag sampleDag() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-requirement-analyzer",
                List.of(SkillArtifactType.RAW_USER_REQUEST.name()),
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(),
                0),
            node(
                "cip-naming-generator",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                List.of("cip-requirement-analyzer"),
                1),
            node(
                "cip-trigger-generator",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(),
                2),
            node(
                "cip-service-call-generator",
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of("cip-trigger-generator"),
                3),
            node(
                "cip-script-generator",
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of("cip-service-call-generator"),
                4),
            node(
                "cip-structure-generator",
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(
                    "cip-trigger-generator",
                    "cip-service-call-generator",
                    "cip-script-generator"),
                5),
            node(
                "cip-chain-assembler",
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of("cip-structure-generator"),
                6),
            node(
                "cip-chain-validator",
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                List.of("cip-chain-assembler"),
                7)),
        List.of(),
        "dag-digest");
  }

  private static ResolvedCompilerNode node(
      String skillId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      int ordinal) {
    return new ResolvedCompilerNode(
        skillId,
        "Generation",
        null,
        consumes,
        produces,
        dependsOn,
        "capture",
        List.of(),
        List.of(),
        true,
        List.of(),
        ordinal,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }

  private static RequirementBrief approvedBrief() {
    return new RequirementBrief(
        "Pets",
        List.of("HTTP GET /pets"),
        List.of(),
        List.of(),
        List.of(),
        "List pets",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "trigger-1",
                "http-trigger",
                "HTTP GET /pets findPets",
                "GET",
                "/pets",
                "findPets"),
            serviceCall(
                "call-1",
                "http-service-call",
                "List pets from Petstore Ext",
                "Petstore Ext",
                "GET /pets")),
        List.of(
            mapping(
                "map-init",
                RequirementDataMapping.Stage.INITIALIZATION,
                "trigger-1",
                "call-1",
                RequirementDataMapping.Mode.PASS_THROUGH),
            mapping(
                "map-response",
                RequirementDataMapping.Stage.RESPONSE,
                "call-1",
                "trigger-1",
                RequirementDataMapping.Mode.PASS_THROUGH)));
  }

  private static RequirementBrief briefMissingMappings() {
    return new RequirementBrief(
        "Pets",
        List.of("HTTP GET /pets"),
        List.of(),
        List.of(),
        List.of(),
        "List pets",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "trigger-1",
                "http-trigger",
                "HTTP GET /pets findPets",
                "GET",
                "/pets",
                "findPets"),
            serviceCall(
                "call-1",
                "http-service-call",
                "List pets from Petstore Ext",
                "Petstore Ext",
                "GET /pets")),
        List.of());
  }

  private static RequirementFact fact(
      String id, RequirementFactKind kind, String capabilityKey) {
    return fact(id, kind, capabilityKey, "statement " + id);
  }

  private static RequirementFact fact(
      String id, RequirementFactKind kind, String capabilityKey, String text) {
    return new RequirementFact(
        id, RequirementFactPolarity.POSITIVE, kind, capabilityKey, text);
  }

  private static RequirementFact httpTrigger(
      String id,
      String capabilityKey,
      String text,
      String httpMethod,
      String path,
      String operation) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        capabilityKey,
        text,
        "",
        operation,
        "",
        httpMethod,
        path);
  }

  private static RequirementFact serviceCall(
      String id, String capabilityKey, String text, String participant, String operation) {
    return serviceCall(id, capabilityKey, text, participant, operation, id);
  }

  private static RequirementFact serviceCall(
      String id,
      String capabilityKey,
      String text,
      String participant,
      String operation,
      String serviceCallId) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        capabilityKey,
        text,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static RequirementDataMapping mapping(
      String id,
      RequirementDataMapping.Stage stage,
      String from,
      String to,
      RequirementDataMapping.Mode mode) {
    return new RequirementDataMapping(id, stage, from, to, mode, List.of(), List.of(id));
  }

  private static String singleApiHubHitJson() {
    return """
        {"operations":[{"operationId":"findPets","packageId":"pkg.petstore","version":"2024.4","name":"findPets","method":"GET","path":"/pets","apiType":"rest"}]}
        """;
  }
}
