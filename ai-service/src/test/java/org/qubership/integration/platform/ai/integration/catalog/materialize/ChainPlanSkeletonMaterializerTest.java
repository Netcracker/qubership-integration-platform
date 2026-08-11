package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class ChainPlanSkeletonMaterializerTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private CatalogRestClient catalogRestClient;

  private ChainPlanSkeletonMaterializer materializer;

  @BeforeEach
  void setUp() {
    materializer = new ChainPlanSkeletonMaterializer(catalogRestClient);
  }

  @Test
  void reusesAutoShellChildrenForContainerWithoutRecreating() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto(
                        "el-tcff", "try-catch-finally-2", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("http-trigger", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("el-trigger", "http-trigger", Map.of())),
                List.of(),
                List.of()));

    CatalogElementResponseDto tcff = containerWithShells();
    when(catalogRestClient.getElement(CHAIN_ID, "el-tcff")).thenReturn(tcff);

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "tcff", null, List.of()),
                new ChainPlanNode("finally", "finally-2", "Finally", "tcff", null, List.of()),
                new ChainPlanNode("trigger", "http-trigger", "Trigger", "try", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals(CHAIN_ID, map.chainId());
    assertEquals("el-tcff", map.nodeIdToElementId().get("tcff"));
    assertEquals("el-try", map.nodeIdToElementId().get("try"));
    assertEquals("el-catch", map.nodeIdToElementId().get("catch"));
    assertEquals("el-finally", map.nodeIdToElementId().get("finally"));
    assertEquals("el-trigger", map.nodeIdToElementId().get("trigger"));

    verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID),
            eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null)));
    verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID),
            eq(new CatalogCreateElementRequest("http-trigger", null, null)));
    verify(catalogRestClient).getElement(CHAIN_ID, "el-tcff");
  }

  @Test
  void createsElementsInParentBeforeChildOrder() {
    when(catalogRestClient.createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class)))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-parent", "try-catch-finally-2", Map.of())),
                List.of(),
                List.of()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-child", "try-2", Map.of())),
                List.of(),
                List.of()));

    CatalogElementResponseDto parentWithoutShells = new CatalogElementResponseDto();
    parentWithoutShells.id = "el-parent";
    parentWithoutShells.type = "try-catch-finally-2";
    parentWithoutShells.children = List.of();
    when(catalogRestClient.getElement(CHAIN_ID, "el-parent")).thenReturn(parentWithoutShells);

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("parent", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("child", "try-2", "Try", "parent", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals(CHAIN_ID, map.chainId());
    assertEquals("el-parent", map.nodeIdToElementId().get("parent"));
    assertEquals("el-child", map.nodeIdToElementId().get("child"));

    var inOrder = inOrder(catalogRestClient);
    inOrder
        .verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID),
            eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null)));
    inOrder
        .verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID),
            eq(new CatalogCreateElementRequest("try-2", "el-parent", null)));
  }

  @Test
  void materializeElementCreatesSingleNodeUsingProvidedMap() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("script", "el-trigger", null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-script", "script", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.getElement(CHAIN_ID, "el-trigger")).thenReturn(new CatalogElementResponseDto());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", "trigger", null, List.of())),
            List.of());
    ChainPlanNode script =
        graph.nodes().stream().filter(node -> "script".equals(node.nodeId())).findFirst().orElseThrow();

    String elementId =
        materializer.materializeElement(
            graph, script, CHAIN_ID, new MaterializationMap(CHAIN_ID, Map.of("trigger", "el-trigger")));

    assertEquals("el-script", elementId);
    verify(catalogRestClient)
        .createElement(eq(CHAIN_ID), eq(new CatalogCreateElementRequest("script", "el-trigger", null)));
  }

  @Test
  void materializeElementRejectsAlreadyMappedNode() {
    ChainPlanGraph graph = singleNodeGraph();
    ChainPlanNode node = graph.nodes().get(0);

    assertThrows(
        IllegalStateException.class,
        () ->
            materializer.materializeElement(
                graph, node, CHAIN_ID, new MaterializationMap(CHAIN_ID, Map.of("n1", "el-1"))));
  }

  private static CatalogElementResponseDto containerWithShells() {
    CatalogElementResponseDto tcff = new CatalogElementResponseDto();
    tcff.id = "el-tcff";
    tcff.type = "try-catch-finally-2";
    CatalogElementResponseDto tryShell = new CatalogElementResponseDto();
    tryShell.id = "el-try";
    tryShell.type = "try-2";
    CatalogElementResponseDto catchShell = new CatalogElementResponseDto();
    catchShell.id = "el-catch";
    catchShell.type = "catch-2";
    CatalogElementResponseDto finallyShell = new CatalogElementResponseDto();
    finallyShell.id = "el-finally";
    finallyShell.type = "finally-2";
    tcff.children = List.of(tryShell, catchShell, finallyShell);
    return tcff;
  }

  @Test
  void createsTriggerAtChainRootEvenWhenPlanNestsItUnderContainer() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto(
                        "el-tcff", "try-catch-finally-2", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("http-trigger", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("el-trigger", "http-trigger", Map.of())),
                List.of(),
                List.of()));

    when(catalogRestClient.getElement(CHAIN_ID, "el-tcff")).thenReturn(containerWithShells());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("trigger", "http-trigger", "Trigger", "try", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals("el-trigger", map.nodeIdToElementId().get("trigger"));
    verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID),
            eq(new CatalogCreateElementRequest("http-trigger", null, null)));
  }

  @Test
  void reusesAutoTryShellOnIncrementalMaterializeWithoutRemappingParent() {
    when(catalogRestClient.getElement(CHAIN_ID, "el-tcff")).thenReturn(containerWithShells());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "tcff", null, List.of())),
            List.of());
    ChainPlanNode tryNode =
        graph.nodes().stream().filter(node -> "try".equals(node.nodeId())).findFirst().orElseThrow();

    String elementId =
        materializer.materializeElement(
            graph, tryNode, CHAIN_ID, new MaterializationMap(CHAIN_ID, Map.of("tcff", "el-tcff")));

    assertEquals("el-try", elementId);
    verify(catalogRestClient, org.mockito.Mockito.never())
        .createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class));
  }

  @Test
  void placesFlowScriptInsideTryWhenReachedFromTryCatchWrapper() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto(
                        "el-tcff", "try-catch-finally-2", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("http-trigger", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("el-trigger", "http-trigger", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("script", "el-try", null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-parse", "script", Map.of())),
                List.of(),
                List.of()));

    when(catalogRestClient.getElement(CHAIN_ID, "el-tcff")).thenReturn(containerWithShells());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Fortune API", null),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "tcff", null, List.of()),
                new ChainPlanNode("parse", "script", "Read Query Param", null, null, List.of()),
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of(
                new ChainPlanEdge("e1", "trigger", "tcff", null),
                new ChainPlanEdge("e2", "tcff", "parse", null)));

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals("el-parse", map.nodeIdToElementId().get("parse"));
    verify(catalogRestClient)
        .createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("script", "el-try", null)));
  }

  @Test
  void mapsPlanNodeIdsToCatalogElementIds() {
    when(catalogRestClient.createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class)))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-1", "http-trigger", Map.of())),
                List.of(),
                List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals(Map.of("n1", "el-1"), map.nodeIdToElementId());
  }

  @Test
  void orderParentBeforeChildPrefersTriggersAmongRoots() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            null,
            List.of(
                new ChainPlanNode(
                    "tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of())),
            List.of());

    List<ChainPlanNode> ordered = ChainPlanSkeletonMaterializer.orderParentBeforeChild(graph);

    assertEquals("trigger", ordered.get(0).nodeId());
    assertEquals("tcff", ordered.get(1).nodeId());
  }

  @Test
  void orderParentBeforeChildThrowsOnMissingParent() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            null,
            List.of(
                new ChainPlanNode(
                    "child", "try-2", "Try", "missing-parent", null, List.of())),
            List.of());

    assertThrows(
        IllegalStateException.class,
        () -> ChainPlanSkeletonMaterializer.orderParentBeforeChild(graph));
  }

  @Test
  void throwsSkeletonMaterializationExceptionWhenElementCreationFails() {
    when(catalogRestClient.createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class)))
        .thenThrow(new RuntimeException("catalog 400"));

    SkeletonMaterializationException thrown =
        assertThrows(
            SkeletonMaterializationException.class,
            () -> materializer.materializeElements(singleNodeGraph(), CHAIN_ID));

    assertEquals(CHAIN_ID, thrown.chainId());
    assertTrue(thrown.chainDeleted());
    verify(catalogRestClient).deleteChain(CHAIN_ID);
  }

  @Test
  void reportsChainDeletedWhenRollbackSucceeds() {
    when(catalogRestClient.createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class)))
        .thenThrow(new RuntimeException("original failure"));

    SkeletonMaterializationException thrown =
        assertThrows(
            SkeletonMaterializationException.class,
            () -> materializer.materializeElements(singleNodeGraph(), CHAIN_ID));

    assertTrue(thrown.chainDeleted());
    assertEquals("original failure", thrown.getCause().getMessage());
  }

  @Test
  void reportsChainNotDeletedWhenRollbackFails() {
    when(catalogRestClient.createElement(eq(CHAIN_ID), any(CatalogCreateElementRequest.class)))
        .thenThrow(new RuntimeException("original failure"));
    org.mockito.Mockito.doThrow(new RuntimeException("delete failed"))
        .when(catalogRestClient)
        .deleteChain(CHAIN_ID);

    SkeletonMaterializationException thrown =
        assertThrows(
            SkeletonMaterializationException.class,
            () -> materializer.materializeElements(singleNodeGraph(), CHAIN_ID));

    assertFalse(thrown.chainDeleted());
    assertEquals("original failure", thrown.getCause().getMessage());
  }

  @Test
  void createsElementsWithExactCatalogTypes() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("http-trigger", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("el-trigger", "http-trigger", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("service-call", null, null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("el-call", "service-call", Map.of())),
                List.of(),
                List.of()));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", null),
            List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("n2", "service-call", "Call", null, null, List.of())),
            List.of());

    materializer.materializeElements(graph, CHAIN_ID);

    verify(catalogRestClient)
        .createElement(eq(CHAIN_ID), eq(new CatalogCreateElementRequest("service-call", null, null)));
  }

  @Test
  void requiresExistingChainId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> materializer.materializeElements(singleNodeGraph(), "  "));
  }

  private static ChainPlanGraph singleNodeGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
