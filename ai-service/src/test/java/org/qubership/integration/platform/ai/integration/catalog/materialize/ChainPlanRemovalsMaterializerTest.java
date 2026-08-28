package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class ChainPlanRemovalsMaterializerTest {

    @Mock
    private CatalogRestClient catalogRestClient;

    private ChainPlanRemovalsMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new ChainPlanRemovalsMaterializer(catalogRestClient);
        lenient().when(catalogRestClient.listDependencies(any())).thenReturn(List.of());
    }

    @Test
    void writesNothingWhenNothingIsRemoved() {
        ChainPlanRemovalsMaterializer.RemovalsApplyResult result =
                materializer.apply(graph(List.of(), List.of()), Set.of(), List.of(), map(Map.of()));

        assertTrue(result.succeeded());
        assertTrue(result.removedElementIds().isEmpty());
        verify(catalogRestClient, never()).deleteElements(any(), any());
        verify(catalogRestClient, never()).deleteDependencies(any(), any());
    }

    @Test
    void resolvesRemovedEdgeToLiveDependencyId() {
        CatalogDependencyDto live = dependency("dep-1", "el-a", "el-b");
        when(catalogRestClient.listDependencies("chain-1")).thenReturn(List.of(live));

        ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
                graph(List.of(node("a", null), node("b", null)), List.of(edge("a->b", "a", "b"))),
                Set.of(),
                List.of(edge("a->b", "a", "b")),
                map(Map.of("a", "el-a", "b", "el-b")));

        assertTrue(result.succeeded());
        assertEquals(List.of("dep-1"), result.removedDependencyIds());
        verify(catalogRestClient).deleteDependencies(eq("chain-1"), eq(List.of("dep-1")));
    }

    @Test
    void treatsEdgeWithNoLiveDependencyAsDone() {
        when(catalogRestClient.listDependencies("chain-1"))
                .thenReturn(List.of(dependency("dep-other", "el-x", "el-y")));

        ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
                graph(List.of(node("a", null), node("b", null)), List.of(edge("a->b", "a", "b"))),
                Set.of(),
                List.of(edge("a->b", "a", "b")),
                map(Map.of("a", "el-a", "b", "el-b")));

        assertTrue(result.succeeded());
        assertTrue(result.removedDependencyIds().isEmpty());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).deleteDependencies(any(), any());
    }

    @Test
    void sendsOnlyRemovalRootsSoTheCascadeTakesTheRest() {
        ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
                graph(
                        List.of(node("container", null), node("child", "container"), node("grandchild", "child")),
                        List.of()),
                Set.of("container", "child", "grandchild"),
                List.of(),
                map(Map.of("container", "el-container", "child", "el-child", "grandchild", "el-grandchild")));

        assertTrue(result.succeeded());
        verify(catalogRestClient).deleteElements(eq("chain-1"), eq(List.of("el-container")));
        assertEquals(
                Set.of("el-container", "el-child", "el-grandchild"),
                Set.copyOf(result.removedElementIds()));
    }

    @Test
    void keepsElementsWhenTheDependencyDeleteFails() {
        when(catalogRestClient.listDependencies("chain-1"))
                .thenReturn(List.of(dependency("dep-1", "el-a", "el-b")));
        when(catalogRestClient.deleteDependencies(any(), any()))
                .thenThrow(new RuntimeException("catalog down"));

        ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
                graph(List.of(node("a", null), node("b", null)), List.of(edge("a->b", "a", "b"))),
                Set.of("b"),
                List.of(edge("a->b", "a", "b")),
                map(Map.of("a", "el-a", "b", "el-b")));

        assertFalse(result.succeeded());
        assertEquals(List.of("a->b"), result.failedEdgeIds());
        assertEquals("catalog down", result.error());
        verify(catalogRestClient, never()).deleteElements(any(), any());
    }

    @Test
    void failsEveryRemovedNodeWhenTheElementDeleteFails() {
        when(catalogRestClient.deleteElements(any(), any())).thenThrow(new RuntimeException("catalog down"));

        ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
                graph(List.of(node("a", null), node("b", null)), List.of()),
                Set.of("b"),
                List.of(),
                map(Map.of("a", "el-a", "b", "el-b")));

        assertFalse(result.succeeded());
        assertEquals(List.of("b"), result.failedNodeIds());
        assertTrue(result.removedElementIds().isEmpty());
        assertEquals("catalog down", result.error());
    }

  @Test
  void failsWhenDependencyListingFails() {
    when(catalogRestClient.listDependencies("chain-1")).thenThrow(new RuntimeException("catalog down"));

    ChainPlanRemovalsMaterializer.RemovalsApplyResult result = materializer.apply(
        graph(List.of(node("a", null), node("b", null)), List.of(edge("a->b", "a", "b"))),
        Set.of(),
        List.of(edge("a->b", "a", "b")),
        map(Map.of("a", "el-a", "b", "el-b")));

    assertFalse(result.succeeded());
    assertEquals(List.of("a->b"), result.failedEdgeIds());
    assertEquals("catalog down", result.error());
    verify(catalogRestClient, never()).deleteDependencies(any(), any());
  }

    private static MaterializationMap map(Map<String, String> nodeIdToElementId) {
        return new MaterializationMap("chain-1", nodeIdToElementId, Map.of(), Map.of());
    }

    private static ChainPlanGraph graph(List<ChainPlanNode> nodes, List<ChainPlanEdge> edges) {
        return new ChainPlanGraph("1.0", new ChainSection("demo-chain", null), nodes, edges);
    }

    private static ChainPlanNode node(String nodeId, String parentNodeId) {
        return new ChainPlanNode(nodeId, "script", nodeId, parentNodeId, null, List.of());
    }

    private static ChainPlanEdge edge(String edgeId, String from, String to) {
        return new ChainPlanEdge(edgeId, from, to, null);
    }

    private static CatalogDependencyDto dependency(String id, String from, String to) {
        CatalogDependencyDto dependency = new CatalogDependencyDto();
        dependency.id = id;
        dependency.from = from;
        dependency.to = to;
        return dependency;
    }
}
