package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.patch.PatchedChain;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ConnectionsApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.materialization.ProductChainMaterializer;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

@ExtendWith(MockitoExtension.class)
class CatalogGraphMaterializerTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private ChainPlanPropertiesMaterializer propertiesMaterializer;
  @Mock private ChainPlanSkeletonMaterializer skeletonMaterializer;
  @Mock private ChainPlanConnectionsMaterializer connectionsMaterializer;
  @Mock private ChainPlanRemovalsMaterializer removalsMaterializer;
  @Mock private CatalogRestClient catalogRestClient;
  @Mock private CatalogElementDescriptorLoader descriptorLoader;

  private CatalogGraphMaterializer materializer;

  @BeforeEach
  void setUp() {
    lenient()
        .when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(1, List.of(), null));
    lenient()
        .when(connectionsMaterializer.apply(any(), any()))
        .thenReturn(new ConnectionsApplyResult(1, List.of()));
    lenient()
        .when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of(), List.of(), List.of(), null));
    CatalogElementDescriptorTestSupport.stubPermissive(descriptorLoader);
    materializer =
        new CatalogGraphMaterializer(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader);
  }

  @Test
  void createPathCallsTheSameApplyAsEdit() {
    CatalogGraphMaterializer spyMaterializer = spy(materializer);
    org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService publicationService =
        mock(org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService.class);
    when(publicationService.resolveOrCreate(any(), any(), any())).thenReturn(CHAIN_ID);
    CatalogMutationGateway gateway =
        new CatalogMutationGateway(
            spyMaterializer,
            mock(org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService.class),
            publicationService);
    org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService factsService =
        mock(org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService.class);
    when(factsService.load(CHAIN_ID))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                CHAIN_ID, "demo-chain", "Demo", 0, 0, "", List.of(), List.of(), "built_in_catalog"));

    ChainPlanGraph desired = twoNodeGraph();
    when(skeletonMaterializer.materializeElement(any(), any(), eq(CHAIN_ID), any(), any()))
        .thenReturn("catalog-trigger-1", "catalog-script-1");

    new ProductChainMaterializer(
            gateway, mock(ProductPipelineArtifactStore.class), factsService)
        .resume(new ProductChainMaterializer.Inputs("run-1", desired, runManifest(), "digest"), null);

    verify(spyMaterializer)
        .apply(eq(CHAIN_ID), eq(CatalogGraphMaterializer.emptyCurrent(desired)), eq(desired), any());

    ChainPatchWriter editAdapter =
        new ChainPatchWriter(
            spyMaterializer, propertiesMaterializer, connectionsMaterializer, removalsMaterializer);
    editAdapter.write(
        importedChain(desired),
        new GraphPatch(
            "patch-edit",
            "test",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of()),
                    null)),
            List.of(),
            List.of(),
            null,
            List.of(),
            "add script"));

    verify(spyMaterializer, org.mockito.Mockito.atLeast(2))
        .apply(eq(CHAIN_ID), any(), any(), any());
  }

  @Test
  void catalogMutationsFollowSpecOrder() {
    ChainPlanGraph current = currentForOrderTest();
    ChainPlanGraph desired = desiredForOrderTest();
    MaterializationMap map =
        new MaterializationMap(
            CHAIN_ID,
            new LinkedHashMap<>(
                Map.of(
                    "trigger-1",
                    "catalog-trigger-1",
                    "service-call",
                    "catalog-service-call",
                    "try-2",
                    "catalog-try-2")));

    when(skeletonMaterializer.materializeElement(any(), any(), eq(CHAIN_ID), any(), any()))
        .thenReturn("catalog-condition", "catalog-if");
    when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of("dep-block"), List.of(), List.of(), List.of(), null))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of("catalog-removed"), List.of(), List.of(), null));
    stubSuccessfulTransfer("catalog-service-call", "catalog-try-2");

    materializer.apply(CHAIN_ID, current, desired, map);

    ArgumentCaptor<List<ChainPlanEdge>> dependencyEdges =
        ArgumentCaptor.forClass(List.class);
    InOrder order =
        inOrder(
            skeletonMaterializer,
            propertiesMaterializer,
            removalsMaterializer,
            catalogRestClient,
            connectionsMaterializer);
    order.verify(skeletonMaterializer, times(2))
        .materializeElement(any(), any(), eq(CHAIN_ID), any(), any());
    order.verify(skeletonMaterializer)
        .finishCreatedContainers(eq(CHAIN_ID), any(), any(), any());
    order.verify(propertiesMaterializer).apply(any(), any());
    order.verify(removalsMaterializer)
        .apply(any(), eq(Set.of()), dependencyEdges.capture(), any());
    order.verify(catalogRestClient).transferElements(eq(CHAIN_ID), any());
    order.verify(connectionsMaterializer).apply(any(), any());
    order.verify(removalsMaterializer)
        .apply(any(), eq(Set.of("removed-node")), eq(List.of()), any());

    assertEquals(
        List.of("edge-block", "edge-old"),
        dependencyEdges.getValue().stream().map(ChainPlanEdge::edgeId).toList());
  }

  @Test
  void reparentIsTakenFromGraphDifference() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("try-2", "try-2", "Try", null, null, List.of()),
                new ChainPlanNode(
                    "service-call", "service-call", "Call", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("try-2", "try-2", "Try", null, null, List.of()),
                new ChainPlanNode(
                    "service-call", "service-call", "Call", "try-2", null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "try-2", "catalog-try-2",
                "service-call", "catalog-service-call"));
    stubSuccessfulTransfer("catalog-service-call", "catalog-try-2");

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    verify(catalogRestClient).transferElements(eq(CHAIN_ID), any(CatalogTransferElementsRequest.class));
    assertTrue(result.succeeded());
  }

  private void stubSuccessfulTransfer(String elementId, String parentId) {
    when(catalogRestClient.transferElements(eq(CHAIN_ID), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));
    CatalogElementResponseDto readBack = new CatalogElementResponseDto();
    readBack.id = elementId;
    readBack.parentElementId = parentId;
    when(catalogRestClient.getElement(CHAIN_ID, elementId)).thenReturn(readBack);
  }

  private static ChainPlanGraph twoNodeGraph() {
    return new ChainPlanGraph(
        "1.0",
        section(),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
        List.of(new ChainPlanEdge("edge-1", "trigger-1", "script-1", null)));
  }

  private static PatchedChain importedChain(ChainPlanGraph desired) {
    ChainPlanGraph current =
        new ChainPlanGraph(
            desired.schemaVersion(),
            desired.chain(),
            List.of(desired.nodes().get(0)),
            List.of());
    MaterializationMap map =
        new MaterializationMap(CHAIN_ID, Map.of("trigger-1", "catalog-trigger-1"));
    return new PatchedChain(current, desired, map);
  }

  private static ChainPlanGraph currentForOrderTest() {
    return new ChainPlanGraph(
        "1.0",
        section(),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("try-2", "try-2", "Try", null, null, List.of()),
            new ChainPlanNode(
                "service-call", "service-call", "Call", null, null, List.of()),
            new ChainPlanNode("removed-node", "script", "Old", null, null, List.of())),
        List.of(
            new ChainPlanEdge("edge-block", "trigger-1", "service-call", null),
            new ChainPlanEdge("edge-old", "trigger-1", "removed-node", null)));
  }

  private static ChainPlanGraph desiredForOrderTest() {
    return new ChainPlanGraph(
        "1.0",
        section(),
        List.of(
            new ChainPlanNode(
                "trigger-1",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(new PlanProperty("path", "/orders"))),
            new ChainPlanNode("try-2", "try-2", "Try", null, null, List.of()),
            new ChainPlanNode(
                "service-call", "service-call", "Call", "try-2", null, List.of()),
            new ChainPlanNode("condition-1", "condition", "Cond", "try-2", null, List.of()),
            new ChainPlanNode(
                "if-1", "if", "If", "condition-1", null, List.of(new PlanProperty("script", "42")))),
        List.of(new ChainPlanEdge("edge-new", "trigger-1", "if-1", null)));
  }

  private static ChainSection section() {
    return new ChainSection("demo-chain", "Demo");
  }

  private static RunManifest runManifest() {
    return new RunManifest(
        "run-1",
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
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }
}
