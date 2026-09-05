package org.qubership.integration.platform.ai.chain.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Derives how a new subgraph connects to the chain elements around it.
 *
 * <p>A capture may drop the connections of a node it displaces, so this rebuilds them from the
 * subgraph's shape instead of taking the capture's edge list on trust. Incoming hops attach to the
 * subgraph's entry, outgoing hops leave from its exit, and a connection whose both ends moved into
 * the same new branch is left as it was. Ends that landed in different branches drop the inherited
 * edge.
 *
 * <p>Every method here takes the new elements and the imported graph, never the edit intent: the
 * structure merge decides which elements were displaced and why, and only hands this class the
 * resulting node sets.
 */
public final class ChainEditBoundaryWiring {

  private ChainEditBoundaryWiring() {}

  /** The node a dropped connection reattaches to: {@code entry} for incoming, {@code exit} for outgoing. */
  public record SubgraphEnds(String entry, String exit) {}

  /**
   * Entry and exit of the new subgraph, derived from the new nodes themselves.
   *
   * <p>Only surface new nodes count: a child such as {@code try} is not an end of the flow. Among
   * those, the entry has no incoming edge from another new node and the exit has no outgoing edge
   * to another new node. Several disconnected surface nodes leave the end unset so a moved endpoint
   * can fall back to the container it nested into.
   */
  public static SubgraphEnds deriveSubgraphEnds(
      Set<String> newNodeIds, List<ChainPlanEdge> edgesAmongNewNodes, Map<String, ChainPlanNode> nodesById) {
    if (newNodeIds.isEmpty()) {
      return new SubgraphEnds(null, null);
    }
    Set<String> withIncoming = new LinkedHashSet<>();
    Set<String> withOutgoing = new LinkedHashSet<>();
    for (ChainPlanEdge edge : edgesAmongNewNodes) {
      if (newNodeIds.contains(edge.fromNodeId()) && newNodeIds.contains(edge.toNodeId())) {
        withOutgoing.add(edge.fromNodeId());
        withIncoming.add(edge.toNodeId());
      }
    }
    Set<String> surface = new LinkedHashSet<>();
    for (String nodeId : newNodeIds) {
      ChainPlanNode node = nodesById.get(nodeId);
      String parentId = node == null ? null : node.parentNodeId();
      if (parentId == null || parentId.isBlank() || !newNodeIds.contains(parentId)) {
        surface.add(nodeId);
      }
    }
    return new SubgraphEnds(
        uniqueSurfaceEnd(surface, withIncoming), uniqueSurfaceEnd(surface, withOutgoing));
  }

  /**
   * Rebuilds a connection that used to touch a node the capture replaced outright.
   *
   * <p>Incoming hops attach to the subgraph entry; outgoing hops leave from the exit. A hop whose
   * both ends were replaced is dropped: it lived inside the old element, not among its neighbours.
   */
  public static ChainPlanEdge rewireReplacedEndpoint(
      ChainPlanEdge edge, Set<String> replaced, SubgraphEnds ends, Set<String> availableNodeIds) {
    boolean fromReplaced = replaced.contains(edge.fromNodeId());
    boolean toReplaced = replaced.contains(edge.toNodeId());
    if (fromReplaced && toReplaced) {
      return null;
    }
    String from = fromReplaced ? firstNonBlank(ends.exit(), null) : edge.fromNodeId();
    String to = toReplaced ? firstNonBlank(ends.entry(), null) : edge.toNodeId();
    if (from == null
        || to == null
        || !availableNodeIds.contains(from)
        || !availableNodeIds.contains(to)
        || Objects.equals(from, to)) {
      return null;
    }
    return new ChainPlanEdge(edge.edgeId(), from, to, edge.scopeNodeId());
  }

  /**
   * Rebuilds a dropped connection only when an endpoint actually moved into the new subgraph.
   *
   * <p>Incoming hops attach to the subgraph entry; outgoing hops leave from the exit. A connection
   * whose two ends nested into the same new branch stays as it was: both endpoints are still
   * siblings. Ends that moved into different branches, such as try-2 and finally-2, drop the
   * inherited edge; the wrapper's doFinally hop is catalog-defined.
   *
   * <p>An insertion that keeps the address elements where they are is different. The capture
   * replaces the address edge with the new subgraph, and neither endpoint moves, so putting that
   * edge back would leave the old hop beside the splice.
   */
  public static ChainPlanEdge rewireMovedEndpoint(
      ChainPlanEdge edge,
      Map<String, ChainPlanNode> nodesById,
      Set<String> baseNodeIds,
      SubgraphEnds ends) {
    String fromBranch = newBranchOf(edge.fromNodeId(), nodesById, baseNodeIds);
    String toBranch = newBranchOf(edge.toNodeId(), nodesById, baseNodeIds);
    boolean fromMoved = !Objects.equals(fromBranch, edge.fromNodeId());
    boolean toMoved = !Objects.equals(toBranch, edge.toNodeId());
    if (fromMoved && toMoved) {
      if (Objects.equals(fromBranch, toBranch) && !baseNodeIds.contains(fromBranch)) {
        return edge;
      }
      return null;
    }
    String fromContainer = newContainerOf(edge.fromNodeId(), nodesById, baseNodeIds);
    String toContainer = newContainerOf(edge.toNodeId(), nodesById, baseNodeIds);
    if (!fromMoved && !toMoved) {
      return edge;
    }
    String from = fromMoved ? firstNonBlank(ends.exit(), fromContainer) : edge.fromNodeId();
    String to = toMoved ? firstNonBlank(ends.entry(), toContainer) : edge.toNodeId();
    if (Objects.equals(from, edge.fromNodeId()) && Objects.equals(to, edge.toNodeId())) {
      return edge;
    }
    return new ChainPlanEdge(edge.edgeId(), from, to, edge.scopeNodeId());
  }

  /**
   * The outermost container this write adds above {@code nodeId}, or {@code nodeId} when the node
   * did not move into one.
   */
  private static String newContainerOf(
      String nodeId, Map<String, ChainPlanNode> nodesById, Set<String> baseNodeIds) {
    String outermost = null;
    Set<String> visited = new LinkedHashSet<>();
    ChainPlanNode node = nodesById.get(nodeId);
    while (node != null && visited.add(node.nodeId())) {
      String parentId = node.parentNodeId();
      if (parentId == null || parentId.isBlank()) {
        break;
      }
      if (!baseNodeIds.contains(parentId)) {
        outermost = parentId;
      }
      node = nodesById.get(parentId);
    }
    return outermost == null ? nodeId : outermost;
  }

  /**
   * The first new parent of {@code nodeId}, which is the branch it moved into, or {@code nodeId}
   * when it did not move.
   */
  private static String newBranchOf(
      String nodeId, Map<String, ChainPlanNode> nodesById, Set<String> baseNodeIds) {
    ChainPlanNode node = nodesById.get(nodeId);
    if (node == null) {
      return nodeId;
    }
    String parentId = node.parentNodeId();
    if (parentId == null || parentId.isBlank() || baseNodeIds.contains(parentId)) {
      return nodeId;
    }
    return parentId;
  }

  private static String uniqueSurfaceEnd(Set<String> surface, Set<String> connected) {
    String found = null;
    for (String nodeId : surface) {
      if (connected.contains(nodeId)) {
        continue;
      }
      if (found != null) {
        return null;
      }
      found = nodeId;
    }
    return found;
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }
}
