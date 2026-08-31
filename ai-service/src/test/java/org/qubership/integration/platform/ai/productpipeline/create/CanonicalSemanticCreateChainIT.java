package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.reconcile.ChainReconcileService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DefaultChainSemanticIdsRenderer;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCapability;
import org.qubership.integration.platform.ai.productpipeline.materialization.ProductChainMaterializer;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * In-memory create-chain@2 E2E for the canonical semantic artifact, including cold restart over
 * persisted blob state with no conversation catalog cache.
 */
class CanonicalSemanticCreateChainIT {

  private static final Instant FIXED = Instant.parse("2026-08-28T12:00:00Z");
  private static final String RUN_ID = "run-canonical-semantic-1";
  private static final String CONVERSATION_ID = "conv-canonical-semantic-1";
  private static final String PINNED_PLANNER_HASH = "pinned-cip-design-planner-hash";
  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private InMemoryArtifactBlobStore blobStore;
  private ObjectMapper mapper;
  private Clock clock;
  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineProfile v2Profile;
  private CatalogSystemReadTool catalogReadTool;
  private ApprovedCompilerExecutionRunner approvedCompilerExecutionRunner;
  private CatalogMutationGateway catalog;
  private ChainCatalogFactsService factsService;
  private CipDesignExecutorJavaAdapter designExecutorAdapter;
  private DefaultChainSemanticGraphCompiler graphCompiler;
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
    catalogReadTool = mock(CatalogSystemReadTool.class);
    approvedCompilerExecutionRunner = mock(ApprovedCompilerExecutionRunner.class);
    catalog = mock(CatalogMutationGateway.class);
    factsService = mock(ChainCatalogFactsService.class);
    graphCompiler =
        new DefaultChainSemanticGraphCompiler(
            new DefaultChainSemanticRevisionValidator(),
            DeterministicElementSchemaService.createForUnitTests(new ObjectMapper()));
    stubCatalogHits();
    stubCompilerAndCatalog();
    offeredRevision =
        SemanticFixtures.linear(
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

  @Test
  void generatePathPersistsSemanticGraphAndMaterializationMap() {
    CreateChainTestOrchestrator runtime = runtime();
    startV2(runtime);
    runGenerateToMaterialized(runtime);

    List<Kind> completedKinds =
        List.of(
                Kind.CHAIN_SEMANTIC_REVISION,
                Kind.CHAIN_PLAN_GRAPH,
                Kind.MATERIALIZATION_MAP)
            .stream()
            .filter(kind -> !artifactStore.history(RUN_ID, kind).isEmpty())
            .toList();
    assertTrue(
        completedKinds.containsAll(
            List.of(
                Kind.CHAIN_SEMANTIC_REVISION,
                Kind.CHAIN_PLAN_GRAPH,
                Kind.MATERIALIZATION_MAP)),
        () -> "kinds=" + historyKinds());

    ChainSemanticRevision revision = latestRevision();
    ChainPlanGraph graph = latestGraph();
    MaterializationMap map = latestMap();
    assertSemanticGraphAndMapAligned(revision, graph, map);
  }

  @Test
  void generatePathPersistsTwoEntrySharedDownstream() {
    offeredRevision = SemanticFixtures.twoEntrySharedDownstream();
    CreateChainTestOrchestrator runtime = runtime();
    startV2(runtime);
    runGenerateToMaterialized(runtime);
    ChainSemanticRevision revision = latestRevision();
    assertEquals(offeredRevision.revisionId(), revision.revisionId());
    assertSemanticGraphAndMapAligned(revision, latestGraph(), latestMap());
  }

  @Test
  void generatePathPersistsConditionReconvergence() {
    offeredRevision = SemanticFixtures.conditionReconvergence();
    CreateChainTestOrchestrator runtime = runtime();
    startV2(runtime);
    runGenerateToMaterialized(runtime);
    ChainSemanticRevision revision = latestRevision();
    assertEquals(offeredRevision.revisionId(), revision.revisionId());
    assertSemanticGraphAndMapAligned(revision, latestGraph(), latestMap());
  }

  @Test
  void coldRestartResumesWithSameRevisionIdAndDigest() {
    CreateChainTestOrchestrator first = runtime();
    startV2(first);
    first
        .acceptInput(new AcceptInputCommand(RUN_ID, "Create a pets HTTP integration"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(first);
    approveLatestWaiting(first);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());

    ChainSemanticRevision before = latestRevision();
    String digest = CanonicalPayloadHash.sha256Hex(before);
    ApprovalRecordV2 semanticApproval = latestSemanticApproval();
    assertEquals(before.revisionId(), semanticApproval.subjectRevisionId());
    assertEquals(digest, semanticApproval.subjectSha256());
    CompilerRunPin pin = latestManifest().compilerRunPin();
    assertEquals(before.revisionId(), pin.subjectRevisionId());
    assertEquals(digest, pin.subjectSha256());
    assertEquals(CONTRACT.contractVersion(), pin.compilerContractVersion());
    assertEquals(CONTRACT.sha256(), pin.compilerContractSha256());

    CreateChainTestOrchestrator restarted = reconstructedRuntime();
    restarted
        .startOrResume(
            new StartOrResumeCommand(CONVERSATION_ID, RUN_ID, v2Profile, runManifestV2()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());

    ChainSemanticRevision afterResume = latestRevision();
    assertEquals(before.revisionId(), afterResume.revisionId());
    assertEquals(digest, CanonicalPayloadHash.sha256Hex(afterResume));

    implementApprovedPlan(restarted);
    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    ChainSemanticRevision afterImplement = latestRevision();
    assertEquals(before.revisionId(), afterImplement.revisionId());
    assertEquals(digest, CanonicalPayloadHash.sha256Hex(afterImplement));
    assertSemanticGraphAndMapAligned(afterImplement, latestGraph(), latestMap());
  }

  private void runGenerateToMaterialized(CreateChainTestOrchestrator runtime) {
    runGenerateToMaterialized(runtime, "Create a pets HTTP integration");
  }

  private void runGenerateToMaterialized(
      CreateChainTestOrchestrator runtime, String userText) {
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, userText))
        .collect()
        .asList()
        .await()
        .indefinitely();
    approveLatestWaiting(runtime);
    approveLatestWaiting(runtime);
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    implementApprovedPlan(runtime);
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        loadRun().run().status(),
        this::runDebug);
  }

  private String runDebug() {
    var doc = loadRun();
    return "status="
        + doc.run().status()
        + " stage="
        + doc.run().currentStageId()
        + " kinds="
        + historyKinds()
        + " attempts="
        + doc.attempts()
        + " transitions="
        + doc.transitions();
  }

  private void assertSemanticGraphAndMapAligned(
      ChainSemanticRevision revision, ChainPlanGraph graph, MaterializationMap map) {
    Set<String> semanticNodeIds =
        revision.nodes().stream().map(SemanticNode::nodeId).collect(Collectors.toSet());
    Set<String> graphNodeIds =
        graph.nodes().stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet());
    assertEquals(semanticNodeIds, graphNodeIds);
    assertEquals(semanticNodeIds, map.nodeIdToElementId().keySet());

    Set<String> semanticEdgeIds =
        revision.executionEdges().stream()
            .map(edge -> edge.edgeId())
            .collect(Collectors.toSet());
    Set<String> graphEdgeIds =
        graph.edges().stream().map(ChainPlanEdge::edgeId).collect(Collectors.toSet());
    assertEquals(semanticEdgeIds, graphEdgeIds);
    assertEquals(semanticEdgeIds, map.semanticEdgeOwnerElementIds().keySet());

    for (SemanticNode node : revision.nodes()) {
      if (node instanceof SemanticNode.ServiceCall call) {
        ChainPlanNode graphNode =
            graph.nodes().stream()
                .filter(item -> call.nodeId().equals(item.nodeId()))
                .findFirst()
                .orElseThrow();
        assertEquals(call.serviceCallId(), graphNode.serviceCallId().orElse(null));
      }
    }
  }

  private CreateChainTestOrchestrator runtime() {
    return new CreateChainTestOrchestrator(runSupport(), runStore);
  }

  private CreateChainTestOrchestrator reconstructedRuntime() {
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    ProductPipelineRunStore reconstructedRuns =
        new ProductPipelineRunStore(blobStore, mapper, clock);
    ProductPipelineArtifactStore reconstructedArtifacts =
        new ProductPipelineArtifactStore(artifacts);
    runStore = reconstructedRuns;
    artifactStore = reconstructedArtifacts;
    return new CreateChainTestOrchestrator(
        runSupport(reconstructedRuns, reconstructedArtifacts), reconstructedRuns);
  }

  private ProductPipelineRunSupport runSupport() {
    return runSupport(runStore, artifactStore);
  }

  private ProductPipelineRunSupport runSupport(
      ProductPipelineRunStore store, ProductPipelineArtifactStore artifacts) {
    CompilerRunPinResolver pinResolver = mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.doNothing().when(pinResolver).verifyAvailable(any());
    org.mockito.Mockito.doNothing()
        .when(pinResolver)
        .verifyPersistedPin(any(), any(ChainSemanticRevision.class));
    return new ProductPipelineRunSupport(
        store,
        artifacts,
        new StageCapabilityRegistry(
            List.of(
                designInputCapability(),
                discoveryStub(),
                UploadedSpecImportPassthrough.capability(),
                analysisStub(),
                designPlanningCapability(artifacts),
                designExecutionCapability(artifacts),
                materializationCapability(artifacts))),
        new ProductPipelineProfileCatalog(List.of(v2Profile)),
        pinResolver,
        clock);
  }

  private StageCapability materializationCapability(ProductPipelineArtifactStore artifacts) {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    ChainPlanGraphImporter graphImporter =
        new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper));
    ProductChainMaterializer materializer =
        new ProductChainMaterializer(catalog, artifacts, factsService, graphImporter);
    return new MaterializationCapability(
        artifacts,
        materializer,
        factsService,
        new ChainReconcileService(),
        new CanonicalGraphDigest(mapper),
        designExecutorAdapter);
  }

  private void stubCatalogHits() {
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations(CONVERSATION_ID, "spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-1", "findPets", "GET", "/pets", "spec-1")));
  }

  private void stubCompilerAndCatalog() {
    org.mockito.stubbing.Answer<CompilerDagExecutionResult> compile =
        invocation -> {
          ChainSemanticRevision revision = invocation.getArgument(1);
          List<ResolvedServiceCallBinding> bindings = invocation.getArgument(2);
          return compiledResult(
              graphCompiler.compile(revision, CONTRACT, bindings == null ? List.of() : bindings));
        };
    org.mockito.Mockito.doAnswer(compile)
        .when(approvedCompilerExecutionRunner)
        .execute(any(), any(), anyList(), any(), any());
    org.mockito.Mockito.doAnswer(compile)
        .when(approvedCompilerExecutionRunner)
        .execute(any(), any(), anyList(), any(), any(), any());
    org.mockito.Mockito.doAnswer(compile)
        .when(approvedCompilerExecutionRunner)
        .execute(any(), any(), anyList(), any(), any(), any(), any(), any());
    when(catalog.resolveOrCreateChain(anyString(), anyString(), any()))
        .thenReturn(Uni.createFrom().item("catalog-chain-1"));
    when(catalog.applyGraph(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ChainPlanGraph desired = invocation.getArgument(1);
              return Uni.createFrom().item(ownedApplyResult(desired));
            });
    when(factsService.load(anyString()))
        .thenAnswer(
            invocation -> {
              String chainId = invocation.getArgument(0);
              ChainPlanGraph graph =
                  artifactStore
                      .latest(RUN_ID, Kind.CHAIN_PLAN_GRAPH)
                      .map(stored -> artifactStore.payload(stored, ChainPlanGraph.class))
                      .orElse(null);
              return factsMatching(chainId, graph);
            });
  }

  private CompilerDagExecutionResult compiledResult(ChainPlanGraph graph) {
    String digest = new CanonicalGraphDigest(mapper).sha256(graph);
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "ok",
        List.of("cip-trigger-generator", "cip-service-call-generator"),
        new PlanningPatchLedger(List.of(), List.of()),
        graph,
        new GraphAssemblyResult(1, graph, digest, List.of(), List.of(), List.of()),
        new CompilerValidationBundle(
            1,
            digest,
            List.of(
                new CompilerValidationPass(
                    "graph", new ValidationResult(true, List.of(), "ok")))));
  }

  private static CatalogGraphMaterializeResult ownedApplyResult(ChainPlanGraph desired) {
    Map<String, String> nodes = new LinkedHashMap<>();
    for (ChainPlanNode node : desired.nodes()) {
      nodes.put(node.nodeId(), "catalog-" + node.nodeId());
    }
    Map<String, String> edges = new LinkedHashMap<>();
    for (ChainPlanEdge edge : desired.edges()) {
      edges.put(edge.edgeId(), nodes.get(edge.fromNodeId()));
    }
    Map<String, String> mappings = new LinkedHashMap<>();
    for (ChainPlanNode node : desired.nodes()) {
      String intentId = MappingExecutionSite.mappingIntentId(node);
      if (intentId != null && !intentId.isBlank()) {
        mappings.put(intentId, node.nodeId());
      }
    }
    MaterializationMap owned =
        new MaterializationMap("catalog-chain-1", nodes, edges, mappings);
    List<String> nodeIds = List.copyOf(nodes.keySet());
    return new CatalogGraphMaterializeResult(
        owned,
        nodeIds,
        List.of(),
        null,
        List.of(),
        nodeIds,
        Map.of(),
        desired.edges(),
        List.of(),
        List.of(),
        List.of(),
        false);
  }

  private static ChainCatalogFacts factsMatching(String chainId, ChainPlanGraph graph) {
    if (graph == null) {
      return new ChainCatalogFacts(
          chainId, "", "", 0, 0, "", List.of(), List.of(), "built_in_catalog");
    }
    List<ChainCatalogElement> elements = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      Map<String, Object> properties = new LinkedHashMap<>();
      if (node.properties() != null) {
        for (PlanProperty property : node.properties()) {
          if (property != null && property.key() != null) {
            properties.put(property.key(), property.value());
          }
        }
      }
      elements.add(
          new ChainCatalogElement(
              "catalog-" + node.nodeId(),
              node.type(),
              node.label(),
              node.parentNodeId() == null ? null : "catalog-" + node.parentNodeId(),
              properties));
    }
    List<ChainCatalogDependency> dependencies = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      dependencies.add(
          new ChainCatalogDependency(
              "catalog-" + edge.fromNodeId(), "catalog-" + edge.toNodeId()));
    }
    return new ChainCatalogFacts(
        chainId,
        graph.chain().name(),
        graph.chain().description() == null ? "" : graph.chain().description(),
        elements.size(),
        dependencies.size(),
        "",
        elements,
        dependencies,
        "built_in_catalog");
  }

  private DesignInputCapability designInputCapability() {
    return new DesignInputCapability(
        (conversationId, prompt) -> {
          ProductCapabilityCaptureContext.offerSemantic(offeredRevision);
          return Multi.createFrom().empty();
        },
        new DefaultChainSemanticIdsRenderer());
  }

  private DesignPlanningCapability designPlanningCapability(
      ProductPipelineArtifactStore artifacts) {
    return new DesignPlanningCapability(
        (conversationId, skillId, input, formatFailure, repairEvidence, pinnedSkillHash) ->
            plannerReport(),
        artifacts);
  }

  private DesignExecutionCapability designExecutionCapability(
      ProductPipelineArtifactStore artifacts) {
    CompilerPlanValidator planValidator = mock(CompilerPlanValidator.class);
    when(planValidator.validate(any())).thenReturn(new ValidationResult(true, List.of(), "ok"));
    designExecutorAdapter =
        new CipDesignExecutorJavaAdapter(
            approvedCompilerExecutionRunner,
            new DefaultExecutorCatalogBindingAdapter(catalogReadTool, mock(OperationSchemaLoader.class)),
            artifacts,
            planValidator);
    return new DesignExecutionCapability(artifacts, designExecutorAdapter);
  }

  private StageCapability discoveryStub() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
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
                                Kind.REQUIREMENT_DRAFT,
                                RequirementFactFixtures.greetingsApprovedDraft(),
                                List.of()),
                            new ArtifactCandidate(
                                Kind.CATALOG_BINDING_HINT,
                                petsBindingHint(),
                                List.of())),
                        "discovered",
                        null)));
      }
    };
  }

  private StageCapability analysisStub() {
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
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.REQUIREMENT_BRIEF, approvedBrief(), List.of())),
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

  private void approveLatestWaiting(CreateChainTestOrchestrator runtime) {
    Reference candidate =
        loadRun().run().stages().stream()
            .filter(stage -> stage.approvableReference() != null)
            .reduce((a, b) -> b)
            .orElseThrow()
            .approvableReference();
    runtime
        .approve(new ApproveCommand(RUN_ID, candidate, loadRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void implementApprovedPlan(CreateChainTestOrchestrator runtime) {
    ApprovalRecordV2 approval =
        artifactStore.payload(
            artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
                .filter(item -> "2".equals(item.schemaVersion()))
                .reduce((a, b) -> b)
                .orElseThrow(),
            ApprovalRecordV2.class);
    runtime
        .implement(
            new ImplementCommand(
                RUN_ID, approval.targetContentHash(), loadRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument
      loadRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private ChainSemanticRevision latestRevision() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.CHAIN_SEMANTIC_REVISION).orElseThrow(),
        ChainSemanticRevision.class);
  }

  private ChainPlanGraph latestGraph() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.CHAIN_PLAN_GRAPH).orElseThrow(), ChainPlanGraph.class);
  }

  private MaterializationMap latestMap() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_MAP).orElseThrow(),
        MaterializationMap.class);
  }

  private ApprovalRecordV2 latestSemanticApproval() {
    return artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
        .filter(item -> "2".equals(item.schemaVersion()))
        .map(item -> artifactStore.payload(item, ApprovalRecordV2.class))
        .filter(item -> Kind.CHAIN_SEMANTIC_REVISION.name().equals(item.subjectArtifactKind()))
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  private RunManifest latestManifest() {
    return artifactStore.payload(
        artifactStore.latest(RUN_ID, Kind.RUN_MANIFEST).orElseThrow(), RunManifest.class);
  }

  private Set<Kind> historyKinds() {
    Set<Kind> kinds = new LinkedHashSet<>();
    for (Kind kind : Kind.values()) {
      if (!artifactStore.history(RUN_ID, kind).isEmpty()) {
        kinds.add(kind);
      }
    }
    return kinds;
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
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
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

  private static CatalogBindingHint petsBindingHint() {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        "call-1",
        "call-1",
        "GET /pets",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-1",
        "http",
        "GET",
        "/pets",
        "2024.4",
        FIXED,
        "catalog-hit");
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
        id);
  }

  private static RequirementDataMapping mapping(
      String id,
      RequirementDataMapping.Stage stage,
      String from,
      String to,
      RequirementDataMapping.Mode mode) {
    return new RequirementDataMapping(id, stage, from, to, mode, List.of(), List.of(id));
  }

  private String plannerReport() {
    if (offeredRevision != null
        && "revision-two-entry".equals(offeredRevision.revisionId())) {
      return twoEntryPlannerReport();
    }
    if (offeredRevision != null
        && "revision-reconverge".equals(offeredRevision.revisionId())) {
      return reconvergePlannerReport();
    }
    return petsPlannerReport();
  }

  private static String twoEntryPlannerReport() {
    return """
        1. Analyze requirements and name chain Two entry shared downstream (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        3. Generate Kafka Trigger element with interface Kafka (cip-trigger-generator)
        4. Generate execution structure and element ordering (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String reconvergePlannerReport() {
    return """
        1. Analyze requirements and name chain Condition reconverge (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        3. Generate execution structure and element ordering (cip-structure-generator)
        4. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        5. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static String petsPlannerReport() {
    return """
        1. Analyze requirements and name chain Pets (cip-requirement-analyzer + cip-naming-generator)
        2. Resolve External integration target Petstore Ext from the retrieved spec (binding for cip-service-call-generator)
        3. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        4. Generate Service Call element for Petstore Ext.GET /pets bound to the retrieved spec (cip-service-call-generator)
        5. Generate execution structure and element ordering (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }
}
