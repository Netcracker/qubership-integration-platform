package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
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
  @Mock private PendingNodeRecoveryResolver resolver;
  @Mock private ProductPipelineArtifactStore artifactStore;
  @Mock private org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService factsService;

  private ProductChainMaterializer materializer;

  @BeforeEach
  void setUp() {
    materializer = new ProductChainMaterializer(catalog, resolver, artifactStore, factsService);
  }

  @Test
  void resumesAfterChainWasCreatedBeforeCheckpointCommit() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationCheckpoint checkpoint = beforeChainCheckpoint(RUN_ID);
    when(catalog.resolveOrCreateChain(RUN_ID, "demo-chain", "Demo")).thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-chain-1"));
    when(catalog.materializeSkeletonElement(any(), any(), anyString(), any()))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item("catalog-script-1"));
    when(catalog.applyProperties(any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(new ChainPlanPropertiesMaterializer.PropertiesApplyResult(0, List.of(), null)));
    when(catalog.applyConnections(any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(new ChainPlanConnectionsMaterializer.ConnectionsApplyResult(0, List.of())));
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
    verify(catalog).resolveOrCreateChain(RUN_ID, inputs.graph().chain().name(), inputs.graph().chain().description());
  }

  @Test
  void resumesAfterElementWasCreatedBeforeCheckpointCommit() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationCheckpoint checkpoint =
        pendingNodeCheckpoint(
            "script-1", new MaterializationMap("catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1")));
    CatalogElementResponseDto existing = element("catalog-script-1", "script", "Script", "catalog-trigger-1");
    when(catalog.listElements("catalog-chain-1"))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(List.of(existing)));
    when(resolver.resolve(any(), any(), any())).thenReturn("catalog-script-1");
    when(catalog.applyProperties(any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(new ChainPlanPropertiesMaterializer.PropertiesApplyResult(0, List.of(), null)));
    when(catalog.applyConnections(any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(new ChainPlanConnectionsMaterializer.ConnectionsApplyResult(0, List.of())));
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

    assertEquals("catalog-script-1", result.materializationMap().nodeIdToElementId().get("script-1"));
    verify(catalog, never()).materializeSkeletonElement(any(), any(), anyString(), any());
  }

  @Test
  void restartAfterPropertiesDoesNotRecreateElements() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationMap completedElements =
        new MaterializationMap(
            "catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"));
    MaterializationCheckpoint checkpoint =
        new MaterializationCheckpoint(
            1,
            RUN_ID,
            "catalog-chain-1",
            MaterializationPhase.PROPERTIES,
            completedElements,
            null,
            Map.of());
    when(catalog.applyConnections(any(), any()))
        .thenReturn(
            io.smallrye.mutiny.Uni.createFrom()
                .item(new ChainPlanConnectionsMaterializer.ConnectionsApplyResult(0, List.of())));
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

    verify(catalog, never()).materializeSkeletonElement(any(), any(), anyString(), any());
    verify(catalog).applyConnections(inputs.graph(), completedElements);
  }

  @Test
  void failsClosedWhenPendingNodeHasTwoCandidates() {
    ProductChainMaterializer.Inputs inputs = inputs();
    MaterializationCheckpoint checkpoint =
        pendingNodeCheckpoint(
            "script-1", new MaterializationMap("catalog-chain-1", Map.of("trigger-1", "catalog-trigger-1")));
    CatalogElementResponseDto first = element("catalog-script-1", "script", "Script", "catalog-trigger-1");
    CatalogElementResponseDto second = element("catalog-script-2", "script", "Script", "catalog-trigger-1");
    when(catalog.listElements("catalog-chain-1"))
        .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(List.of(first, second)));
    when(resolver.resolve(any(), any(), any()))
        .thenThrow(new IllegalStateException("multiple pending-node candidates"));

    assertThrows(IllegalStateException.class, () -> materializer.resume(inputs, checkpoint));
  }

  private static ProductChainMaterializer.Inputs inputs() {
    return new ProductChainMaterializer.Inputs(RUN_ID, graph(), runManifest(RUN_ID), "graph-digest-1");
  }

  private static MaterializationCheckpoint beforeChainCheckpoint(String executionKey) {
    return new MaterializationCheckpoint(
        1, executionKey, null, null, new MaterializationMap(null, Map.of()), null, Map.of());
  }

  private static MaterializationCheckpoint pendingNodeCheckpoint(
      String pendingNodeId, MaterializationMap map) {
    return new MaterializationCheckpoint(
        1, RUN_ID, map.chainId(), MaterializationPhase.CHAIN, map, pendingNodeId, Map.of());
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

  private static CatalogElementResponseDto element(
      String id, String type, String label, String parentElementId) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.type = type;
    dto.name = label;
    dto.parentElementId = parentElementId;
    return dto;
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
