package org.qubership.integration.platform.ai.integration.catalog.materialize.plan;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Pure static tree-traversal utilities for {@link ElementPlan} trees.
 *
 * <p>Null elements in any list are silently skipped by all traversals. Null {@code roots} list is
 * treated as empty.
 */
public final class PlanTreeUtils {

  private PlanTreeUtils() {}

  /**
   * Pre-order (NLR) traversal: visits {@code node} before its children. Null elements in {@code
   * roots} or any {@code children} list are skipped.
   */
  public static void preOrder(List<ElementPlan> roots, Consumer<ElementPlan> visit) {
    if (roots == null) {
      return;
    }
    for (ElementPlan root : roots) {
      preOrderVisit(root, visit);
    }
  }

  private static void preOrderVisit(ElementPlan node, Consumer<ElementPlan> visit) {
    if (node == null) {
      return;
    }
    visit.accept(node);
    if (node.getChildren() != null) {
      for (ElementPlan child : node.getChildren()) {
        preOrderVisit(child, visit);
      }
    }
  }

  /**
   * Post-order (LRN) traversal: visits children before {@code node}. Null elements in any list are
   * skipped.
   */
  public static void postOrder(List<ElementPlan> roots, Consumer<ElementPlan> visit) {
    if (roots == null) {
      return;
    }
    for (ElementPlan root : roots) {
      postOrderVisit(root, visit);
    }
  }

  private static void postOrderVisit(ElementPlan node, Consumer<ElementPlan> visit) {
    if (node == null) {
      return;
    }
    if (node.getChildren() != null) {
      for (ElementPlan child : node.getChildren()) {
        postOrderVisit(child, visit);
      }
    }
    visit.accept(node);
  }

  /**
   * Pre-order traversal that passes {@code true} as second argument for direct elements of {@code
   * roots} and {@code false} for all deeper descendants. Used for parent-mismatch checks where root
   * nodes have no expected parent.
   */
  public static void visitWithRootFlag(
      List<ElementPlan> roots, BiConsumer<ElementPlan, Boolean> visit) {
    if (roots == null) {
      return;
    }
    for (ElementPlan root : roots) {
      visitWithRootFlagVisit(root, visit, true);
    }
  }

  private static void visitWithRootFlagVisit(
      ElementPlan node, BiConsumer<ElementPlan, Boolean> visit, boolean isRoot) {
    if (node == null) {
      return;
    }
    visit.accept(node, isRoot);
    if (node.getChildren() != null) {
      for (ElementPlan child : node.getChildren()) {
        visitWithRootFlagVisit(child, visit, false);
      }
    }
  }

  /** Returns the total number of non-null nodes in the tree (all levels). */
  public static int count(List<ElementPlan> roots) {
    int[] counter = {0};
    preOrder(roots, node -> counter[0]++);
    return counter[0];
  }

  /**
   * Builds a flat {@code clientId → ElementPlan} map via pre-order traversal. On duplicate {@code
   * clientId} the first occurrence wins. Nodes without a non-blank {@code clientId} are omitted.
   */
  public static Map<String, ElementPlan> indexByClientId(List<ElementPlan> roots) {
    Map<String, ElementPlan> out = new LinkedHashMap<>();
    preOrder(
        roots,
        node -> {
          String clientId = CatalogStrings.blankToNull(node.getClientId());
          if (clientId != null) {
            out.putIfAbsent(clientId, node);
          }
        });
    return out;
  }

  /**
   * Collects {@code clientId → elementId} pairs for nodes where both fields are non-blank. Order is
   * pre-order; on duplicate {@code clientId} the first occurrence wins.
   */
  public static LinkedHashMap<String, String> collectClientIdToElementId(List<ElementPlan> roots) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    preOrder(
        roots,
        node -> {
          String clientId = CatalogStrings.blankToNull(node.getClientId());
          String elementId = CatalogStrings.blankToNull(node.getElementId());
          if (clientId != null && elementId != null) {
            out.putIfAbsent(clientId, elementId);
          }
        });
    return out;
  }
}
