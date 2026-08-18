package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.patch.PatchedChain;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ConnectionsApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.Projection;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ProjectionAction;
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
import org.qubership.integration.platform.ai.productpipeline.materialization.ProductChainMaterializer;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
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
  @Mock private ChainCatalogFactsService factsService;

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
    ChainPlanGraphImporter graphImporter =
        new ChainPlanGraphImporter(new ObjectMapper(), new CanonicalGraphDigest(new ObjectMapper()));
    CatalogGraphReadBackVerifier readBackVerifier =
        new CatalogGraphReadBackVerifier(factsService, graphImporter);
    materializer =
        new CatalogGraphMaterializer(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader,
            readBackVerifier);
  }

  @Test
  void createPathCallsTheSameApplyAsEdit() {
    CatalogGraphMaterializer spyMaterializer = spy(materializer);
    CatalogChainPublicationService publicationService = mock(CatalogChainPublicationService.class);
    when(publicationService.resolveOrCreate(any(), any(), any())).thenReturn(CHAIN_ID);
    CatalogMutationGateway gateway =
        new CatalogMutationGateway(
            spyMaterializer,
            mock(ApiHubSpecificationImportService.class),
            publicationService);
    ChainCatalogFactsService pipelineFactsService = mock(ChainCatalogFactsService.class);
    when(pipelineFactsService.load(CHAIN_ID))
        .thenReturn(
            new ChainCatalogFacts(
                CHAIN_ID, "demo-chain", "Demo", 0, 0, "", List.of(), List.of(), "built_in_catalog"));

    ChainPlanGraph desired = twoNodeGraph();
    when(skeletonMaterializer.materializeElement(any(), any(), eq(CHAIN_ID), any(), any()))
        .thenReturn("catalog-trigger-1", "catalog-script-1");
    stubMatchingImport(
        desired,
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "script-1", "catalog-script-1")));

    ProductChainMaterializer materializer =
        new ProductChainMaterializer(
            gateway,
            mock(ProductPipelineArtifactStore.class),
            pipelineFactsService,
            new ChainPlanGraphImporter(new ObjectMapper(), new CanonicalGraphDigest(new ObjectMapper())));
    materializer
        .resume(new ProductChainMaterializer.Inputs("run-1", desired, runManifest(), "digest"), null);

    verify(spyMaterializer)
        .apply(eq(CHAIN_ID), eq(CatalogGraphMaterializer.emptyCurrent(desired)), eq(desired), any());

    ChainPatchWriter editAdapter =
        new ChainPatchWriter(
            spyMaterializer, propertiesMaterializer, connectionsMaterializer, removalsMaterializer,
            catalogRestClient);
    ChainPlanGraph editDesired =
        new ChainPlanGraph(
            desired.schemaVersion(),
            desired.chain(),
            List.of(desired.nodes().get(0), desired.nodes().get(1)),
            desired.edges());
    stubMatchingImport(
        editDesired,
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "script-1", "catalog-script-1")));
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
    MaterializationMap finalMap =
        new MaterializationMap(
            CHAIN_ID,
            new LinkedHashMap<>(
                Map.of(
                    "trigger-1", "catalog-trigger-1",
                    "service-call", "catalog-service-call",
                    "try-2", "catalog-try-2",
                    "condition-1", "catalog-condition",
                    "if-1", "catalog-if")));
    stubMatchingImport(desired, finalMap);

    materializer.apply(CHAIN_ID, current, desired, map);

    ArgumentCaptor<List<ChainPlanEdge>> dependencyEdges = ArgumentCaptor.forClass(List.class);
    InOrder order =
        inOrder(
            skeletonMaterializer,
            propertiesMaterializer,
            removalsMaterializer,
            catalogRestClient,
            connectionsMaterializer,
            factsService);
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
    order.verify(factsService).load(CHAIN_ID);

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
    stubMatchingImport(desired, map);

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    verify(catalogRestClient).transferElements(eq(CHAIN_ID), any(CatalogTransferElementsRequest.class));
    assertTrue(result.succeeded());
  }

  @Test
  void mismatchFailsEvenWhenMutationsSucceeded() {
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
    ChainPlanGraph staleImport =
        new ChainPlanGraph(
            "1.0",
            section(),
            current.nodes(),
            List.of());
    stubMatchingImport(staleImport, map);

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("service-call"));
  }

  @Test
  void leftoverGeneratedChildFailsVerification() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("condition-1", "condition", "Cond", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "condition-1", null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(CHAIN_ID, Map.of("trigger-1", "catalog-trigger-1"));
    when(skeletonMaterializer.materializeElement(any(), any(), eq(CHAIN_ID), any(), any()))
        .thenReturn("catalog-condition", "catalog-if");
    MaterializationMap finalMap =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "condition-1", "catalog-condition",
                "if-1", "catalog-if"));
    List<ChainCatalogElement> elements = new ArrayList<>(factsElements(desired, finalMap));
    elements.add(
        new ChainCatalogElement(
            "catalog-leftover-else", "else", "Else", "catalog-condition", Map.of()));
    when(factsService.load(CHAIN_ID))
        .thenReturn(
            new ChainCatalogFacts(
                CHAIN_ID,
                "demo-chain",
                "Demo",
                elements.size(),
                0,
                "",
                elements,
                List.of(),
                "built_in_catalog"));

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("unrequested generated descendant"));
  }

  @Test
  void missingDesiredNodeFailsVerification() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(CHAIN_ID, Map.of("trigger-1", "catalog-trigger-1"));
    when(skeletonMaterializer.materializeElement(any(), any(), eq(CHAIN_ID), any(), any()))
        .thenReturn("catalog-script-1");
    MaterializationMap finalMap =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "script-1", "catalog-script-1"));
    stubMatchingImport(
        new ChainPlanGraph(
            desired.schemaVersion(),
            desired.chain(),
            List.of(desired.nodes().get(0)),
            List.of()),
        finalMap);

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("script-1"));
  }

  @Test
  void dependencyProjectionMismatchFailsVerification() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("edge-1", "trigger-1", "script-1", null)));
    MaterializationMap map =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "script-1", "catalog-script-1"));
    ChainCatalogFacts facts = importFacts(desired, map);
    List<ChainCatalogDependency> wrongDeps =
        List.of(new ChainCatalogDependency("catalog-script-1", "catalog-trigger-1"));
    when(factsService.load(CHAIN_ID))
        .thenReturn(
            new ChainCatalogFacts(
                CHAIN_ID,
                facts.chainName(),
                facts.chainDescription(),
                facts.elements().size(),
                wrongDeps.size(),
                "",
                facts.elements(),
                wrongDeps,
                "built_in_catalog"));

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("projected dependencies"));
  }

  @Test
  void removedNodeStillPresentFailsVerification() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("removed-node", "script", "Old", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "removed-node", "catalog-removed"));
    when(removalsMaterializer.apply(any(), eq(Set.of("removed-node")), eq(List.of()), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of("catalog-removed"), List.of(), List.of(), null));
    stubMatchingImport(
        desired,
        map,
        List.of(new ChainCatalogElement("catalog-removed", "script", "Old", null, Map.of())));

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("removed-node"));
  }

  @Test
  void unrelatedExistingIdsStayStable() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            section(),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
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
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(
            CHAIN_ID,
            Map.of(
                "trigger-1", "catalog-trigger-1",
                "script-1", "catalog-script-1"));
    stubMatchingImport(desired, map);

    CatalogGraphMaterializeResult result =
        materializer.apply(CHAIN_ID, current, desired, map);

    assertTrue(result.succeeded());
    assertEquals("catalog-trigger-1", result.materializationMap().nodeIdToElementId().get("trigger-1"));
    assertEquals("catalog-script-1", result.materializationMap().nodeIdToElementId().get("script-1"));
  }

  private void stubMatchingImport(ChainPlanGraph desired, MaterializationMap map) {
    stubMatchingImport(desired, map, List.of());
  }

  private void stubMatchingImport(
      ChainPlanGraph desired, MaterializationMap map, List<ChainCatalogElement> extraElements) {
    List<ChainCatalogElement> elements = new ArrayList<>(factsElements(desired, map));
    elements.addAll(extraElements);
    when(factsService.load(CHAIN_ID))
        .thenReturn(
            new ChainCatalogFacts(
                CHAIN_ID,
                desired.chain().name(),
                desired.chain().description(),
                elements.size(),
                factsDependencies(desired, map).size(),
                "",
                elements,
                factsDependencies(desired, map),
                "built_in_catalog"));
  }

  private ChainCatalogFacts importFacts(ChainPlanGraph desired, MaterializationMap map) {
    List<ChainCatalogElement> elements = factsElements(desired, map);
    List<ChainCatalogDependency> dependencies = factsDependencies(desired, map);
    return new ChainCatalogFacts(
        CHAIN_ID,
        desired.chain().name(),
        desired.chain().description(),
        elements.size(),
        dependencies.size(),
        "",
        elements,
        dependencies,
        "built_in_catalog");
  }

  private static List<ChainCatalogElement> factsElements(
      ChainPlanGraph desired, MaterializationMap map) {
    List<ChainCatalogElement> elements = new ArrayList<>();
    for (ChainPlanNode node : desired.nodes()) {
      String elementId = map.nodeIdToElementId().get(node.nodeId());
      if (elementId == null) {
        continue;
      }
      String parentElementId =
          node.parentNodeId() == null
              ? null
              : map.nodeIdToElementId().get(node.parentNodeId());
      elements.add(
          new ChainCatalogElement(
              elementId, node.type(), node.label(), parentElementId, Map.of()));
    }
    return elements;
  }

  private static List<ChainCatalogDependency> factsDependencies(
      ChainPlanGraph desired, MaterializationMap map) {
    List<ChainCatalogDependency> dependencies = new ArrayList<>();
    for (ChainPlanEdge edge : desired.edges()) {
      Projection projection = ChainPlanConnectionsMaterializer.project(edge, desired, map);
      if (projection.action() == ProjectionAction.CREATE) {
        dependencies.add(
            new ChainCatalogDependency(projection.fromElementId(), projection.toElementId()));
      }
    }
    return dependencies;
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
