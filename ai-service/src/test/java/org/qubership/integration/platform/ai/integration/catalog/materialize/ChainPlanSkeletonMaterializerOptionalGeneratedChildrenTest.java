package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.container;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.leaf;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.stubPermissive;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class ChainPlanSkeletonMaterializerOptionalGeneratedChildrenTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private CatalogRestClient catalogRestClient;
  @Mock private CatalogElementDescriptorLoader descriptorLoader;

  private ChainPlanSkeletonMaterializer materializer;

  @BeforeEach
  void setUp() {
    stubPermissive(descriptorLoader);
    materializer = new ChainPlanSkeletonMaterializer(catalogRestClient, descriptorLoader);
    lenient().when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of());
    lenient()
        .when(catalogRestClient.deleteElements(eq(CHAIN_ID), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));
  }

  @Test
  void deletesUnclaimedOptionalElse() {
    when(descriptorLoader.load("condition"))
        .thenReturn(
            container(
                "condition",
                Map.of(
                    "if", CatalogChildQuantity.ONE_OR_MANY,
                    "else", CatalogChildQuantity.ONE_OR_ZERO)));
    when(descriptorLoader.load("if")).thenReturn(leaf("if"));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("condition", null, null))))
        .thenReturn(
            created(
                new CatalogRestClient.ElementSummaryDto(
                    "el-cond",
                    "condition",
                    Map.of(),
                    null,
                    List.of(
                        new CatalogRestClient.ElementSummaryDto("el-if", "if", Map.of()),
                        new CatalogRestClient.ElementSummaryDto("el-else", "else", Map.of())))));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("cond", "condition", "Condition", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "cond", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals("el-if", map.nodeIdToElementId().get("if-1"));
    assertEquals("el-cond", map.nodeIdToElementId().get("cond"));

    var order = inOrder(catalogRestClient);
    order
        .verify(catalogRestClient)
        .createElement(eq(CHAIN_ID), eq(new CatalogCreateElementRequest("condition", null, null)));
    order
        .verify(catalogRestClient)
        .deleteElements(eq(CHAIN_ID), eq(List.of("el-else")));
  }

  @Test
  void deletesUnclaimedFinally() {
    when(descriptorLoader.load("try-catch-finally-2"))
        .thenReturn(
            container(
                "try-catch-finally-2",
                Map.of(
                    "try-2", CatalogChildQuantity.ONE,
                    "catch-2", CatalogChildQuantity.ONE_OR_MANY,
                    "finally-2", CatalogChildQuantity.ONE_OR_ZERO)));
    when(descriptorLoader.load("try-2")).thenReturn(leaf("try-2"));
    when(descriptorLoader.load("catch-2")).thenReturn(leaf("catch-2"));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("try-catch-finally-2", null, null))))
        .thenReturn(
            created(
                new CatalogRestClient.ElementSummaryDto(
                    "el-tcff",
                    "try-catch-finally-2",
                    Map.of(),
                    null,
                    List.of(
                        new CatalogRestClient.ElementSummaryDto("el-try", "try-2", Map.of()),
                        new CatalogRestClient.ElementSummaryDto("el-catch", "catch-2", Map.of()),
                        new CatalogRestClient.ElementSummaryDto(
                            "el-finally", "finally-2", Map.of())))));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "tcff", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals("el-try", map.nodeIdToElementId().get("try"));
    assertEquals("el-catch", map.nodeIdToElementId().get("catch"));
    verify(catalogRestClient)
        .deleteElements(eq(CHAIN_ID), eq(List.of("el-finally")));
  }

  @Test
  void deletesUnclaimedMainSplitElement() {
    when(descriptorLoader.load("split-2"))
        .thenReturn(
            container(
                "split-2",
                Map.of(
                    "main-split-element-2", CatalogChildQuantity.ONE_OR_ZERO,
                    "split-element-2", CatalogChildQuantity.ONE_OR_MANY)));
    when(descriptorLoader.load("split-element-2")).thenReturn(leaf("split-element-2"));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("split-2", null, null))))
        .thenReturn(
            created(
                new CatalogRestClient.ElementSummaryDto(
                    "el-split",
                    "split-2",
                    Map.of(),
                    null,
                    List.of(
                        new CatalogRestClient.ElementSummaryDto(
                            "el-main", "main-split-element-2", Map.of()),
                        new CatalogRestClient.ElementSummaryDto(
                            "el-branch-1", "split-element-2", Map.of())))));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("split", "split-2", "Split", null, null, List.of()),
                new ChainPlanNode(
                    "branch-1", "split-element-2", "Branch 1", "split", null, List.of())),
            List.of());

    MaterializationMap map = materializer.materializeElements(graph, CHAIN_ID);

    assertEquals("el-branch-1", map.nodeIdToElementId().get("branch-1"));
    verify(catalogRestClient)
        .deleteElements(eq(CHAIN_ID), eq(List.of("el-main")));
  }

  @Test
  void doesNotDeleteMandatoryGeneratedChild() {
    when(descriptorLoader.load("condition"))
        .thenReturn(container("condition", Map.of("if", CatalogChildQuantity.ONE)));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(new ChainPlanNode("cond", "condition", "Condition", null, null, List.of())),
            List.of());

    assertThrows(
        DesiredGraphDescriptorPreflightException.class,
        () -> materializer.materializeElements(graph, CHAIN_ID));

    verify(catalogRestClient, never()).createElement(any(), any());
    verify(catalogRestClient, never()).deleteElements(any(), any());
  }

  @Test
  void doesNotInventDeletesFromCardinality() {
    when(descriptorLoader.load("condition"))
        .thenReturn(
            container(
                "condition",
                Map.of(
                    "if", CatalogChildQuantity.ONE_OR_MANY,
                    "else", CatalogChildQuantity.ONE_OR_ZERO)));
    when(descriptorLoader.load("if")).thenReturn(leaf("if"));
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("condition", null, null))))
        .thenReturn(
            created(
                new CatalogRestClient.ElementSummaryDto(
                    "el-cond",
                    "condition",
                    Map.of(),
                    null,
                    List.of(new CatalogRestClient.ElementSummaryDto("el-if", "if", Map.of())))));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("cond", "condition", "Condition", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "cond", null, List.of())),
            List.of());

    materializer.materializeElements(graph, CHAIN_ID);

    verify(catalogRestClient, never()).deleteElements(any(), argThat(ids -> !ids.isEmpty()));
  }

  private static CatalogRestClient.ChainDiffDto created(
      CatalogRestClient.ElementSummaryDto... elements) {
    return new CatalogRestClient.ChainDiffDto(List.of(elements), List.of(), List.of());
  }
}
