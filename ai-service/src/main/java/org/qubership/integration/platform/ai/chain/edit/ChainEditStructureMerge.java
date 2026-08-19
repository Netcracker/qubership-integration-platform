package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Protects an imported graph while accepting topology captured for one structural edit.
 *
 * <p>Structure capture re-emits the whole chain, so an existing node arrives carrying whatever the
 * generator echoed back for it. The merge pins {@code type}, {@code label}, {@code order}, and
 * {@code properties} of every existing node to the imported values, so an echo that disagrees is
 * discarded rather than applied. Rejecting the capture over such a disagreement would buy no extra
 * protection and would fail the edit over cosmetic echo noise, so those fields are logged and
 * dropped.
 *
 * <p>What the capture genuinely decides is validated instead: the existing node set must survive
 * whole, {@code parentNodeId} may move only for a node the intent names as a target, and edges may
 * be added, removed, or rewritten only where they touch the target boundary or a new node.
 *
 * <p>Connections of a wrapped node are derived here rather than taken on trust. Wrapping puts a new
 * container where the node used to sit in the flow, so each connection the imported chain gave that
 * node has to move onto the container. A generator that relists them is accepted as is; one that
 * drops them has them restored from the imported chain, which is the only place that still knows
 * how many there were.
 */
public final class ChainEditStructureMerge {

  private static final Logger LOG = Logger.getLogger(ChainEditStructureMerge.class);

  private ChainEditStructureMerge() {}

  public static ChainPlanGraph merge(
      ChainPlanGraph base, ChainPlanGraph proposed, ChainEditIntent intent) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(proposed, "proposed");
    Objects.requireNonNull(intent, "intent");

    Set<String> targets = Set.copyOf(intent.targetNodeIds());
    Map<String, ChainPlanNode> baseNodes = nodesById(base.nodes(), "base");
    Map<String, ChainPlanNode> proposedNodes = nodesById(proposed.nodes(), "proposed");
    if (!baseNodes.keySet().containsAll(targets)) {
      Set<String> missing = new LinkedHashSet<>(targets);
      missing.removeAll(baseNodes.keySet());
      throw outOfScope("unknown structural target ids " + missing);
    }

    Set<String> newNodeIds = new LinkedHashSet<>(proposedNodes.keySet());
    newNodeIds.removeAll(baseNodes.keySet());
    List<ChainPlanNode> mergedNodes = new ArrayList<>();
    for (ChainPlanNode existing : base.nodes()) {
      ChainPlanNode candidate = proposedNodes.get(existing.nodeId());
      if (candidate == null) {
        throw outOfScope("structure capture removed existing node '" + existing.nodeId() + "'");
      }
      mergedNodes.add(mergeExistingNode(existing, candidate, targets));
    }
    for (ChainPlanNode candidate : proposed.nodes()) {
      if (newNodeIds.contains(candidate.nodeId())) {
        mergedNodes.add(withoutProperties(candidate));
      }
    }

    List<ChainPlanEdge> mergedEdges =
        mergeEdges(
            base.edges(),
            proposed.edges(),
            targets,
            newNodeIds,
            nodesById(mergedNodes, "merged"),
            baseNodes.keySet());
    return new ChainPlanGraph(
        base.schemaVersion(), base.chain(), List.copyOf(mergedNodes), mergedEdges);
  }

  private static ChainPlanNode mergeExistingNode(
      ChainPlanNode existing, ChainPlanNode candidate, Set<String> targets) {
    logDiscardedEcho("type", existing.nodeId(), existing.type(), candidate.type());
    logDiscardedEcho("label", existing.nodeId(), existing.label(), candidate.label());
    logDiscardedEcho("order", existing.nodeId(), existing.order(), candidate.order());
    if (candidate.properties() != null && !candidate.properties().isEmpty()) {
      logDiscardedEcho(
          "properties", existing.nodeId(), existing.properties(), candidate.properties());
    }
    String parentNodeId = existing.parentNodeId();
    if (!Objects.equals(existing.parentNodeId(), candidate.parentNodeId())) {
      if (!targets.contains(existing.nodeId())) {
        throw outOfScope(
            "structure capture reparented non-target node '" + existing.nodeId() + "'");
      }
      parentNodeId = candidate.parentNodeId();
    }
    return new ChainPlanNode(
        existing.nodeId(),
        existing.type(),
        existing.label(),
        parentNodeId,
        existing.order(),
        existing.properties());
  }

  /** Records an echoed value the merge pins back to the imported node instead of applying. */
  private static void logDiscardedEcho(
      String field, String nodeId, Object imported, Object captured) {
    if (captured != null && !Objects.equals(imported, captured)) {
      LOG.debugf(
          "Structure capture echoed a different %s for existing node %s; keeping the imported value",
          field, nodeId);
    }
  }

  private static ChainPlanNode withoutProperties(ChainPlanNode node) {
    return new ChainPlanNode(
        node.nodeId(), node.type(), node.label(), node.parentNodeId(), node.order(), List.of());
  }

  private static List<ChainPlanEdge> mergeEdges(
      List<ChainPlanEdge> baseEdges,
      List<ChainPlanEdge> proposedEdges,
      Set<String> targets,
      Set<String> newNodeIds,
      Map<String, ChainPlanNode> mergedNodes,
      Set<String> baseNodeIds) {
    List<ChainPlanEdge> baseList = baseEdges == null ? List.of() : baseEdges;
    List<ChainPlanEdge> proposedList = proposedEdges == null ? List.of() : proposedEdges;
    Map<String, ChainPlanEdge> baseById = edgesById(baseList, "base");
    Map<String, ChainPlanEdge> proposedById = edgesById(proposedList, "proposed");

    List<ChainPlanEdge> restored = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    for (ChainPlanEdge candidate : proposedList) {
      connections.add(connectionKey(candidate.fromNodeId(), candidate.toNodeId()));
    }

    for (ChainPlanEdge existing : baseList) {
      ChainPlanEdge candidate = proposedById.get(existing.edgeId());
      if (candidate == null) {
        if (!referencesAny(existing, targets)) {
          throw outOfScope("structure capture removed unrelated edge '" + existing.edgeId() + "'");
        }
        ChainPlanEdge rewired = followTargetIntoNewParent(existing, mergedNodes, baseNodeIds);
        if (connections.add(connectionKey(rewired.fromNodeId(), rewired.toNodeId()))) {
          restored.add(rewired);
        }
        continue;
      }
      if (!Objects.equals(existing, candidate)
          && (!referencesAny(existing, targets) || !referencesAny(candidate, newNodeIds))) {
        throw outOfScope("structure capture rewrote unrelated edge '" + existing.edgeId() + "'");
      }
    }
    for (ChainPlanEdge candidate : proposedList) {
      if (!baseById.containsKey(candidate.edgeId()) && !referencesAny(candidate, newNodeIds)) {
        throw outOfScope(
            "structure capture added an edge between existing nodes outside the target boundary: '"
                + candidate.edgeId()
                + "'");
      }
    }
    if (!restored.isEmpty()) {
      LOG.infof(
          "Followed %d connection(s) of a wrapped node onto its new container: %s",
          restored.size(),
          restored.stream().map(ChainPlanEdge::edgeId).collect(Collectors.joining(", ")));
    }
    List<ChainPlanEdge> merged = new ArrayList<>(proposedList);
    merged.addAll(restored);
    return List.copyOf(merged);
  }

  /**
   * Re-points a dropped connection at the container the node moved into.
   *
   * <p>Wrapping a node puts a new container where that node used to sit in the flow, so every
   * connection that crossed the container boundary now belongs to the container: {@code A -> X}
   * becomes {@code A -> wrapper} for each neighbour A the wrapped node had. Deriving them from the
   * imported chain keeps all of them, however many there were, instead of trusting a generator to
   * relist each one. A connection whose two ends moved into the same container stays as it was:
   * both endpoints are still siblings, just one level deeper.
   */
  private static ChainPlanEdge followTargetIntoNewParent(
      ChainPlanEdge edge, Map<String, ChainPlanNode> mergedNodes, Set<String> baseNodeIds) {
    String from = newContainerOf(edge.fromNodeId(), mergedNodes, baseNodeIds);
    String to = newContainerOf(edge.toNodeId(), mergedNodes, baseNodeIds);
    if (Objects.equals(from, to)
        || (Objects.equals(from, edge.fromNodeId()) && Objects.equals(to, edge.toNodeId()))) {
      return edge;
    }
    return new ChainPlanEdge(edge.edgeId(), from, to, edge.scopeNodeId());
  }

  /**
   * The outermost container this write adds above {@code nodeId}, or {@code nodeId} when the node
   * did not move into one.
   */
  private static String newContainerOf(
      String nodeId, Map<String, ChainPlanNode> mergedNodes, Set<String> baseNodeIds) {
    String outermost = null;
    Set<String> visited = new LinkedHashSet<>();
    ChainPlanNode node = mergedNodes.get(nodeId);
    while (node != null && visited.add(node.nodeId())) {
      String parentId = node.parentNodeId();
      if (parentId == null || parentId.isBlank()) {
        break;
      }
      if (!baseNodeIds.contains(parentId)) {
        outermost = parentId;
      }
      node = mergedNodes.get(parentId);
    }
    return outermost == null ? nodeId : outermost;
  }

  private static String connectionKey(String fromNodeId, String toNodeId) {
    return fromNodeId + " " + toNodeId;
  }

  private static boolean referencesAny(ChainPlanEdge edge, Set<String> nodeIds) {
    return nodeIds.contains(edge.fromNodeId())
        || nodeIds.contains(edge.toNodeId())
        || nodeIds.contains(edge.scopeNodeId());
  }

  private static Map<String, ChainPlanNode> nodesById(
      List<ChainPlanNode> nodes, String graphName) {
    if (nodes == null) {
      throw outOfScope(graphName + " graph has no nodes");
    }
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    for (ChainPlanNode node : nodes) {
      if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
        throw outOfScope(graphName + " graph contains a node without an id");
      }
      if (byId.put(node.nodeId(), node) != null) {
        throw outOfScope(graphName + " graph contains duplicate node id '" + node.nodeId() + "'");
      }
    }
    return byId;
  }

  private static Map<String, ChainPlanEdge> edgesById(
      List<ChainPlanEdge> edges, String graphName) {
    Map<String, ChainPlanEdge> byId = new LinkedHashMap<>();
    for (ChainPlanEdge edge : edges) {
      if (edge == null || edge.edgeId() == null || edge.edgeId().isBlank()) {
        throw outOfScope(graphName + " graph contains an edge without an id");
      }
      if (byId.put(edge.edgeId(), edge) != null) {
        throw outOfScope(graphName + " graph contains duplicate edge id '" + edge.edgeId() + "'");
      }
    }
    return byId;
  }

  private static IllegalArgumentException outOfScope(String message) {
    return new IllegalArgumentException("edit structure is outside the approved scope: " + message);
  }
}
