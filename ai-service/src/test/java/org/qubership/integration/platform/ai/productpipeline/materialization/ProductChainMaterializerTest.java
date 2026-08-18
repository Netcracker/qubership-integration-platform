package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class ProductChainMaterializerTest {

  private static final String RUN_ID = "run-materialize-1";

  @Mock private CatalogMutationGateway catalog;
  @Mock private ProductPipelineArtifactStore artifactStore;
  @Mock private org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService factsService;

  private ProductChainMaterializer materializer;

  @BeforeEach
  void setUp() {
    materializer = new ProductChainMaterializer(catalog, artifactStore, factsService);
  }

  @Test
  void materializesThroughApplyGraphBoundary() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationCheckpoint checkpoint = beforeChainCheckpoint(RUN_ID);
    MaterializationMap resultMap =
        new MaterializationMap(
            "catalog-chain-1",
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"));
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo"))
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
            "catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"));
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
  void legacyPropertiesCheckpointReappliesGraph() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationMap partialMap =
        new MaterializationMap("catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1"));
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
            Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"));
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
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo"))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-chain-1"));
    when(catalog.applyGraph(any(), any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(
                    new CatalogGraphMaterializeResult(
                        new MaterializationMap("catalog-chain-1", Map.of()),
                        List.of(),
                        List.of("script-1"),
                        "catalog refused",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        false)));

    assertThrows(IllegalStateException.class, () -> materializer.resume(inputs, null));
  }

  private static ProductChainMaterializer.Inputs inputs() {
    return new ProductChainMaterializer.Inputs(RUN_ID, graph(), runManifest(RUN_ID), "graph-digest-1");
  }

  private static MaterializationCheckpoint beforeChainCheckpoint(String executionKey) {
    return new MaterializationCheckpoint(
        1, executionKey, null, null, new MaterializationMap(null, Map.of()), null, Map.of());
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
