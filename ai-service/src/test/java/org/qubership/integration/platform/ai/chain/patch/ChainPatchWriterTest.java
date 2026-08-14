package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ConnectionsApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
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
    writer =
        new ChainPatchWriter(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer);
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
}
