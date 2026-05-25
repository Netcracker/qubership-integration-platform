package org.qubership.integration.platform.ai.integration.catalog.materialize.plan;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanTreeUtilsTest {

  // --- helpers ---

  private static ElementPlan node(String clientId, String elementId, ElementPlan... children) {
    ElementPlan n = new ElementPlan();
    n.setClientId(clientId);
    n.setElementId(elementId);
    if (children.length > 0) {
      n.setChildren(new ArrayList<>(List.of(children)));
    }
    return n;
  }

  private static ElementPlan node(String clientId) {
    return node(clientId, null);
  }

  // --- preOrder ---

  @Test
  void preOrderEmptyListVisitsNothing() {
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.preOrder(List.of(), n -> visited.add(n.getClientId()));
    assertTrue(visited.isEmpty());
  }

  @Test
  void preOrderNullListVisitsNothing() {
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.preOrder(null, n -> visited.add(n.getClientId()));
    assertTrue(visited.isEmpty());
  }

  @Test
  void preOrderSingleRootVisitsThatRoot() {
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.preOrder(List.of(node("a")), n -> visited.add(n.getClientId()));
    assertEquals(List.of("a"), visited);
  }

  @Test
  void preOrderThreeLevelsVisitedInPreOrder() {
    ElementPlan grandchild = node("gc");
    ElementPlan child = node("c", null, grandchild);
    ElementPlan root = node("r", null, child);

    List<String> visited = new ArrayList<>();
    PlanTreeUtils.preOrder(List.of(root), n -> visited.add(n.getClientId()));

    assertEquals(List.of("r", "c", "gc"), visited);
  }

  @Test
  void preOrderNullNodeInListSkipped() {
    List<ElementPlan> roots = new ArrayList<>();
    roots.add(null);
    roots.add(node("a"));
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.preOrder(roots, n -> visited.add(n.getClientId()));
    assertEquals(List.of("a"), visited);
  }

  // --- postOrder ---

  @Test
  void postOrderThreeLevelsVisitedInPostOrder() {
    ElementPlan grandchild = node("gc");
    ElementPlan child = node("c", null, grandchild);
    ElementPlan root = node("r", null, child);

    List<String> visited = new ArrayList<>();
    PlanTreeUtils.postOrder(List.of(root), n -> visited.add(n.getClientId()));

    assertEquals(List.of("gc", "c", "r"), visited);
  }

  @Test
  void postOrderNullListVisitsNothing() {
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.postOrder(null, n -> visited.add(n.getClientId()));
    assertTrue(visited.isEmpty());
  }

  // --- count ---

  @Test
  void countEmptyListReturnsZero() {
    assertEquals(0, PlanTreeUtils.count(List.of()));
  }

  @Test
  void countNullListReturnsZero() {
    assertEquals(0, PlanTreeUtils.count(null));
  }

  @Test
  void countSingleRootReturnsOne() {
    assertEquals(1, PlanTreeUtils.count(List.of(node("a"))));
  }

  @Test
  void countThreeLevelTreeCountsAllNodes() {
    ElementPlan grandchild = node("gc");
    ElementPlan child = node("c", null, grandchild);
    ElementPlan root = node("r", null, child);
    assertEquals(3, PlanTreeUtils.count(List.of(root)));
  }

  // --- indexByClientId ---

  @Test
  void indexByClientIdEmptyListReturnsEmpty() {
    assertTrue(PlanTreeUtils.indexByClientId(List.of()).isEmpty());
  }

  @Test
  void indexByClientIdSingleRootContainsEntry() {
    ElementPlan a = node("a");
    Map<String, ElementPlan> idx = PlanTreeUtils.indexByClientId(List.of(a));
    assertEquals(1, idx.size());
    assertSame(a, idx.get("a"));
  }

  @Test
  void indexByClientIdThreeLevelTreeAllNodesIndexed() {
    ElementPlan grandchild = node("gc");
    ElementPlan child = node("c", null, grandchild);
    ElementPlan root = node("r", null, child);

    Map<String, ElementPlan> idx = PlanTreeUtils.indexByClientId(List.of(root));
    assertEquals(3, idx.size());
    assertSame(root, idx.get("r"));
    assertSame(child, idx.get("c"));
    assertSame(grandchild, idx.get("gc"));
  }

  @Test
  void indexByClientIdDuplicateClientIdFirstWins() {
    ElementPlan first = node("dup");
    ElementPlan second = node("dup");
    first.setChildren(List.of(second));

    Map<String, ElementPlan> idx = PlanTreeUtils.indexByClientId(List.of(first));
    assertSame(first, idx.get("dup"), "first occurrence should win on duplicate clientId");
  }

  // --- collectClientIdToElementId ---

  @Test
  void collectClientIdToElementIdNullListReturnsEmpty() {
    assertTrue(PlanTreeUtils.collectClientIdToElementId(null).isEmpty());
  }

  @Test
  void collectClientIdToElementIdNodeWithoutElementIdExcluded() {
    ElementPlan n = node("a"); // elementId is null
    LinkedHashMap<String, String> result = PlanTreeUtils.collectClientIdToElementId(List.of(n));
    assertFalse(result.containsKey("a"));
  }

  @Test
  void collectClientIdToElementIdThreeLevelTreeAllPairsCollected() {
    ElementPlan grandchild = node("gc", "cat-gc");
    ElementPlan child = node("c", "cat-c", grandchild);
    ElementPlan root = node("r", "cat-r", child);

    LinkedHashMap<String, String> result = PlanTreeUtils.collectClientIdToElementId(List.of(root));

    assertEquals(3, result.size());
    assertEquals("cat-r", result.get("r"));
    assertEquals("cat-c", result.get("c"));
    assertEquals("cat-gc", result.get("gc"));
  }

  @Test
  void collectClientIdToElementIdDuplicateClientIdFirstWins() {
    ElementPlan first = node("dup", "cat-first");
    ElementPlan second = node("dup", "cat-second");
    first.setChildren(List.of(second));

    LinkedHashMap<String, String> result = PlanTreeUtils.collectClientIdToElementId(List.of(first));
    assertEquals("cat-first", result.get("dup"));
  }

  // --- visitWithRootFlag ---

  @Test
  void visitWithRootFlagNullListVisitsNothing() {
    List<String> visited = new ArrayList<>();
    PlanTreeUtils.visitWithRootFlag(null, (n, isRoot) -> visited.add(n.getClientId()));
    assertTrue(visited.isEmpty());
  }

  @Test
  void visitWithRootFlagRootFlagCorrect() {
    ElementPlan grandchild = node("gc");
    ElementPlan child = node("c", null, grandchild);
    ElementPlan root = node("r", null, child);

    List<String> rootNodes = new ArrayList<>();
    List<String> nonRootNodes = new ArrayList<>();
    PlanTreeUtils.visitWithRootFlag(
        List.of(root),
        (n, isRoot) -> {
          if (isRoot) {
            rootNodes.add(n.getClientId());
          } else {
            nonRootNodes.add(n.getClientId());
          }
        });

    assertEquals(List.of("r"), rootNodes);
    assertEquals(List.of("c", "gc"), nonRootNodes);
  }

  @Test
  void visitWithRootFlagTwoRootsBothFlaggedAsRoot() {
    ElementPlan r1 = node("r1");
    ElementPlan r2 = node("r2");

    List<String> rootNodes = new ArrayList<>();
    PlanTreeUtils.visitWithRootFlag(
        List.of(r1, r2),
        (n, isRoot) -> {
          if (isRoot) {
            rootNodes.add(n.getClientId());
          }
        });

    assertEquals(List.of("r1", "r2"), rootNodes);
  }
}
