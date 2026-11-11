package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;

@ExtendWith(MockitoExtension.class)
class ProductChainMaterializerTest {

  private static final String RUN_ID = "run-materialize-1";

  @Mock private CatalogMutationGateway catalog;
  @Mock private ProductPipelineArtifactStore artifactStore;
  @Mock private org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService factsService;

  private ProductChainMaterializer materializer;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    ChainPlanGraphImporter graphImporter =
        new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper));
    materializer = new ProductChainMaterializer(catalog, artifactStore, factsService, graphImporter);
  }

  @Test
  void materializesThroughApplyGraphBoundary() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationCheckpoint checkpoint = beforeChainCheckpoint(RUN_ID);
    MaterializationMap resultMap =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of());
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo", null))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-chain-1"));
    when(catalog.applyGraph(
            eq(CatalogGraphMaterializer.emptyCurrent(inputs.graph())),
            eq(inputs.graph()),
            any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        resultMap,
                        List.of("trigger-1", "script-1"),
                        List.of(),
                        null,
                        List.of(),
                        List.of("trigger-1", "script-1"),
                        Map.of(),
                        List.of(inputs.graph().edges().get(0)),
                        List.of(),
                        List.of(),
                        List.of(),
                        false)));
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                0,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));

    MaterializationResult result = materializer.resume(inputs, checkpoint);

    assertEquals("catalog-chain-1", result.chainId());
    assertEquals(MaterializationPhase.READ_BACK, result.completedPhase());
    verify(catalog).applyGraph(any(), eq(inputs.graph()), any());
    verify(catalog, never()).importApiHubSpecification(any(), any());
  }

  @Test
  void restartAfterElementsDoesNotReapplyGraph() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationMap completedElements =
        new MaterializationMap(
            "catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of());
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.ELEMENTS,
            completedElements,
            null,
            Map.of());
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                0,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));

    materializer.resume(inputs, checkpoint);

    verify(catalog, never()).applyGraph(any(), any(), any());
  }

  @Test
  void preservesOwnershipInCheckpointsAndOnRestart() throws Exception {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationMap owners =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"),
            Map.of("edge-1", "catalog-trigger-1"),
            Map.of());
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo", null))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-chain-1"));
    when(catalog.applyGraph(any(), eq(inputs.graph()), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(CatalogGraphMaterializeResult.noOp(owners)));
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new ChainCatalogFacts(
                "catalog-chain-1", "demo-chain", "Demo", 0, 0, "", List.of(), List.of(),
                "built_in_catalog"));

    MaterializationResult initial = materializer.resume(inputs, null);
    materializer.markComplete(inputs, materializer.markReconciled(inputs, initial));

    ArgumentCaptor<AppendCommand> saved = ArgumentCaptor.forClass(AppendCommand.class);
    verify(artifactStore, atLeastOnce()).append(saved.capture());
    List<MaterializationCheckpoint> checkpoints =
        saved.getAllValues().stream()
            .map(command -> (MaterializationCheckpoint) command.payload())
            .filter(checkpoint -> checkpoint.completedPhase() != MaterializationPhase.CHAIN)
            .toList();
    assertEquals(4, checkpoints.size());
    for (MaterializationCheckpoint checkpoint : checkpoints) {
      assertEquals(owners, checkpoint.materializationMap(), checkpoint.completedPhase().name());
      ObjectMapper mapper = new ObjectMapper();
      MaterializationCheckpoint persisted =
          mapper.readValue(mapper.writeValueAsBytes(checkpoint), MaterializationCheckpoint.class);
      assertEquals(owners, materializer.resume(inputs, persisted).materializationMap());
    }
  }

  @Test
  void legacyPropertiesCheckpointReappliesGraph() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationMap partialMap =
        new MaterializationMap("catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1"), Map.of(), Map.of());
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.PROPERTIES,
            partialMap,
            null,
            Map.of());
    MaterializationMap resultMap =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of());
    when(catalog.applyGraph(any(), eq(inputs.graph()), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        resultMap,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false)));
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                0,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));

    materializer.resume(inputs, checkpoint);

    verify(catalog).applyGraph(any(), eq(inputs.graph()), any());
  }

  @Test
  void failsClosedWhenApplyGraphFails() {
    ProductChainMaterializer.Inputs inputs = inputs();
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo", null))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-chain-1"));
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                0,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(catalog.applyGraph(any(), any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        new MaterializationMap("catalog-chain-1", Map.of(), Map.of(), Map.of()),
                        List.of(),
                        List.of("script-1"),
                        "catalog refused",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false)));

    assertThrows(IllegalStateException.class, () -> materializer.resume(inputs, null));
  }

  @Test
  void successfulBindingsSurvivePartialGraphFailureAndReconstruction() {
    var blobs = new InMemoryArtifactBlobStore();
    var mapper = new ObjectMapper().findAndRegisterModules();
    var saved = new ProductPipelineArtifactStore(
        new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC()));
    var importer = new ChainPlanGraphImporter(mapper, new CanonicalGraphDigest(mapper));
    var desired = new ChainPlanGraph("1.0", new ChainSection("demo-chain", "Demo"), List.of(
        new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
        new ChainPlanNode("script-1", "script", "Prepare", null, null, List.of()),
        new ChainPlanNode("script-2", "script", "Verify", null, null, List.of())), List.of());
    var input = new ProductChainMaterializer.Inputs(RUN_ID, desired, runManifest(RUN_ID), "digest");
    var original = new MaterializationCheckpoint(1, "publication-key", "catalog-chain-1",
        MaterializationPhase.CHAIN, new MaterializationMap("catalog-chain-1",
        Map.of("trigger-1", "catalog-trigger-1"), Map.of(), Map.of()), null, Map.of());
    var known = new MaterializationMap("catalog-chain-1",
        Map.of("trigger-1", "catalog-trigger-1", "script-2", "catalog-script-a"), Map.of(), Map.of());
    var partial = new CatalogGraphMaterializeResult(known, List.of("script-2"), List.of("script-1"),
        "The first script was committed, but its response failed.", List.of(), List.of("script-2"),
        Map.of(), List.of(), List.of(), List.of(), List.of(), false);
    when(factsService.load("catalog-chain-1")).thenReturn(new ChainCatalogFacts("catalog-chain-1",
        "demo-chain", "Demo", 1, 0, "", List.of(new ChainCatalogElement("catalog-trigger-1",
        "http-trigger", "Trigger", null, Map.of())), List.of(), "catalog"));
    when(catalog.applyGraph(any(), eq(desired), any()))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(partial))
        .thenAnswer(call -> io.smallrye.mutiny.Uni.createFrom().item(
            CatalogGraphMaterializeResult.noOp(call.getArgument(2))));
    var first = new ProductChainMaterializer(catalog, saved, factsService, importer);

    assertThrows(IllegalStateException.class, () -> first.resume(input, original));
    var reconstructed = new ProductPipelineArtifactStore(
        new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC()));
    MaterializationCheckpoint retained = reconstructed.payload(
        reconstructed.latest(RUN_ID, Kind.MATERIALIZATION_CHECKPOINT).orElseThrow(),
        MaterializationCheckpoint.class);
    assertEquals(known, retained.materializationMap());
    assertEquals("publication-key", retained.executionKey());
    assertEquals(MaterializationPhase.CHAIN, retained.completedPhase());

    when(factsService.load("catalog-chain-1")).thenReturn(new ChainCatalogFacts("catalog-chain-1",
        "demo-chain", "Demo", 3, 0, "", List.of(
        new ChainCatalogElement("catalog-trigger-1", "http-trigger", "Trigger", null, Map.of()),
        new ChainCatalogElement("catalog-script-z", "script", "Prepare", null, Map.of()),
        new ChainCatalogElement("catalog-script-a", "script", "Verify", null, Map.of())),
        List.of(), "catalog"));
    var restarted = new ProductChainMaterializer(catalog, reconstructed, factsService, importer);
    MaterializationResult resumed = restarted.resume(input, retained);
    assertEquals(Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-z",
        "script-2", "catalog-script-a"), resumed.materializationMap().nodeIdToElementId());
    assertEquals(MaterializationPhase.READ_BACK, resumed.completedPhase());
    verify(catalog, never()).resolveOrCreateChain(any(), any(), any(), any());
  }

  @Test
  void doesNotRecreateParentAfterCrashBetweenCreateAndCheckpoint() {
    ChainPlanGraph desired = conditionGraph();
    ProductChainMaterializer.Inputs inputs =
        new ProductChainMaterializer.Inputs(RUN_ID, desired, runManifest(RUN_ID), "graph-digest-1");
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.CHAIN,
            new MaterializationMap("catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1"), Map.of(), Map.of()),
            null,
            Map.of());
    when(factsService.load("catalog-chain-1")).thenReturn(conditionFacts());

    ArgumentCaptor<MaterializationMap> mapCaptor = ArgumentCaptor.forClass(MaterializationMap.class);
    when(catalog.applyGraph(any(), eq(desired), mapCaptor.capture()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        new MaterializationMap(
                            "catalog-chain-1",
                            Map.of(
                                "trigger-1", "catalog-trigger-1",
                                "condition-1", "catalog-condition-1",
                                "if-1", "catalog-if-1"), Map.of(), Map.of()),
                        List.of("trigger-1", "condition-1", "if-1"),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false)));
    when(factsService.load("catalog-chain-1")).thenReturn(conditionFacts());

    materializer.resume(inputs, checkpoint);

    Map<String, String> seededMap = mapCaptor.getValue().nodeIdToElementId();
    assertEquals("catalog-condition-1", seededMap.get("condition-1"));
    assertEquals("catalog-if-1", seededMap.get("if-1"));
    verify(catalog, never()).resolveOrCreateChain(any(), any(), any(), any());
  }

  @Test
  void adoptsGeneratedChildrenFromReadBackWithoutSecondCreate() {
    ChainPlanGraph desired = conditionGraph();
    ProductChainMaterializer.Inputs inputs =
        new ProductChainMaterializer.Inputs(RUN_ID, desired, runManifest(RUN_ID), "graph-digest-1");
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.CHAIN,
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "condition-1", "catalog-condition-1"), Map.of(), Map.of()),
            null,
            Map.of());
    when(factsService.load("catalog-chain-1")).thenReturn(conditionFacts());

    ArgumentCaptor<MaterializationMap> mapCaptor = ArgumentCaptor.forClass(MaterializationMap.class);
    when(catalog.applyGraph(any(), eq(desired), mapCaptor.capture()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        new MaterializationMap(
                            "catalog-chain-1",
                            Map.of(
                                "trigger-1", "catalog-trigger-1",
                                "condition-1", "catalog-condition-1",
                                "if-1", "catalog-if-1"), Map.of(), Map.of()),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false)));

    materializer.resume(inputs, checkpoint);

    assertEquals("catalog-if-1", mapCaptor.getValue().nodeIdToElementId().get("if-1"));
  }

  @Test
  void checkpointMappingIsNotRebound() {
    ChainPlanGraph desired = twinConditionGraph();
    ProductChainMaterializer.Inputs inputs =
        new ProductChainMaterializer.Inputs(RUN_ID, desired, runManifest(RUN_ID), "graph-digest-1");
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.CHAIN,
            new MaterializationMap(
                "catalog-chain-1",
                Map.of(
                    "trigger-1", "catalog-trigger-1",
                    "condition-1", "catalog-condition-a"), Map.of(), Map.of()),
            null,
            Map.of());
    when(factsService.load("catalog-chain-1")).thenReturn(twinConditionFacts());

    ArgumentCaptor<MaterializationMap> mapCaptor = ArgumentCaptor.forClass(MaterializationMap.class);
    when(catalog.applyGraph(any(), eq(desired), mapCaptor.capture()))
        .thenAnswer(
            invocation ->
                io.smallrye.mutiny.Uni.createFrom()
                    .item(
                        new CatalogGraphMaterializeResult(
                            invocation.getArgument(2),
                            List.of(),
                            List.of(),
                            null,
                            List.of(),
                            List.of(),
                            Map.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            false)));

    materializer.resume(inputs, checkpoint);

    assertEquals("catalog-condition-a", mapCaptor.getValue().nodeIdToElementId().get("condition-1"));
    assertEquals("catalog-condition-b", mapCaptor.getValue().nodeIdToElementId().get("condition-2"));
  }

  private static ProductChainMaterializer.Inputs inputs() {
    return new ProductChainMaterializer.Inputs(RUN_ID, graph(), runManifest(RUN_ID), "graph-digest-1");
  }

  private static MaterializationCheckpoint beforeChainCheckpoint(String executionKey) {
    return new MaterializationCheckpoint(
        1, executionKey, null, null, new MaterializationMap(null, Map.of(), Map.of(), Map.of()), null, Map.of());
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
        List.of(new ChainPlanEdge("edge-1", "trigger-1", "script-1", null)));
  }

  private static ChainPlanGraph conditionGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("condition-1", "condition", "Condition", "trigger-1", null, List.of()),
            new ChainPlanNode("if-1", "if", "If", "condition-1", null, List.of())),
        List.of());
  }

  private static ChainPlanGraph twinConditionGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("condition-1", "condition", "Condition A", "trigger-1", null, List.of()),
            new ChainPlanNode("condition-2", "condition", "Condition B", "trigger-1", null, List.of())),
        List.of());
  }

  private static ChainCatalogFacts conditionFacts() {
    return new ChainCatalogFacts(
        "catalog-chain-1",
        "demo-chain",
        "Demo",
        3,
        0,
        "",
        List.of(
            new ChainCatalogElement("catalog-trigger-1", "http-trigger", "Trigger", null, Map.of()),
            new ChainCatalogElement(
                "catalog-condition-1", "condition", "Condition", "catalog-trigger-1", Map.of()),
            new ChainCatalogElement("catalog-if-1", "if", "If", "catalog-condition-1", Map.of())),
        List.of(),
        "built_in_catalog");
  }

  private static ChainCatalogFacts twinConditionFacts() {
    return new ChainCatalogFacts(
        "catalog-chain-1",
        "demo-chain",
        "Demo",
        3,
        0,
        "",
        List.of(
            new ChainCatalogElement("catalog-trigger-1", "http-trigger", "Trigger", null, Map.of()),
            new ChainCatalogElement(
                "catalog-condition-a", "condition", "Condition A", "catalog-trigger-1", Map.of()),
            new ChainCatalogElement(
                "catalog-condition-b", "condition", "Condition B", "catalog-trigger-1", Map.of())),
        List.of(),
        "built_in_catalog");
  }

  private static RunManifest runManifest(String runId) {
    return new RunManifest(
        runId,
        null,
        List.of(),
        "product",
        "create-chain",
        "1",
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
}
