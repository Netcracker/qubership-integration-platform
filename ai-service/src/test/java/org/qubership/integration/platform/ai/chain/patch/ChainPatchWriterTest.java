package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    writer =
        new ChainPatchWriter(
            propertiesMaterializer, skeletonMaterializer, connectionsMaterializer);
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
