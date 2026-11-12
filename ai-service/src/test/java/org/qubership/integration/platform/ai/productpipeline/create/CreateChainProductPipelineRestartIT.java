package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.reconcile.ChainReconcileService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
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
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCapability;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationPhase;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.materialization.ProductChainMaterializer;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

class CreateChainProductPipelineRestartIT {

  private static final Instant FIXED = Instant.parse("2026-07-24T12:00:00Z");
  private static final String RUN_ID = "run-create-chain-restart-1";
  private static final String CONVERSATION_ID = "conv-create-chain-restart-1";

  private InMemoryArtifactBlobStore blobStore;
  private ObjectMapper mapper;
  private Clock clock;
  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CatalogMutationGateway catalog;
  private ChainCatalogFactsService factsService;
  private ProductPipelineProfile createChainProfile;
  private ProductPipelineProfile createChainV2Profile;
  private final AtomicInteger chainCreates = new AtomicInteger();
  private final AtomicInteger elementCreates = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    catalog = mock(CatalogMutationGateway.class);
    factsService = mock(ChainCatalogFactsService.class);
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      createChainProfile = ProductPipelineProfileParser.parse(in);
    }
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      createChainV2Profile = ProductPipelineProfileParser.parse(in);
    }
  }

  @Test
  void successfulMaterializationRequiresSuccessfulReadBack() {
    stubCatalogHappyPath();
    PreparedInputs prepared = appendHappyPathInputs();
    var outcome = completed(materializationCapability().execute(materializationContext(prepared))).outcome();

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass(), outcome.message());
    assertTrue(latestReconcileResult().matches());
    assertEquals(MaterializationPhase.COMPLETE, latestMaterializationResult().completedPhase());
    var checkpoint = artifactStore.payload(artifactStore.latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT)
        .orElseThrow(), DesignExecutionCheckpoint.class);
    assertEquals(DesignExecutionPhase.COMPLETE, checkpoint.phase());
    assertEquals(1, chainCreates.get());
    assertEquals(1, elementCreates.get());
  }

  @Test
  void runtimeRegistryContainsCompletedMaterializationCapability() {
    StageCapabilityRegistry registry =
        new StageCapabilityRegistry(List.of(materializationCapability()));
    assertInstanceOf(
        MaterializationCapability.class,
        registry.require(MaterializationCapability.CAPABILITY_ID));
  }

  @Test
  void historicalProductCreateChainBindingResumesAfterRestart() throws Exception {
    RunManifest originalManifest = runManifest();
    String historicalJson =
        """
        {
          "conversationId":"%s",
          "mode":"PRODUCT",
          "productRunId":"%s",
          "runManifest":%s,
          "createdAt":"2026-07-24T12:00:00Z"
        }
        """
            .formatted(
                CONVERSATION_ID,
                originalManifest.runId(),
                mapper.writeValueAsString(originalManifest));
    blobStore.put(
        "product-pipeline-create-bindings/" + CONVERSATION_ID + ".json",
        historicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // Recreate store and selection objects after "restart".
    CreateRunBindingStore restartedStore = new CreateRunBindingStore(blobStore, mapper);
    CompilerRunPinResolver pinResolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(
            pinResolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("must not resolve a new pin on resume"));
    CreateRunSelectionService selection =
        new CreateRunSelectionService(
            "2026.1",
            conversationId -> {
              throw new IllegalStateException("must not create a new binding on resume");
            },
            restartedStore,
            new ProductPipelineProfileCatalog(List.of(createChainProfile)),
            pinResolver,
            clock);

    CreateRunSelectionService.CreateRunSelection resumed =
        selection.selectOrCreate(CONVERSATION_ID);

    assertEquals(originalManifest.runId(), resumed.productRunId());
    assertEquals(originalManifest, resumed.runManifest());
    assertEquals("1", resumed.runManifest().profileVersion());
  }

  @Test
  void historicalProductCreateChainV2BindingResumesAfterRestart() throws Exception {
    RunManifest originalManifest = runManifestV2();
    String historicalJson =
        """
        {
          "conversationId":"%s",
          "mode":"PRODUCT",
          "productRunId":"%s",
          "runManifest":%s,
          "createdAt":"2026-07-24T12:00:00Z"
        }
        """
            .formatted(
                CONVERSATION_ID,
                originalManifest.runId(),
                mapper.writeValueAsString(originalManifest));
    blobStore.put(
        "product-pipeline-create-bindings/" + CONVERSATION_ID + ".json",
        historicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    CreateRunBindingStore restartedStore = new CreateRunBindingStore(blobStore, mapper);
    CompilerRunPinResolver pinResolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(
            pinResolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("must not resolve a new pin on resume"));
    CreateRunSelectionService selection =
        new CreateRunSelectionService(
            "2026.1",
            conversationId -> {
              throw new IllegalStateException("must not create a new binding on resume");
            },
            restartedStore,
            new ProductPipelineProfileCatalog(List.of(createChainProfile, createChainV2Profile)),
            pinResolver,
            clock);

    CreateRunSelectionService.CreateRunSelection resumed =
        selection.selectOrCreate(CONVERSATION_ID);

    assertEquals(originalManifest.runId(), resumed.productRunId());
    assertEquals(originalManifest, resumed.runManifest());
    assertEquals("2", resumed.runManifest().profileVersion());
    assertEquals("create-chain", resumed.runManifest().profileId());
  }

  @Test
  void newCreateBindingSelectsCreateChainV2() {
    CreateRunBindingStore bindingStore = new CreateRunBindingStore(blobStore, mapper);
    CompilerRunPinResolver pinResolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(
            pinResolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin(
                "pkg",
                "1",
                "digest",
                1,
                "idx-1",
                "idx-digest",
                new org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag(
                    List.of(), List.of(), "dag"),
                List.of("planning"),
                Map.of(),
                Map.of("skill", "a".repeat(64)),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null));
    CreateRunSelectionService selection =
        new CreateRunSelectionService(
            "2026.1",
            org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient
                .defaultFixture(),
            bindingStore,
            new ProductPipelineProfileCatalog(List.of(createChainProfile, createChainV2Profile)),
            pinResolver,
            clock);

    CreateRunSelectionService.CreateRunSelection created = selection.selectOrCreate("conv-new-v2");

    assertEquals("2", created.runManifest().profileVersion());
    assertEquals("create-chain", created.runManifest().profileId());
    assertEquals("conv-new-v2-create-chain-2", created.productRunId());
  }

  @Test
  void resumesAfterElementsCheckpointWithoutDuplicateCreates() {
    stubCatalogAfterElements();
    seedElementsCheckpoint();
    PreparedInputs prepared = appendHappyPathInputs();

    MaterializationCapability first = materializationCapability();
    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed(first.execute(materializationContext(prepared))).outcome().outcomeClass());

    MaterializationCapability reconstructed = materializationCapability();
    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed(reconstructed.execute(materializationContext(prepared)))
            .outcome()
            .outcomeClass());

    verify(catalog, never()).resolveOrCreateChain(anyString(), anyString(), any(), any());
    verify(catalog, never()).applyGraph(any(), any(), any());
    assertEquals(0, chainCreates.get());
    assertEquals(0, elementCreates.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = MaterializationPhase.class,
      names = {"ELEMENTS", "READ_BACK", "RECONCILE", "COMPLETE"})
  void everyResumedCompletionPhaseRejectsUnapprovedCatalogExecution(MaterializationPhase phase) {
    seedElementsCheckpoint(phase);
    var expected = matchingFacts();
    var elements = new java.util.ArrayList<>(expected.elements());
    elements.add(new ChainCatalogElement("unapproved-script", "script", "Unapproved", null, Map.of()));
    var dependencies = new java.util.ArrayList<>(expected.dependencies());
    dependencies.add(new ChainCatalogDependency("catalog-script-1", "unapproved-script"));
    when(factsService.load("catalog-chain-1")).thenReturn(new ChainCatalogFacts(expected.chainId(),
        expected.chainName(), expected.chainDescription(), elements.size(), dependencies.size(), "",
        elements, dependencies, expected.lifecycleStatus()));
    var prepared = appendHappyPathInputs();
    var outcome = completed(materializationCapability().execute(materializationContext(prepared))).outcome();
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, outcome.outcomeClass());
    var reconcile = outcome.candidates().stream().filter(candidate -> candidate.kind() == Kind.RECONCILE_RESULT)
        .map(candidate -> (ReconcileResult) candidate.payload()).findFirst().orElseThrow();
    assertTrue(reconcile.chainMismatches().contains("unexpectedElement:unapproved-script"));
    assertTrue(reconcile.chainMismatches().contains("unexpectedDependency:catalog-script-1->unapproved-script"));
    verify(catalog, never()).resolveOrCreateChain(anyString(), anyString(), any(), any());
    verify(catalog, never()).applyGraph(any(), any(), any());
  }

  @Test
  void resumesFromNodeIdOnlyCheckpointAndRebuildsOwnershipOnApply() {
    stubCatalogHappyPath();
    seedChainCheckpointNodeIdsOnly();
    PreparedInputs prepared = appendHappyPathInputs();

    MaterializationCapability reconstructed = materializationCapability();
    CapabilitySignal.Completed completed =
        completed(reconstructed.execute(materializationContext(prepared)));
    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());

    org.mockito.Mockito.verify(catalog).applyGraph(any(), any(), any());
    MaterializationMap owned =
        completed.outcome().candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.MATERIALIZATION_MAP)
            .map(candidate -> (MaterializationMap) candidate.payload())
            .findFirst()
            .orElseThrow();
    assertEquals(
        Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"),
        owned.nodeIdToElementId());
    assertEquals("catalog-trigger-1", owned.semanticEdgeOwnerElementIds().get("edge-1"));
  }

  private MaterializationCapability materializationCapability() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    ChainPlanGraphImporter graphImporter =
        new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper));
    ProductChainMaterializer materializer =
        new ProductChainMaterializer(catalog, artifactStore, factsService, graphImporter);
    return org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationTestWiring.withCopyValidation(
        new MaterializationCapability(
        artifactStore, materializer, factsService, new ChainReconcileService(),
        new CipDesignExecutorJavaAdapter(
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner.class),
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ExecutorCatalogBindingAdapter.class),
            artifactStore,
            mock(org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator.class))),
        new CompilationArtifacts(blobStore, mapper, clock));
  }

  private CreateChainTestOrchestrator runtimeWith(StageCapability materialization) {
    return new CreateChainTestOrchestrator(
        CreateChainTestOrchestrator.supportBuilder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(
                        discovery(),
                        importStage(),
                        UploadedSpecImportPassthrough.capability(),
                        analysis(),
                        planning(),
                        materialization)),
                clock)
            .profileCatalog(new ProductPipelineProfileCatalog(List.of(createChainProfile)))
            .build(),
        runStore);
  }

  private String runToWaitingForImplement(CreateChainTestOrchestrator runtime) {
    runtime
        .startOrResume(
            new StartOrResumeCommand(
                CONVERSATION_ID, RUN_ID, createChainProfile, runManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    Reference draft =
        afterInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow()
            .candidate();
    List<PipelineSignal> afterDraft =
        runtime.approve(new ApproveCommand(RUN_ID, draft, loadRun().run().runRevision())).collect().asList().await().indefinitely();
    Reference plan =
        afterDraft.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow()
            .candidate();
    runtime.approve(new ApproveCommand(RUN_ID, plan, loadRun().run().runRevision())).collect().asList().await().indefinitely();
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    return artifactStore
        .payload(
            artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
                .filter(item -> "2".equals(item.schemaVersion()))
                .reduce((a, b) -> b)
                .orElseThrow(),
            ApprovalRecordV2.class)
        .targetContentHash();
  }

  private void stubCatalogHappyPath() {
    when(catalog.resolveOrCreateChain(anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              chainCreates.incrementAndGet();
              return Uni.createFrom().item("catalog-chain-1");
            });
    when(catalog.applyGraph(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              elementCreates.incrementAndGet();
              ChainPlanGraph desired = invocation.getArgument(1);
              return Uni.createFrom()
                  .item(
                      new CatalogGraphMaterializeResult(
                          new MaterializationMap(
                              "catalog-chain-1",
                              Map.of(
                                  "trigger-1", "catalog-trigger-1",
                                  "script-1", "catalog-script-1"),
                              Map.of("edge-1", "catalog-trigger-1"),
                              Map.of()),
                          List.of("trigger-1", "script-1"),
                          List.of(),
                          null,
                          List.of(),
                          List.of("trigger-1", "script-1"),
                          Map.of(),
                          desired.edges(),
                          List.of(),
                          List.of(),
                          List.of(),
                          false));
            });
    when(factsService.load("catalog-chain-1")).thenReturn(matchingFacts());
  }

  private void stubCatalogAfterElements() {
    when(factsService.load("catalog-chain-1")).thenReturn(matchingFacts());
  }

  private void seedChainCheckpointNodeIdsOnly() {
    MaterializationMap map =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"),
            Map.of(),
            Map.of());
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.MATERIALIZATION_CHECKPOINT,
            "1",
            MaterializationCapability.CAPABILITY_ID,
            "1",
            new MaterializationCheckpoint(
                1, RUN_ID, "catalog-chain-1", MaterializationPhase.CHAIN, map, null, Map.of()),
            List.of(),
            null,
            provenance()));
  }

  private void seedElementsCheckpoint() {
    seedElementsCheckpoint(MaterializationPhase.ELEMENTS);
  }

  private void seedElementsCheckpoint(MaterializationPhase phase) {
    MaterializationMap map =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of());
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.MATERIALIZATION_CHECKPOINT,
            "1",
            MaterializationCapability.CAPABILITY_ID,
            "1",
            new MaterializationCheckpoint(
                1, RUN_ID, "catalog-chain-1", phase, map, null, Map.of()),
            List.of(),
            null,
            provenance()));
  }

  private PreparedInputs appendHappyPathInputs() {
    RequirementBrief brief = new RequirementBrief("Supplied script", List.of(), List.of(), List.of(), List.of(), "Return 200")
        .withFacts(List.of(org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
            .suppliedFact("script-1", "return 200")));
    ChainSemanticRevision semantic = new ChainSemanticRevision(
        "chain-semantic-revision/v1", "semantic", "demo-chain", "1",
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    ChainPlanGraph graph = graph();
    Reference briefRef = append(Kind.REQUIREMENT_BRIEF, "1", brief, List.of());
    Reference semanticRef = append(Kind.CHAIN_SEMANTIC_REVISION, semantic.schemaVersion(), semantic, List.of(briefRef));
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    String orderedPatchDigest = sha256Hex("[]");
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle compilerBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    PlanValidationResult validationResult = new PlanValidationResult(List.of());
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of(semanticRef));
    Reference reportRef =
        append(Kind.DESIGN_PLAN_REPORT, "1", Map.of("markdown", "# plan"), List.of());
    Reference planRef =
        append(Kind.DESIGN_EXECUTION_PLAN, "1", Map.of("flowId", "flow-1"), List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of(semanticRef));
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference orderedPatchesRef =
        append(Kind.ORDERED_GRAPH_PATCHES, "1", new OrderedGraphPatches("1", List.of()), List.of());
    Reference compilerBundleRef =
        append(Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifestV2(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, reportRef, planRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            "1",
            graphDigest,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            true,
            List.of());
    Reference executorValidationRef =
        append(Kind.EXECUTOR_VALIDATION_BUNDLE, "1", executorValidation, List.of());
    ValidatedExecutionBundle bundle =
        new ValidatedExecutionBundle(
            "1",
            approvalRef,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            runManifestRef,
            graphRef,
            graphDigest,
            orderedPatchesRef,
            orderedPatchDigest,
            validationRef,
            validationRef,
            compilerBundleRef,
            executorValidationRef);
    Reference bundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", bundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            approvalRef,
            reportRef,
            planRef,
            graphDigest,
            orderedPatchDigest,
            bundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    PreparedInputs prepared = new PreparedInputs(request, requestRef, bundleRef, runManifestRef, approvalRef);
    appendWaitingCheckpoint(approvalRef, prepared);
    return prepared;
  }

  private void appendWaitingCheckpoint(Reference approvalRef, PreparedInputs prepared) {
    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            "1",
            approvalRef,
            prepared.request().designPlanReportRef().contentHash(),
            prepared.request().designExecutionPlanRef().contentHash(),
            "manifest-hash",
            DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
            List.of(
                new DesignExecutionCheckpoint.CompletedStep(
                    "phase-5",
                    List.of(
                        prepared.request().designPlanReportRef().contentHash(),
                        prepared.request().designExecutionPlanRef().contentHash()),
                    List.of(prepared.bundleRef()),
                    List.of(prepared.bundleRef().contentHash()),
                    new ArtifactProvenance(
                        RUN_ID,
                        "design-execution",
                        "create-chain",
                        "2",
                        "profile-sha",
                        "design-execution",
                        "1",
                        "closure"),
                    "WAITING_FOR_MATERIALIZATION")));
    append(Kind.DESIGN_EXECUTION_CHECKPOINT, "1", checkpoint, List.of());
  }

  private StageExecutionContext materializationContext(PreparedInputs prepared) {
    return new StageExecutionContext(RUN_ID, CONVERSATION_ID, "materialization", RUN_ID,
        "attempt-1", null, runManifestV2(),
        List.of(prepared.requestRef(), prepared.bundleRef(), prepared.runManifestRef()), Map.of());
  }

  private static String sha256Hex(String value) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private ReconcileResult latestReconcileResult() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.RECONCILE_RESULT).orElseThrow(), ReconcileResult.class);
  }

  private MaterializationResult latestMaterializationResult() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT).orElseThrow(),
        MaterializationResult.class);
  }

  private ProductPipelineRunDocument loadRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private Reference append(Kind kind, String schemaVersion, Object payload) {
    return append(kind, schemaVersion, payload, List.of());
  }

  private Reference append(Kind kind, String schemaVersion, Object payload, List<Reference> inputs) {
    return artifactStore
        .append(
            new AppendCommand(
                RUN_ID, kind, schemaVersion, "test-producer", "1", payload, inputs, null, provenance()))
        .reference();
  }

  private ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID, "materialization", "create-chain", "1", "profile-sha", "test", "1", "closure-sha");
  }

  private static CapabilitySignal.Completed completed(Multi<CapabilitySignal> stream) {
    return stream.collect().asList().await().indefinitely().stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "script-1",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return 200")))),
        List.of(new ChainPlanEdge("edge-1", "trigger-1", "script-1", null)));
  }

  private static ChainCatalogFacts matchingFacts() {
    return new ChainCatalogFacts(
        "catalog-chain-1",
        "demo-chain",
        "Demo",
        2,
        1,
        "",
        List.of(
            new ChainCatalogElement("catalog-trigger-1", "http-trigger", "Trigger", null, Map.of()),
            new ChainCatalogElement(
                "catalog-script-1",
                "script",
                "Script",
                null,
                Map.of("script", "return 200"))),
        List.of(new ChainCatalogDependency("catalog-trigger-1", "catalog-script-1")),
        "built_in_catalog");
  }

  private RunManifest runManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        createChainProfile.profileId(),
        createChainProfile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }

  private RunManifest runManifestV2() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        createChainV2Profile.profileId(),
        createChainV2Profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
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

  private static StageCapability discovery() {
    AtomicInteger calls = new AtomicInteger();
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (calls.incrementAndGet() == 1 && context.attributeAsString("userText") == null) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
        }
        RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "draft ready",
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

  private static StageCapability analysis() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementAnalysisCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.REQUIREMENT_BRIEF,
                                new RequirementBrief(
                                    "brief", List.of("fact"), List.of(), List.of(), List.of(), "ok"),
                                List.of())),
                        "analyzed",
                        null)));
      }
    };
  }

  private static StageCapability planning() {
    ChainPlanGraph graph = graph();
    String graphDigest = new CanonicalGraphDigest(new ObjectMapper()).sha256(graph);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle bundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    ImplementationPlan plan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return PlanningCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, plan, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.PLAN_VALIDATION_RESULT,
                                new PlanValidationResult(List.of()),
                                context.inputRefs()),
                            new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.GRAPH_ASSEMBLY_RESULT, assembly, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.COMPILER_VALIDATION_BUNDLE, bundle, context.inputRefs())),
                        "plan ready",
                        null)));
      }
    };
  }

  private record PreparedInputs(
      MaterializationRequest request, Reference requestRef, Reference bundleRef,
      Reference runManifestRef, Reference approvalRef) {}
}
