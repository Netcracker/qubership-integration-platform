package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class ChainPlanConnectionsMaterializerTest {

    @Mock
    private CatalogRestClient catalogRestClient;

    private ChainPlanConnectionsMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new ChainPlanConnectionsMaterializer(catalogRestClient);
        lenient().when(catalogRestClient.listDependencies(any())).thenReturn(List.of());
    }

    @Test
    void skipsExistingDependency() {
        ChainPlanGraph graph = new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "http-sender", "Sender", null, null, List.of())),
                List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1", "n2", "el-2"));
        CatalogDependencyDto existing = new CatalogDependencyDto();
        existing.from = "el-1";
        existing.to = "el-2";
        when(catalogRestClient.listDependencies("chain-1")).thenReturn(List.of(existing));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void failsCreatableEdgesWhenListDependenciesFails() {
        when(catalogRestClient.listDependencies("chain-1")).thenThrow(new RuntimeException("catalog down"));

        ChainPlanGraph graph = new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "http-sender", "Sender", null, null, List.of())),
                List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1", "n2", "el-2"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertEquals(List.of("e1"), result.failedEdgeIds());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void createsDependencyFromMappedElementIds() {
        when(catalogRestClient.createConnection(any(), any()))
                .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

        ChainPlanGraph graph = new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "http-sender", "Sender", null, null, List.of())),
                List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1", "n2", "el-2"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(1, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient)
                .createConnection(eq("chain-1"), eq(new CatalogCreateDependencyRequest("el-1", "el-2")));
    }

    @Test
    void createsRootDependencyToContainerWrapper() {
        when(catalogRestClient.createConnection(any(), any()))
                .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

        ChainPlanGraph graph = graph(
                List.of(
                        node("trigger", "http-trigger", null),
                        node("tcff", "try-catch-finally-2", null)),
                List.of(edge("e1", "trigger", "tcff")));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("trigger", "el-trigger", "tcff", "el-tcff"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(1, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient)
                .createConnection(
                        eq("chain-1"),
                        eq(new CatalogCreateDependencyRequest("el-trigger", "el-tcff")));
    }

    @Test
    void skipsConditionBranchEntryEdges() {
        ChainPlanGraph graph = graph(
                List.of(
                        node("condition", "condition", null),
                        node("if-branch", "if", "condition"),
                        node("call", "service-call", "if-branch")),
                List.of(
                        edge("e1", "condition", "if-branch"),
                        edge("e2", "if-branch", "call")));
        MaterializationMap map = new MaterializationMap(
                "chain-1",
                Map.of("condition", "el-cond", "if-branch", "el-if", "call", "el-call"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void createsDependencyBetweenWorkflowChildrenUnderSameIf() {
        when(catalogRestClient.createConnection(any(), any()))
                .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

        ChainPlanGraph graph = graph(
                List.of(
                        node("if-branch", "if", "condition"),
                        node("script", "script", "if-branch"),
                        node("call", "service-call", "if-branch")),
                List.of(edge("e1", "script", "call")));
        MaterializationMap map = new MaterializationMap(
                "chain-1", Map.of("if-branch", "el-if", "script", "el-script", "call", "el-call"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(1, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient)
                .createConnection(
                        eq("chain-1"),
                        eq(new CatalogCreateDependencyRequest("el-script", "el-call")));
    }

    @Test
    void skipsTryAndCatchBranchEntryEdges() {
        ChainPlanGraph graph = graph(
                List.of(
                        node("try", "try-2", "tcff"),
                        node("protected", "script", "try"),
                        node("catch", "catch-2", "tcff"),
                        node("catch-script", "script", "catch")),
                List.of(
                        edge("e1", "try", "protected"),
                        edge("e2", "catch", "catch-script")));
        MaterializationMap map = new MaterializationMap(
                "chain-1",
                Map.of(
                        "try", "el-try",
                        "protected", "el-protected",
                        "catch", "el-catch",
                        "catch-script", "el-catch-script"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void createsDependencyBetweenWorkflowChildrenUnderTry() {
        when(catalogRestClient.createConnection(any(), any()))
                .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

        ChainPlanGraph graph = graph(
                List.of(
                        node("try", "try-2", "tcff"),
                        node("script", "script", "try"),
                        node("call", "service-call", "try")),
                List.of(edge("e1", "script", "call")));
        MaterializationMap map = new MaterializationMap(
                "chain-1", Map.of("try", "el-try", "script", "el-script", "call", "el-call"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(1, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient)
                .createConnection(
                        eq("chain-1"),
                        eq(new CatalogCreateDependencyRequest("el-script", "el-call")));
    }

    @Test
    void skipsSplitBranchEntryAndCreatesBranchLocalSequence() {
        when(catalogRestClient.createConnection(any(), any()))
                .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

        ChainPlanGraph graph = graph(
                List.of(
                        node("split-branch", "split-element-2", "split"),
                        node("branch-script", "script", "split-branch"),
                        node("call", "service-call", "split-branch")),
                List.of(
                        edge("e1", "split-branch", "branch-script"),
                        edge("e2", "branch-script", "call")));
        MaterializationMap map = new MaterializationMap(
                "chain-1",
                Map.of(
                        "split-branch", "el-split-branch",
                        "branch-script", "el-branch-script",
                        "call", "el-call"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(1, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient)
                .createConnection(
                        eq("chain-1"),
                        eq(new CatalogCreateDependencyRequest("el-branch-script", "el-call")));
    }

    @Test
    void skipsCrossScopeEdgeWithoutFailing() {
        ChainPlanGraph graph = graph(
                List.of(
                        node("trigger", "http-trigger", null),
                        node("try", "try-2", "tcff"),
                        node("protected", "script", "try")),
                List.of(edge("e-cross", "trigger", "protected")));
        MaterializationMap map = new MaterializationMap(
                "chain-1",
                Map.of("trigger", "el-trigger", "try", "el-try", "protected", "el-protected"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void skipsCatchToTryContentEdgeWithoutFailing() {
        ChainPlanGraph graph = graph(
                List.of(
                        node("try", "try-2", "tcff"),
                        node("catch", "catch-2", "tcff"),
                        node("call", "service-call", "try")),
                List.of(edge("e3", "catch", "call")));
        MaterializationMap map = new MaterializationMap(
                "chain-1",
                Map.of("try", "el-try", "catch", "el-catch", "call", "el-call"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertTrue(result.failedEdgeIds().isEmpty());
        verify(catalogRestClient, never()).createConnection(any(), any());
    }

    @Test
    void failsWhenEdgeReferencesUnknownPlanNodeId() {
        ChainPlanGraph graph = new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
                List.of(new ChainPlanEdge("e1", "n1", "missing", null)));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertEquals(List.of("e1"), result.failedEdgeIds());
    }

    @Test
    void recordsFailedEdgeWhenCatalogThrows() {
        doThrow(new RuntimeException("catalog down"))
                .when(catalogRestClient)
                .createConnection(any(), any());

        ChainPlanGraph graph = new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "http-sender", "Sender", null, null, List.of())),
                List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
        MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1", "n2", "el-2"));

        ChainPlanConnectionsMaterializer.ConnectionsApplyResult result = materializer.apply(graph, map);

        assertEquals(0, result.createdCount());
        assertEquals(List.of("e1"), result.failedEdgeIds());
    }

    private static ChainPlanGraph graph(List<ChainPlanNode> nodes, List<ChainPlanEdge> edges) {
        return new ChainPlanGraph("1.0", new ChainSection("demo-chain", null), nodes, edges);
    }

    private static ChainPlanNode node(String nodeId, String type, String parentNodeId) {
        return new ChainPlanNode(nodeId, type, nodeId, parentNodeId, null, List.of());
    }

    private static ChainPlanEdge edge(String edgeId, String from, String to) {
        return new ChainPlanEdge(edgeId, from, to, null);
    }
}
