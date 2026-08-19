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
 * whole except for a replaced address element the intent names, {@code parentNodeId} may move only
 * for a node the intent names as a target, and edges may be added, removed, or rewritten only
 * where they touch the target boundary or a new node.
 *
 * <p>Connections of a displaced node are derived, rather than taken on trust, by {@link
 * ChainEditBoundaryWiring}. When the capture drops them, incoming hops attach to the new
 * subgraph's entry and outgoing hops leave from its exit. Both ends come from the new nodes: the
 * entry has no incoming edge from another new node, and the exit has no outgoing edge to another
 * new node. A wrapping container has neither, so it is both ends. A generator that relists the
 * connections is accepted as is. An insertion that keeps the address elements in place does not
 * restore a dropped address edge: the capture already replaced that hop with the new subgraph. A
 * replacement omits the address element; its neighbours follow the same entry and exit rule.
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
    Set<String> removed = new LinkedHashSet<>();
    if (intent.replacesAddressElement()) {
      removed.addAll(intent.targetNodeIds());
    }
    Map<String, ChainPlanNode> baseNodes = nodesById(base.nodes(), "base");
    Map<String, ChainPlanNode> proposedNodes = nodesById(proposed.nodes(), "proposed");
    if (!baseNodes.keySet().containsAll(targets)) {
      Set<String> missing = new LinkedHashSet<>(targets);
      missing.removeAll(baseNodes.keySet());
      throw unsatisfiableScope("unknown structural target ids " + missing);
    }

    Set<String> newNodeIds = new LinkedHashSet<>(proposedNodes.keySet());
    newNodeIds.removeAll(baseNodes.keySet());
    String containerParent = containerParentOfRemoved(base, removed);
    List<ChainPlanNode> mergedNodes = new ArrayList<>();
    for (ChainPlanNode existing : base.nodes()) {
      if (removed.contains(existing.nodeId())) {
        continue;
      }
      ChainPlanNode candidate = proposedNodes.get(existing.nodeId());
      if (candidate == null) {
        throw outOfScope("structure capture removed existing node '" + existing.nodeId() + "'");
      }
      mergedNodes.add(mergeExistingNode(existing, candidate, targets));
    }
    for (ChainPlanNode candidate : proposed.nodes()) {
      if (newNodeIds.contains(candidate.nodeId())) {
        mergedNodes.add(
            withoutProperties(
                placeInReplacedContainer(candidate, newNodeIds, removed, containerParent)));
      }
    }

    Map<String, ChainPlanNode> mergedById = nodesById(mergedNodes, "merged");
    List<ChainPlanEdge> mergedEdges =
        mergeEdges(
            base.edges(),
            proposed.edges(),
            targets,
            removed,
            newNodeIds,
            mergedById,
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
      Set<String> removed,
      Set<String> newNodeIds,
      Map<String, ChainPlanNode> mergedNodes,
      Set<String> baseNodeIds) {
    List<ChainPlanEdge> baseList = baseEdges == null ? List.of() : baseEdges;
    List<ChainPlanEdge> proposedList = proposedEdges == null ? List.of() : proposedEdges;
    Map<String, ChainPlanEdge> baseById = edgesById(baseList, "base");
    Map<String, ChainPlanEdge> proposedById = edgesById(proposedList, "proposed");
    Set<String> mergedNodeIds = mergedNodes.keySet();

    List<ChainPlanEdge> restored = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    List<ChainPlanEdge> acceptedProposed = new ArrayList<>();
    for (ChainPlanEdge candidate : proposedList) {
      if (!mergedNodeIds.contains(candidate.fromNodeId())
          || !mergedNodeIds.contains(candidate.toNodeId())) {
        continue;
      }
      acceptedProposed.add(candidate);
      connections.add(connectionKey(candidate.fromNodeId(), candidate.toNodeId()));
    }
    ChainEditBoundaryWiring.SubgraphEnds ends =
        ChainEditBoundaryWiring.deriveSubgraphEnds(newNodeIds, acceptedProposed, mergedNodes);

    for (ChainPlanEdge existing : baseList) {
      ChainPlanEdge candidate = proposedById.get(existing.edgeId());
      if (candidate == null
          || !mergedNodeIds.contains(candidate.fromNodeId())
          || !mergedNodeIds.contains(candidate.toNodeId())) {
        if (referencesAny(existing, removed)) {
          ChainPlanEdge recovered =
              ChainEditBoundaryWiring.rewireReplacedEndpoint(existing, removed, ends, mergedNodeIds);
          if (recovered != null
              && connections.add(connectionKey(recovered.fromNodeId(), recovered.toNodeId()))) {
            restored.add(recovered);
          }
          continue;
        }
        if (candidate == null) {
          if (!referencesAny(existing, targets)) {
            throw outOfScope(
                "structure capture removed unrelated edge '" + existing.edgeId() + "'");
          }
          ChainPlanEdge recovered =
              ChainEditBoundaryWiring.rewireMovedEndpoint(existing, mergedNodes, baseNodeIds, ends);
          if (recovered != null
              && connections.add(connectionKey(recovered.fromNodeId(), recovered.toNodeId()))) {
            restored.add(recovered);
          }
        }
        continue;
      }
      if (!Objects.equals(existing, candidate)
          && (!referencesAny(existing, targets) || !referencesAny(candidate, newNodeIds))) {
        throw outOfScope("structure capture rewrote unrelated edge '" + existing.edgeId() + "'");
      }
    }
    for (ChainPlanEdge candidate : acceptedProposed) {
      if (!baseById.containsKey(candidate.edgeId()) && !referencesAny(candidate, newNodeIds)) {
        throw outOfScope(
            "structure capture added an edge between existing nodes outside the target boundary: '"
                + candidate.edgeId()
                + "'");
      }
    }
    if (!restored.isEmpty()) {
      LOG.infof(
          "Followed %d dropped connection(s) onto the new subgraph: %s",
          restored.size(),
          restored.stream().map(ChainPlanEdge::edgeId).collect(Collectors.joining(", ")));
    }
    List<ChainPlanEdge> merged = new ArrayList<>(acceptedProposed);
    merged.addAll(restored);
    return List.copyOf(merged);
  }

  /**
   * Parent the new subgraph inherits when the replaced element sat inside a container.
   *
   * <p>Several replaced elements must share that parent; mixed parents leave the capture's own
   * parent values in place rather than guessing.
   */
  private static String containerParentOfRemoved(ChainPlanGraph base, Set<String> removed) {
    if (removed.isEmpty() || base.nodes() == null) {
      return null;
    }
    String parent = null;
    for (ChainPlanNode node : base.nodes()) {
      if (!removed.contains(node.nodeId())) {
        continue;
      }
      String candidate = node.parentNodeId();
      if (candidate == null || candidate.isBlank() || removed.contains(candidate)) {
        continue;
      }
      if (parent != null && !parent.equals(candidate)) {
        return null;
      }
      parent = candidate;
    }
    return parent;
  }

  /**
   * Puts a new surface node in the same container the replaced element occupied, when the capture
   * left it at chain root or still parented to the removed node.
   */
  private static ChainPlanNode placeInReplacedContainer(
      ChainPlanNode node, Set<String> newNodeIds, Set<String> removed, String containerParent) {
    if (containerParent == null) {
      return node;
    }
    String parent = node.parentNodeId();
    boolean nestedInNewSubgraph = parent != null && newNodeIds.contains(parent);
    if (nestedInNewSubgraph) {
      return node;
    }
    if (parent != null && !parent.isBlank() && !removed.contains(parent)) {
      return node;
    }
    return new ChainPlanNode(
        node.nodeId(), node.type(), node.label(), containerParent, node.order(), node.properties());
  }

  private static String connectionKey(String fromNodeId, String toNodeId) {
    return fromNodeId + " " + toNodeId;
  }

  private static boolean referencesAny(ChainPlanEdge edge, Set<String> nodeIds) {
    if (nodeIds == null || nodeIds.isEmpty()) {
      return false;
    }
    return containsId(nodeIds, edge.fromNodeId())
        || containsId(nodeIds, edge.toNodeId())
        || containsId(nodeIds, edge.scopeNodeId());
  }

  private static boolean containsId(Set<String> nodeIds, String nodeId) {
    return nodeId != null && nodeIds.contains(nodeId);
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
    return new ChainEditScopeException(scopeMessage(message), false);
  }

  /**
   * A refusal the generator cannot answer, because the intent itself names something the base
   * graph does not hold. Retrying the capture cannot change that.
   */
  private static IllegalArgumentException unsatisfiableScope(String message) {
    return new ChainEditScopeException(scopeMessage(message), true);
  }

  private static String scopeMessage(String message) {
    return "edit structure is outside the approved scope: " + message;
  }
}
