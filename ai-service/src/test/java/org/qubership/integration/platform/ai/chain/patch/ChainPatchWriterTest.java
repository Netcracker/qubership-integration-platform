package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ConnectionsApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class ChainPatchWriterTest {

  private ChainPlanPropertiesMaterializer propertiesMaterializer;
  private ChainPlanSkeletonMaterializer skeletonMaterializer;
  private ChainPlanConnectionsMaterializer connectionsMaterializer;
  private ChainPlanRemovalsMaterializer removalsMaterializer;
  private CatalogRestClient catalogRestClient;
  private CatalogElementDescriptorLoader descriptorLoader;
  private ChainPatchWriter writer;

  @BeforeEach
  void setUp() {
    propertiesMaterializer = mock(ChainPlanPropertiesMaterializer.class);
    skeletonMaterializer = mock(ChainPlanSkeletonMaterializer.class);
    connectionsMaterializer = mock(ChainPlanConnectionsMaterializer.class);
    when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(1, List.of(), null));
    when(connectionsMaterializer.apply(any(), any()))
        .thenReturn(new ConnectionsApplyResult(1, List.of()));
    removalsMaterializer = mock(ChainPlanRemovalsMaterializer.class);
    lenient()
        .when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of(), List.of(), List.of(), null));
    catalogRestClient = mock(CatalogRestClient.class);
    descriptorLoader = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptorLoader);
    writer =
        new ChainPatchWriter(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader);
  }

  @Test
  void createsTheElementsThePatchAdds() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");

    ChainPatchWriteResult result = writer.write(chainWithAddedScript(), addScriptPatch());

    ArgumentCaptor<ChainPlanNode> created = ArgumentCaptor.forClass(ChainPlanNode.class);
    verify(skeletonMaterializer).materializeElement(any(), created.capture(), eq("chain-1"), any());
    assertEquals("node-new-script", created.getValue().nodeId());
    assertTrue(result.succeeded());
  }

  @Test
  void bindsANewElementToItsCatalogIdBeforeWritingItsProperties() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");

    writer.write(chainWithAddedScript(), addScriptPatch());

    ArgumentCaptor<MaterializationMap> map = ArgumentCaptor.forClass(MaterializationMap.class);
    verify(propertiesMaterializer).apply(any(), map.capture());
    assertEquals("catalog-new-script", map.getValue().nodeIdToElementId().get("node-new-script"));
  }

  @Test
  void writesTheWholeOfANewElementRatherThanPartOfIt() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");

    writer.write(chainWithAddedScript(), addScriptPatch());

    ChainPlanNode written = capturedGraph().nodes().get(0);
    assertEquals("node-new-script", written.nodeId());
    assertEquals("Enrich payload", written.label());
    assertEquals(List.of(new PlanProperty("script", "return 42")), written.properties());
  }

  @Test
  void connectsWhatThePatchConnects() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");

    writer.write(chainWithAddedScript(), addScriptPatch());

    ArgumentCaptor<ChainPlanGraph> graph = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(connectionsMaterializer).apply(graph.capture(), any());
    assertEquals(1, graph.getValue().edges().size());
    assertEquals("node-new-script", graph.getValue().edges().get(0).toNodeId());
    // Every node is present so the materializer can read the placement of both ends.
    assertEquals(3, graph.getValue().nodes().size());
  }

  @Test
  void reportsAnElementThatCouldNotBeCreated() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenThrow(new IllegalStateException("catalog refused"));

    ChainPatchWriteResult result = writer.write(chainWithAddedScript(), addScriptPatch());

    assertTrue(!result.succeeded());
    assertEquals(List.of("node-new-script"), result.failedElementIds());
    verify(connectionsMaterializer, org.mockito.Mockito.never()).apply(any(), any());
  }

  @Test
  void leavesConnectionsAloneWhenThePatchAddsNone() {
    writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    verify(connectionsMaterializer, org.mockito.Mockito.never()).apply(any(), any());
  }

  @Test
  void writesOnlyTheElementsThePatchNames() {
    writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    ChainPlanGraph written = capturedGraph();
    assertEquals(1, written.nodes().size());
    assertEquals("element-script", written.nodes().get(0).nodeId());
  }

  @Test
  void writesOnlyThePropertiesThePatchChanges() {
    writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    ChainPlanNode written = capturedGraph().nodes().get(0);
    assertEquals(
        List.of(new PlanProperty("script", "return 201")),
        written.properties());
  }

  @Test
  void leavesTheElementNameAloneWhenOnlyAPropertyChanged() {
    writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    assertNull(capturedGraph().nodes().get(0).label());
  }

  @Test
  void keepsTheChainBindingOfTheImportedChain() {
    writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    ArgumentCaptor<MaterializationMap> map = ArgumentCaptor.forClass(MaterializationMap.class);
    verify(propertiesMaterializer).apply(any(), map.capture());
    assertEquals("chain-1", map.getValue().chainId());
  }

  @Test
  void reportsTheElementsItChanged() {
    ChainPatchWriteResult result =
        writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    assertEquals(List.of("element-script"), result.changedElementIds());
    assertTrue(result.succeeded());
  }

  @Test
  void reportsAnElementThatCouldNotBeWritten() {
    when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(0, List.of("element-script"), "schema said no"));

    ChainPatchWriteResult result =
        writer.write(patchedChain(), patchOn("element-script", "script", "return 201"));

    assertEquals(List.of("element-script"), result.failedElementIds());
    assertEquals("schema said no", result.error());
    assertTrue(!result.succeeded());
  }

  @Test
  void writesNothingWhenThePatchChangesNoProperty() {
    ChainPatchWriteResult result =
        writer.write(
            patchedChain(),
            new GraphPatch("patch-1", "chain-patch", null, null, List.of(), null, List.of(), ""));

    verify(propertiesMaterializer, org.mockito.Mockito.never()).apply(any(), any());
    assertTrue(result.changedElementIds().isEmpty());
    assertTrue(result.succeeded());
  }

  @Test
  void connectsTwoElementsThatAlreadyExist() {
    // Nothing is added and no property changes -- the whole patch is one new connection between
    // elements the chain already has. The empty-patch guard must not swallow it.
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Order sync", "Syncs orders"),
            patchedChain().graph().nodes(),
            List.of(new ChainPlanEdge("edge-new", "element-trigger", "element-script", null)));
    PatchedChain patched = new PatchedChain(graph, patchedChain().materializationMap());

    ChainPatchWriteResult result =
        writer.write(
            patched,
            new GraphPatch(
                "patch-edge-only",
                "chain-patch",
                null,
                List.of(
                    new EdgePatch(
                        GraphPatchOperation.ADD,
                        new ChainPlanEdge("edge-new", "element-trigger", "element-script", null),
                        null)),
                List.of(),
                null,
                List.of(),
                "connects the trigger to the script"));

    ArgumentCaptor<ChainPlanGraph> connected = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(connectionsMaterializer).apply(connected.capture(), any());
    assertEquals(1, connected.getValue().edges().size());
    assertEquals("edge-new", connected.getValue().edges().get(0).edgeId());
    assertTrue(result.succeeded());
  }

  @Test
  void removesWhatThePatchRemoves() {
    PatchedChain patched =
        new PatchedChain(patchedChain().graph(), withoutScript(), patchedChain().materializationMap());

    ChainPatchWriteResult result = writer.write(patched, removeScriptPatch());

    ArgumentCaptor<java.util.Set<String>> nodeIds = ArgumentCaptor.forClass(java.util.Set.class);
    verify(removalsMaterializer).apply(any(), nodeIds.capture(), any(), any());
    assertEquals(java.util.Set.of("element-script"), nodeIds.getValue());
    assertTrue(result.succeeded());
  }

  @Test
  void removesOnlyAfterEverythingElseHasBeenWritten() {
    // Removal is the one step nothing can take back, so it must come after every step that can.
    // A patch that both reconfigures and removes puts the two in the same write to be ordered.
    PatchedChain patched =
        new PatchedChain(patchedChain().graph(), patchedChain().graph(), patchedChain().materializationMap());

    writer.write(patched, removeAndReconfigurePatch());

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(propertiesMaterializer, removalsMaterializer);
    order.verify(propertiesMaterializer).apply(any(), any());
    order.verify(removalsMaterializer).apply(any(), any(), any(), any());
  }

  @Test
  void doesNotRemoveWhenAnEarlierPhaseFailed() {
    when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(0, List.of("element-script"), "schema said no"));
    PatchedChain patched =
        new PatchedChain(patchedChain().graph(), patchedChain().graph(), patchedChain().materializationMap());

    writer.write(patched, removeAndReconfigurePatch());

    verify(removalsMaterializer, org.mockito.Mockito.never()).apply(any(), any(), any(), any());
  }

  @Test
  void reportsWhatTheRemovalActuallyTook() {
    when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of("element-script", "cascaded-child"), List.of("dep-1"), List.of(), List.of(), null));
    PatchedChain patched =
        new PatchedChain(patchedChain().graph(), withoutScript(), patchedChain().materializationMap());

    ChainPatchWriteResult result = writer.write(patched, removeScriptPatch());

    assertEquals(List.of("element-script", "cascaded-child"), result.removedElementIds());
  }

  @Test
  void deletesTheElementItCreatedWhenTheWriteFailsAfterwards() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");
    when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(0, List.of("node-new-script"), "schema said no"));

    ChainPatchWriteResult result = writer.write(chainWithAddedScript(), addScriptPatch());

    ArgumentCaptor<java.util.Set<String>> nodeIds = ArgumentCaptor.forClass(java.util.Set.class);
    verify(removalsMaterializer).apply(any(), nodeIds.capture(), any(), any());
    assertEquals(java.util.Set.of("node-new-script"), nodeIds.getValue());
    assertEquals(ChainPatchWriteResult.RollbackOutcome.COMPLETED, result.rollback());
  }

  @Test
  void putsBackThePropertyValueTheChainHeldBefore() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenThrow(new IllegalStateException("catalog said no"));

    ChainPatchWriteResult result =
        writer.write(chainWithAddedScript(), addScriptAndReconfigurePatch());

    ArgumentCaptor<ChainPlanGraph> written = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(propertiesMaterializer, org.mockito.Mockito.times(2)).apply(written.capture(), any());
    ChainPlanGraph restored = written.getAllValues().get(1);
    assertEquals("element-script", restored.nodes().get(0).nodeId());
    assertEquals("return 201", restored.nodes().get(0).properties().get(0).value());
    assertEquals(ChainPatchWriteResult.RollbackOutcome.COMPLETED, result.rollback());
  }

  /** The merge never deletes a key, so a key the patch introduced cannot be taken back off. */
  @Test
  void reportsAPartialRollbackWhenThePatchIntroducedThePropertyKey() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenThrow(new IllegalStateException("catalog said no"));

    ChainPatchWriteResult result =
        writer.write(chainWithAddedScript(), addScriptAndSetNewKeyPatch());

    assertEquals(ChainPatchWriteResult.RollbackOutcome.PARTIAL, result.rollback());
  }

  @Test
  void removesNothingWhenAConnectionCouldNotBeCreated() {
    // A patch that both connects and removes: a connection that did not land must stop the
    // removal, because a removal is the step no later failure can take back.
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");
    when(connectionsMaterializer.apply(any(), any()))
        .thenReturn(new ConnectionsApplyResult(0, List.of("edge-new")));

    ChainPatchWriteResult result = writer.write(chainWithAddedScript(), addScriptAndRemovePatch());

    verify(removalsMaterializer, org.mockito.Mockito.never())
        .apply(any(), eq(java.util.Set.of("element-script")), any(), any());
    assertTrue(!result.succeeded());
  }

  @Test
  void drawsBackTheConnectionsItCutWhenTheElementDeleteFails() {
    when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of("dep-1"), List.of("element-script"), List.of(), "catalog down"));

    ChainPatchWriteResult result = writer.write(connectedChain(), removeScriptAndItsEdgePatch());

    ArgumentCaptor<ChainPlanGraph> redrawn = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(connectionsMaterializer).apply(redrawn.capture(), any());
    assertEquals(1, redrawn.getValue().edges().size());
    assertEquals("edge-trigger-script", redrawn.getValue().edges().get(0).edgeId());
    assertEquals(ChainPatchWriteResult.RollbackOutcome.COMPLETED, result.rollback());
  }

  @Test
  void refusesToRollBackOnceAnElementIsGone() {
    when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of("element-script"),
                List.of("dep-1"),
                List.of("element-trigger"),
                List.of(),
                "catalog down"));

    ChainPatchWriteResult result = writer.write(connectedChain(), removeScriptAndItsEdgePatch());

    assertEquals(ChainPatchWriteResult.RollbackOutcome.REFUSED, result.rollback());
    verify(connectionsMaterializer, org.mockito.Mockito.never()).apply(any(), any());
  }

  @Test
  void leavesACleanWriteAlone() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-new-script");

    ChainPatchWriteResult result = writer.write(chainWithAddedScript(), addScriptPatch());

    assertTrue(result.succeeded());
    assertEquals(ChainPatchWriteResult.RollbackOutcome.NOT_ATTEMPTED, result.rollback());
    verify(removalsMaterializer, org.mockito.Mockito.never()).apply(any(), any(), any(), any());
  }

  @Test
  void replacesACatalogDependencyWhenAnUpdateChangesAnEndpoint() {
    ChainPatchWriteResult result = writer.write(chainWithRetargetedEdge(), retargetEdgePatch());

    ArgumentCaptor<Set<String>> removedNodes = ArgumentCaptor.forClass(Set.class);
    ArgumentCaptor<List<ChainPlanEdge>> removedEdges = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<ChainPlanGraph> created = ArgumentCaptor.forClass(ChainPlanGraph.class);
    org.mockito.InOrder order = inOrder(removalsMaterializer, connectionsMaterializer);
    order.verify(removalsMaterializer).apply(any(), removedNodes.capture(), removedEdges.capture(), any());
    order.verify(connectionsMaterializer).apply(created.capture(), any());
    assertTrue(removedNodes.getValue().isEmpty());
    assertEquals(
        List.of(new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-script", null)),
        removedEdges.getValue());
    assertEquals(
        List.of(new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-enrich", null)),
        created.getValue().edges());
    assertTrue(result.succeeded());
  }

  @Test
  void matchesTheCatalogDependencyByEndpointPairNotPlanEdgeId() {
    // Plan edge ids are synthesized on import and stay stable while endpoints change. The writer
    // must hand the old and new endpoint pairs to the materializers, not the plan edge id as a
    // catalog dependency id.
    writer.write(chainWithRetargetedEdge(), retargetEdgePatch());

    ArgumentCaptor<List<ChainPlanEdge>> removedEdges = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<ChainPlanGraph> created = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(removalsMaterializer).apply(any(), any(), removedEdges.capture(), any());
    verify(connectionsMaterializer).apply(created.capture(), any());
    ChainPlanEdge removed = removedEdges.getValue().get(0);
    ChainPlanEdge createdEdge = created.getValue().edges().get(0);
    assertEquals("edge-trigger-script", removed.edgeId());
    assertEquals("edge-trigger-script", createdEdge.edgeId());
    assertEquals("element-trigger", removed.fromNodeId());
    assertEquals("element-script", removed.toNodeId());
    assertEquals("element-trigger", createdEdge.fromNodeId());
    assertEquals("element-enrich", createdEdge.toNodeId());
  }

  @Test
  void writesAnUpdateOnlyPatchInsteadOfTreatingItAsEmpty() {
    ChainPatchWriteResult result = writer.write(chainWithRetargetedEdge(), retargetEdgePatch());

    verify(removalsMaterializer).apply(any(), any(), any(), any());
    verify(connectionsMaterializer).apply(any(), any());
    verify(propertiesMaterializer, never()).apply(any(), any());
    assertTrue(result.succeeded());
  }

  @Test
  void restoresTheRemovedDependencyWhenTheWriteFailsAfterDeletingIt() {
    when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of("dep-old"), List.of(), List.of(), null));
    when(connectionsMaterializer.apply(any(), any()))
        .thenReturn(new ConnectionsApplyResult(0, List.of("edge-trigger-script")))
        .thenReturn(new ConnectionsApplyResult(1, List.of()));

    ChainPatchWriteResult result = writer.write(chainWithRetargetedEdge(), retargetEdgePatch());

    ArgumentCaptor<ChainPlanGraph> graphs = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(connectionsMaterializer, org.mockito.Mockito.times(2)).apply(graphs.capture(), any());
    assertEquals("element-enrich", graphs.getAllValues().get(0).edges().get(0).toNodeId());
    assertEquals("element-script", graphs.getAllValues().get(1).edges().get(0).toNodeId());
    assertEquals(ChainPatchWriteResult.RollbackOutcome.COMPLETED, result.rollback());
    assertTrue(!result.succeeded());
  }

  @Test
  void skipsAStructuralParentToChildEdgeUpdate() {
    writer.write(chainWithRetargetedStructuralEdge(), retargetStructuralEdgePatch());

    verify(removalsMaterializer, never()).apply(any(), any(), any(), any());
    verify(connectionsMaterializer, never()).apply(any(), any());
  }

  @Test
  void skipsAnUpdateWhoseProjectedEndpointPairDidNotChange() {
    writer.write(chainWithAliasRetargetedEdge(), retargetEdgeToAliasPatch());

    verify(removalsMaterializer, never()).apply(any(), any(), any(), any());
    verify(connectionsMaterializer, never()).apply(any(), any());
  }

  @Test
  void failsAnUpdateWhoseNewEndpointsCannotBeProjected() {
    ChainPatchWriteResult result =
        writer.write(chainWithRetargetedEdgeMissingToInMap(), retargetEdgePatch());

    assertFalse(result.succeeded());
    verify(removalsMaterializer, never()).apply(any(), any(), any(), any());
    verify(connectionsMaterializer, never()).apply(any(), any());
  }

  /**
   * A model that adds a branch and its contents does not reliably name the branch first, and the
   * catalog cannot attach a child to a parent that does not exist yet.
   */
  @Test
  void createsAContainerBeforeTheChildItHolds() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-id");

    writer.write(chainWithBranchListedAfterItsChild(), addBranchAndChildPatch());

    ArgumentCaptor<ChainPlanNode> created = ArgumentCaptor.forClass(ChainPlanNode.class);
    verify(skeletonMaterializer, org.mockito.Mockito.times(2))
        .materializeElement(any(), created.capture(), eq("chain-1"), any());
    assertEquals(
        List.of("node-branch", "node-child"),
        created.getAllValues().stream().map(ChainPlanNode::nodeId).toList());
  }

  @Test
  void transfersAnExistingServiceCallUnderANewlyAddedTry2() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-try-2");
    stubCompatibleTry2AndServiceCallDescriptors();
    stubSuccessfulTransfer("catalog-service-call", "catalog-try-2");

    ChainPatchWriteResult result =
        writer.write(chainWrappingServiceCallInTry2(), wrapServiceCallInNewTry2Patch());

    ArgumentCaptor<CatalogTransferElementsRequest> request =
        ArgumentCaptor.forClass(CatalogTransferElementsRequest.class);
    org.mockito.InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).transferElements(eq("chain-1"), request.capture());
    order.verify(catalogRestClient).getElement("chain-1", "catalog-service-call");
    assertEquals("catalog-try-2", request.getValue().parentId());
    assertEquals(List.of("catalog-service-call"), request.getValue().elements());
    assertEquals(
        "catalog-service-call",
        result.materializationMap().nodeIdToElementId().get("element-service-call"));
    assertTrue(result.succeeded());
  }

  @Test
  void movesTwoConnectedElementsSharingAParentInOneTransfer() {
    stubCompatibleTry2AndServiceCallDescriptors();
    stubSuccessfulTransfer("catalog-call-a", "catalog-try-2");
    stubSuccessfulTransfer("catalog-call-b", "catalog-try-2");

    ChainPatchWriteResult result =
        writer.write(chainMovingTwoCallsUnderExistingTry2(), reparentTwoCallsUnderTry2Patch());

    ArgumentCaptor<CatalogTransferElementsRequest> request =
        ArgumentCaptor.forClass(CatalogTransferElementsRequest.class);
    verify(catalogRestClient, times(1)).transferElements(eq("chain-1"), request.capture());
    assertEquals("catalog-try-2", request.getValue().parentId());
    assertEquals(List.of("catalog-call-a", "catalog-call-b"), request.getValue().elements());
    assertTrue(result.succeeded());
  }

  @Test
  void failsBeforeTransferWhenTheDestinationIsNotAContainer() {
    when(descriptorLoader.load("try-2")).thenReturn(nonContainer("try-2"));
    when(descriptorLoader.load("service-call")).thenReturn(leaf("service-call"));

    ChainPatchWriteResult result =
        writer.write(chainReparentingServiceCallUnderExistingTry2(), reparentServiceCallUnderTry2Patch());

    assertFalse(result.succeeded());
    verify(catalogRestClient, never()).transferElements(any(), any());
  }

  @Test
  void failsWhenTransferLeavesTheElementUnderItsOldParent() {
    stubCompatibleTry2AndServiceCallDescriptors();
    when(catalogRestClient.transferElements(eq("chain-1"), any()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(),
                List.of(),
                List.of(
                    new CatalogRestClient.DependencySummaryDto(
                        "dep-created", "catalog-service-call", "catalog-try-2"))));
    when(catalogRestClient.getElement("chain-1", "catalog-service-call"))
        .thenReturn(catalogElement("catalog-service-call", null));

    ChainPatchWriteResult result =
        writer.write(chainReparentingServiceCallUnderExistingTry2(), reparentServiceCallUnderTry2Patch());

    verify(catalogRestClient).transferElements(eq("chain-1"), any());
    verify(catalogRestClient).getElement("chain-1", "catalog-service-call");
    assertFalse(result.succeeded());
    assertEquals(
        "Cannot transfer element 'catalog-service-call' under 'catalog-try-2': catalog parent is still 'null'.",
        result.error());
  }

  @Test
  void deletesBlockingDependenciesThenTransfersThenCreatesDesiredDependencies() {
    when(skeletonMaterializer.materializeElement(any(), any(), eq("chain-1"), any()))
        .thenReturn("catalog-try-2");
    stubCompatibleTry2AndServiceCallDescriptors();
    stubSuccessfulTransfer("catalog-service-call", "catalog-try-2");

    ChainPatchWriteResult result =
        writer.write(
            chainWrappingServiceCallAndRetargetingTrigger(),
            wrapServiceCallAndRetargetTriggerPatch());

    org.mockito.InOrder order =
        inOrder(removalsMaterializer, catalogRestClient, connectionsMaterializer);
    order.verify(removalsMaterializer).apply(any(), any(), any(), any());
    order.verify(catalogRestClient).transferElements(eq("chain-1"), any());
    order.verify(connectionsMaterializer).apply(any(), any());
    assertTrue(result.succeeded());
  }

  @Test
  void writesAParentOnlyUpdateInsteadOfTreatingItAsEmpty() {
    stubCompatibleTry2AndServiceCallDescriptors();
    stubSuccessfulTransfer("catalog-service-call", "catalog-try-2");

    ChainPatchWriteResult result =
        writer.write(chainReparentingServiceCallUnderExistingTry2(), reparentServiceCallUnderTry2Patch());

    verify(catalogRestClient).transferElements(eq("chain-1"), any());
    verify(propertiesMaterializer, never()).apply(any(), any());
    assertTrue(result.succeeded());
  }

  /** The graph lists the child first, exactly as the patch named them. */
  private static PatchedChain chainWithBranchListedAfterItsChild() {
    PatchedChain base = patchedChain();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            base.graph().schemaVersion(),
            base.graph().chain(),
            List.of(
                base.graph().nodes().get(0),
                new ChainPlanNode("node-child", "script", "Tag", "node-branch", null, List.of()),
                new ChainPlanNode("node-branch", "if", "Bulk", null, null, List.of())),
            List.of());
    return new PatchedChain(graph, base.materializationMap());
  }

  private static GraphPatch addBranchAndChildPatch() {
    return new GraphPatch(
        "patch-nested",
        "chain-patch",
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode("node-child", "script", "Tag", "node-branch", null, List.of()),
                null),
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode("node-branch", "if", "Bulk", null, null, List.of()),
                null)),
        List.of(),
        List.of(),
        null,
        List.of(),
        "adds a branch and what it holds");
  }

  private ChainPlanGraph capturedGraph() {
    ArgumentCaptor<ChainPlanGraph> graph = ArgumentCaptor.forClass(ChainPlanGraph.class);
    verify(propertiesMaterializer).apply(graph.capture(), any());
    return graph.getValue();
  }

  /** The chain after the patch was applied: the script node already carries the new body. */
  private static PatchedChain patchedChain() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Order sync", "Syncs orders"),
            List.of(
                new ChainPlanNode(
                    "element-trigger", "http-trigger", "Receive order", null, null, List.of()),
                new ChainPlanNode(
                    "element-script",
                    "script",
                    "Normalize payload",
                    null,
                    null,
                    List.of(
                        new PlanProperty("script", "return 201"),
                        new PlanProperty("connectTimeout", "30000")))),
            List.of());
    return new PatchedChain(
        graph,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "element-trigger", "element-trigger",
                "element-script", "element-script")));
  }

  /** The chain after a patch that adds one script and wires the trigger to it. */
  private static PatchedChain chainWithAddedScript() {
    PatchedChain base = patchedChain();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            base.graph().schemaVersion(),
            base.graph().chain(),
            List.of(
                base.graph().nodes().get(0),
                base.graph().nodes().get(1),
                new ChainPlanNode(
                    "node-new-script",
                    "script",
                    "Enrich payload",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 42")))),
            List.of(
                new ChainPlanEdge(
                    "edge-new", "element-trigger", "node-new-script", null)));
    return new PatchedChain(graph, base.materializationMap());
  }

  private static GraphPatch addScriptPatch() {
    return new GraphPatch(
        "patch-2",
        "chain-patch",
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(
                    "node-new-script",
                    "script",
                    "Enrich payload",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 42"))),
                null)),
        List.of(
            new EdgePatch(
                GraphPatchOperation.ADD,
                new ChainPlanEdge("edge-new", "element-trigger", "node-new-script", null),
                null)),
        List.of(),
        null,
        List.of(),
        "adds an enrichment step");
  }

  /** Adds one script and rewrites a property the chain already had, so a rollback has both to do. */
  private static GraphPatch addScriptAndReconfigurePatch() {
    GraphPatch base = addScriptPatch();
    return new GraphPatch(
        base.patchId(),
        base.ownerCapabilityId(),
        base.nodePatches(),
        base.edgePatches(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, "element-script", new PlanProperty("script", "return 9"))),
        null,
        List.of(),
        base.rationale());
  }

  /** Same, but the property key is one the element did not have before. */
  private static GraphPatch addScriptAndSetNewKeyPatch() {
    GraphPatch base = addScriptPatch();
    return new GraphPatch(
        base.patchId(),
        base.ownerCapabilityId(),
        base.nodePatches(),
        base.edgePatches(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, "element-script", new PlanProperty("language", "groovy"))),
        null,
        List.of(),
        base.rationale());
  }

  /** Adds one script, connects it, and removes another element in the same patch. */
  private static GraphPatch addScriptAndRemovePatch() {
    GraphPatch base = addScriptPatch();
    return new GraphPatch(
        base.patchId(),
        base.ownerCapabilityId(),
        List.of(
            base.nodePatches().get(0),
            new NodePatch(GraphPatchOperation.REMOVE, null, "element-script")),
        base.edgePatches(),
        List.of(),
        null,
        List.of(),
        base.rationale());
  }

  /** A chain whose trigger and script are wired together, so an edge exists to be cut. */
  private static PatchedChain connectedChain() {
    ChainPlanGraph base = patchedChain().graph();
    ChainPlanGraph before =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            base.nodes(),
            List.of(new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-script", null)));
    return new PatchedChain(before, withoutScript(), patchedChain().materializationMap());
  }

  private static GraphPatch removeScriptAndItsEdgePatch() {
    return new GraphPatch(
        "patch-remove-3",
        "chain-patch",
        List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "element-script")),
        List.of(new EdgePatch(GraphPatchOperation.REMOVE, null, "edge-trigger-script")),
        List.of(),
        null,
        List.of(),
        "removes the normalize step and the connection into it");
  }

  /** The chain after the script element was removed from it. */
  private static ChainPlanGraph withoutScript() {
    ChainPlanGraph base = patchedChain().graph();
    return new ChainPlanGraph(
        base.schemaVersion(), base.chain(), List.of(base.nodes().get(0)), List.of());
  }

  private static GraphPatch removeScriptPatch() {
    return new GraphPatch(
        "patch-remove",
        "chain-patch",
        List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "element-script")),
        List.of(),
        List.of(),
        null,
        List.of(),
        "removes the normalize step");
  }

  private static GraphPatch removeAndReconfigurePatch() {
    return new GraphPatch(
        "patch-remove-2",
        "chain-patch",
        List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "element-trigger")),
        List.of(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, "element-script", new PlanProperty("script", "return 9"))),
        null,
        List.of(),
        "reconfigures one element and removes another");
  }

  /** Trigger already wired to the script; the patch retargets that same plan edge onto enrich. */
  private static PatchedChain chainWithRetargetedEdge() {
    ChainPlanGraph base = patchedChain().graph();
    List<ChainPlanNode> nodes =
        List.of(
            base.nodes().get(0),
            base.nodes().get(1),
            new ChainPlanNode("element-enrich", "script", "Enrich payload", null, null, List.of()));
    ChainPlanGraph before =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(
                new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-script", null)));
    ChainPlanGraph after =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(
                new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-enrich", null)));
    return new PatchedChain(
        before,
        after,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "element-trigger", "element-trigger",
                "element-script", "element-script",
                "element-enrich", "element-enrich")));
  }

  private static GraphPatch retargetEdgePatch() {
    return new GraphPatch(
        "patch-update-edge",
        "chain-patch",
        null,
        List.of(
            new EdgePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-enrich", null),
                "edge-trigger-script")),
        List.of(),
        null,
        List.of(),
        "retargets the trigger onto the enrich step");
  }

  /**
   * Same retarget as {@link #chainWithRetargetedEdge()}, but the new {@code to} node has no catalog
   * id, so the after-side projection is {@code FAIL_INVALID}.
   */
  private static PatchedChain chainWithRetargetedEdgeMissingToInMap() {
    PatchedChain patched = chainWithRetargetedEdge();
    return new PatchedChain(
        patched.before(),
        patched.graph(),
        new MaterializationMap(
            patched.materializationMap().chainId(),
            Map.of(
                "element-trigger", "element-trigger",
                "element-script", "element-script")));
  }

  /**
   * Same catalog endpoints after the update: plan node ids change, materialization maps both pairs
   * onto the same catalog elements.
   */
  private static PatchedChain chainWithAliasRetargetedEdge() {
    ChainPlanGraph base = patchedChain().graph();
    List<ChainPlanNode> nodes =
        List.of(
            base.nodes().get(0),
            base.nodes().get(1),
            new ChainPlanNode("alias-trigger", "http-trigger", "Receive order", null, null, List.of()),
            new ChainPlanNode("alias-script", "script", "Normalize payload", null, null, List.of()));
    ChainPlanGraph before =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(
                new ChainPlanEdge("edge-trigger-script", "element-trigger", "element-script", null)));
    ChainPlanGraph after =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(new ChainPlanEdge("edge-trigger-script", "alias-trigger", "alias-script", null)));
    return new PatchedChain(
        before,
        after,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "element-trigger", "catalog-trigger",
                "element-script", "catalog-script",
                "alias-trigger", "catalog-trigger",
                "alias-script", "catalog-script")));
  }

  private static GraphPatch retargetEdgeToAliasPatch() {
    return new GraphPatch(
        "patch-update-alias",
        "chain-patch",
        null,
        List.of(
            new EdgePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanEdge("edge-trigger-script", "alias-trigger", "alias-script", null),
                "edge-trigger-script")),
        List.of(),
        null,
        List.of(),
        "rewrites plan endpoints that already map to the same catalog pair");
  }

  private static PatchedChain chainWithRetargetedStructuralEdge() {
    ChainPlanGraph base = patchedChain().graph();
    List<ChainPlanNode> nodes =
        List.of(
            new ChainPlanNode("element-if", "if", "Bulk", null, null, List.of()),
            new ChainPlanNode("element-child-a", "script", "Tag A", "element-if", null, List.of()),
            new ChainPlanNode("element-child-b", "script", "Tag B", "element-if", null, List.of()));
    ChainPlanGraph before =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(new ChainPlanEdge("edge-if-child", "element-if", "element-child-a", null)));
    ChainPlanGraph after =
        new ChainPlanGraph(
            base.schemaVersion(),
            base.chain(),
            nodes,
            List.of(new ChainPlanEdge("edge-if-child", "element-if", "element-child-b", null)));
    return new PatchedChain(
        before,
        after,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "element-if", "element-if",
                "element-child-a", "element-child-a",
                "element-child-b", "element-child-b")));
  }

  private static GraphPatch retargetStructuralEdgePatch() {
    return new GraphPatch(
        "patch-update-structural",
        "chain-patch",
        null,
        List.of(
            new EdgePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanEdge("edge-if-child", "element-if", "element-child-b", null),
                "edge-if-child")),
        List.of(),
        null,
        List.of(),
        "retargets a parent-to-child placement edge");
  }

  private static GraphPatch patchOn(String nodeId, String key, String value) {
    return new GraphPatch(
        "patch-1",
        "chain-patch",
        null,
        null,
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, nodeId, new PlanProperty(key, value))),
        null,
        List.of(),
        "keeps the customer id");
  }

  private void stubCompatibleTry2AndServiceCallDescriptors() {
    when(descriptorLoader.load("try-2")).thenReturn(container("try-2"));
    when(descriptorLoader.load("service-call")).thenReturn(leaf("service-call"));
  }

  private void stubSuccessfulTransfer(String elementId, String parentId) {
    lenient()
        .when(catalogRestClient.transferElements(eq("chain-1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));
    when(catalogRestClient.getElement("chain-1", elementId))
        .thenReturn(catalogElement(elementId, parentId));
  }

  private static CatalogElementResponseDto catalogElement(String id, String parentId) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.parentElementId = parentId;
    return dto;
  }

  private static CatalogElementDescriptor container(String type) {
    return new CatalogElementDescriptor(
        type, true, Map.of(), List.of(), false, "priority", false, false, false, true);
  }

  private static CatalogElementDescriptor leaf(String type) {
    return new CatalogElementDescriptor(
        type, false, Map.of(), List.of(), false, "priority", false, false, false, true);
  }

  private static CatalogElementDescriptor nonContainer(String type) {
    return leaf(type);
  }

  private static PatchedChain chainWrappingServiceCallInTry2() {
    return new PatchedChain(
        serviceCallAtRootGraph(List.of()),
        serviceCallUnderTry2Graph(List.of()),
        serviceCallMaterializationMap());
  }

  private static GraphPatch wrapServiceCallInNewTry2Patch() {
    return new GraphPatch(
        "patch-wrap-try2",
        "chain-patch",
        List.of(addTry2Patch(), reparentServiceCallNodePatch()),
        List.of(),
        List.of(),
        null,
        List.of(),
        "wraps the service call in a generated try-2");
  }

  private static PatchedChain chainReparentingServiceCallUnderExistingTry2() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Order sync", "Syncs orders"),
            List.of(triggerNode(), serviceCallNode(null), try2Node()),
            List.of());
    ChainPlanGraph after =
        new ChainPlanGraph(
            before.schemaVersion(),
            before.chain(),
            List.of(triggerNode(), serviceCallNode("node-try-2"), try2Node()),
            List.of());
    return new PatchedChain(
        before,
        after,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "element-trigger", "element-trigger",
                "element-service-call", "catalog-service-call",
                "node-try-2", "catalog-try-2")));
  }

  private static GraphPatch reparentServiceCallUnderTry2Patch() {
    return new GraphPatch(
        "patch-reparent",
        "chain-patch",
        List.of(reparentServiceCallNodePatch()),
        List.of(),
        List.of(),
        null,
        List.of(),
        "moves the service call under try-2");
  }

  private static PatchedChain chainMovingTwoCallsUnderExistingTry2() {
    ChainPlanNode callA =
        new ChainPlanNode("element-call-a", "service-call", "Call A", "node-try-2", null, List.of());
    ChainPlanNode callB =
        new ChainPlanNode("element-call-b", "service-call", "Call B", "node-try-2", null, List.of());
    ChainPlanNode callABefore =
        new ChainPlanNode("element-call-a", "service-call", "Call A", null, null, List.of());
    ChainPlanNode callBBefore =
        new ChainPlanNode("element-call-b", "service-call", "Call B", null, null, List.of());
    ChainPlanEdge internal =
        new ChainPlanEdge("edge-a-b", "element-call-a", "element-call-b", null);
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Order sync", "Syncs orders"),
            List.of(try2Node(), callABefore, callBBefore),
            List.of(internal));
    ChainPlanGraph after =
        new ChainPlanGraph(
            before.schemaVersion(),
            before.chain(),
            List.of(try2Node(), callA, callB),
            List.of(internal));
    return new PatchedChain(
        before,
        after,
        new MaterializationMap(
            "chain-1",
            Map.of(
                "node-try-2", "catalog-try-2",
                "element-call-a", "catalog-call-a",
                "element-call-b", "catalog-call-b")));
  }

  private static GraphPatch reparentTwoCallsUnderTry2Patch() {
    return new GraphPatch(
        "patch-reparent-two",
        "chain-patch",
        List.of(
            new NodePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanNode(
                    "element-call-a", "service-call", "Call A", "node-try-2", null, List.of()),
                "element-call-a"),
            new NodePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanNode(
                    "element-call-b", "service-call", "Call B", "node-try-2", null, List.of()),
                "element-call-b")),
        List.of(),
        List.of(),
        null,
        List.of(),
        "moves two connected calls under the same try-2");
  }

  private static PatchedChain chainWrappingServiceCallAndRetargetingTrigger() {
    ChainPlanGraph before = serviceCallAtRootGraph(
        List.of(new ChainPlanEdge("edge-trigger-call", "element-trigger", "element-service-call", null)));
    ChainPlanGraph after = serviceCallUnderTry2Graph(
        List.of(new ChainPlanEdge("edge-trigger-call", "element-trigger", "node-try-2", null)));
    return new PatchedChain(before, after, serviceCallMaterializationMap());
  }

  private static GraphPatch wrapServiceCallAndRetargetTriggerPatch() {
    return new GraphPatch(
        "patch-wrap-retarget",
        "chain-patch",
        List.of(addTry2Patch(), reparentServiceCallNodePatch()),
        List.of(
            new EdgePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanEdge("edge-trigger-call", "element-trigger", "node-try-2", null),
                "edge-trigger-call")),
        List.of(),
        null,
        List.of(),
        "wraps the service call and retargets the trigger onto try-2");
  }

  private static ChainPlanGraph serviceCallAtRootGraph(List<ChainPlanEdge> edges) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", "Syncs orders"),
        List.of(triggerNode(), serviceCallNode(null)),
        edges);
  }

  private static ChainPlanGraph serviceCallUnderTry2Graph(List<ChainPlanEdge> edges) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", "Syncs orders"),
        List.of(triggerNode(), serviceCallNode("node-try-2"), try2Node()),
        edges);
  }

  private static MaterializationMap serviceCallMaterializationMap() {
    return new MaterializationMap(
        "chain-1",
        Map.of(
            "element-trigger", "element-trigger",
            "element-service-call", "catalog-service-call"));
  }

  private static NodePatch addTry2Patch() {
    return new NodePatch(GraphPatchOperation.ADD, try2Node(), null);
  }

  private static NodePatch reparentServiceCallNodePatch() {
    return new NodePatch(
        GraphPatchOperation.UPDATE, serviceCallNode("node-try-2"), "element-service-call");
  }

  private static ChainPlanNode triggerNode() {
    return new ChainPlanNode(
        "element-trigger", "http-trigger", "Receive order", null, null, List.of());
  }

  private static ChainPlanNode serviceCallNode(String parentNodeId) {
    return new ChainPlanNode(
        "element-service-call", "service-call", "Call orders", parentNodeId, null, List.of());
  }

  private static ChainPlanNode try2Node() {
    return new ChainPlanNode("node-try-2", "try-2", "Try", null, null, List.of());
  }
}
