package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Drops an optional container a generator named but left empty, before descriptor preflight.
 *
 * <p>A container the catalog creates on its own — the {@code finally-2} branch of a
 * {@code try-catch-finally-2}, for example — is optional for the reader but mandatory-inner for the
 * catalog. A plan that omits it is written and then tidied: the catalog generates the branch and
 * {@code UnclaimedGeneratedChildRemover} deletes it again as unclaimed. A plan that names the same
 * empty branch used to fail preflight instead, so one graph was fatal and the other silently
 * cleaned up. Pruning here makes the two agree, and keeps a generator that lists every branch of a
 * container from failing an edit the reader did ask for.
 *
 * <p>Only a node this write would create is ever dropped. A container the chain already has stays,
 * empty or not: removing it is a deletion, and a deletion is not this class's decision.
 */
public final class ChildlessOptionalContainerPruner {

  private static final Logger LOG = Logger.getLogger(ChildlessOptionalContainerPruner.class);

  private ChildlessOptionalContainerPruner() {}

  /**
   * Returns {@code desired} without its childless optional containers.
   *
   * <p>Pruning one container can empty its parent, so this repeats until the graph stops changing.
   * The same instance of {@code cache} the caller passes to preflight should be used here.
   *
   * @param desired the graph about to be materialized
   * @param current the chain as the catalog holds it now; empty on CREATE
   * @param cache descriptor cache for this materialization attempt
   */
  public static ChainPlanGraph prune(
      ChainPlanGraph desired, ChainPlanGraph current, CatalogElementDescriptorCache cache) {
    Objects.requireNonNull(desired, "desired");
    Objects.requireNonNull(cache, "cache");
    Set<String> existingNodeIds = nodeIds(current);
    ChainPlanGraph pruned = desired;
    Set<String> removed = new LinkedHashSet<>();
    for (String next = findPrunable(pruned, existingNodeIds, cache);
        next != null;
        next = findPrunable(pruned, existingNodeIds, cache)) {
      removed.add(next);
      pruned = without(pruned, next);
    }
    if (removed.isEmpty()) {
      return desired;
    }
    LOG.infof(
        "Pruned %d childless optional container(s) before preflight: %s",
        removed.size(), String.join(", ", removed));
    return pruned;
  }

  /** The id of one container safe to drop, or null when the graph has none left. */
  private static String findPrunable(
      ChainPlanGraph desired, Set<String> existingNodeIds, CatalogElementDescriptorCache cache) {
    List<ChainPlanNode> nodes = nodes(desired);
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    Set<String> parentsWithChildren = new LinkedHashSet<>();
    for (ChainPlanNode node : nodes) {
      byId.put(node.nodeId(), node);
      String parentId = trim(node.parentNodeId());
      if (parentId != null) {
        parentsWithChildren.add(parentId);
      }
    }
    for (ChainPlanNode node : nodes) {
      if (existingNodeIds.contains(node.nodeId()) || parentsWithChildren.contains(node.nodeId())) {
        continue;
      }
      if (isChildlessOptionalContainer(node, byId, cache)) {
        return node.nodeId();
      }
    }
    return null;
  }

  private static boolean isChildlessOptionalContainer(
      ChainPlanNode node, Map<String, ChainPlanNode> byId, CatalogElementDescriptorCache cache) {
    String type = trim(node.type());
    String parentId = trim(node.parentNodeId());
    if (type == null || parentId == null) {
      return false;
    }
    ChainPlanNode parent = byId.get(parentId);
    String parentType = parent == null ? null : trim(parent.type());
    if (parentType == null) {
      return false;
    }
    CatalogElementDescriptor descriptor = descriptorOrNull(cache, type);
    if (descriptor == null || !descriptor.container() || !descriptor.mandatoryInnerElement()) {
      return false;
    }
    CatalogElementDescriptor parentDescriptor = descriptorOrNull(cache, parentType);
    if (parentDescriptor == null || parentDescriptor.allowedChildren().isEmpty()) {
      return false;
    }
    CatalogChildQuantity quantity = parentDescriptor.allowedChildren().get(type);
    return quantity != null && quantity.minimum() == 0;
  }

  /**
   * Loads a descriptor, or null when the catalog cannot describe the type.
   *
   * <p>An unknown type is preflight's error to report, not a reason to prune.
   */
  private static CatalogElementDescriptor descriptorOrNull(
      CatalogElementDescriptorCache cache, String type) {
    try {
      return cache.require(type);
    } catch (CatalogElementDescriptorException e) {
      return null;
    }
  }

  private static ChainPlanGraph without(ChainPlanGraph graph, String nodeId) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : nodes(graph)) {
      if (!nodeId.equals(node.nodeId())) {
        nodes.add(node);
      }
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges() == null ? List.<ChainPlanEdge>of() : graph.edges()) {
      if (edge != null
          && !nodeId.equals(edge.fromNodeId())
          && !nodeId.equals(edge.toNodeId())
          && !nodeId.equals(edge.scopeNodeId())) {
        edges.add(edge);
      }
    }
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  private static List<ChainPlanNode> nodes(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    return graph.nodes().stream().filter(node -> node != null && node.nodeId() != null).toList();
  }

  private static Set<String> nodeIds(ChainPlanGraph graph) {
    Set<String> ids = new LinkedHashSet<>();
    for (ChainPlanNode node : nodes(graph)) {
      ids.add(node.nodeId());
    }
    return ids;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
