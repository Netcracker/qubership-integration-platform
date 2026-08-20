package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphConnection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;

/**
 * Builds the graph a structural edit proposes from a capture of what it adds.
 *
 * <p>The imported chain arrives whole and leaves whole. An existing element reaches this assembly
 * as an identifier in the branch it moves into, so the only thing that can happen to it is the
 * reparenting the intent already approved. Its type, label, order, and properties are never read
 * from the capture, which is why the refusals {@link ChainEditStructureMerge} still owes the older
 * contract have no subject here.
 *
 * <p>Identifiers of the container and its branches are minted here rather than captured. A capture
 * that named them could collide with an element the chain already has, and nothing downstream needs
 * them to be anything in particular: the catalog creates a container together with its children and
 * Java binds those to the planned nodes by type and order.
 *
 * <p>Connections to the chain around the edit come from {@link ChainEditBoundaryWiring}, so the
 * capture never states where the container attaches. Incoming hops of a moved element arrive at the
 * container, outgoing hops leave from it, and a connection whose two ends both moved is kept as it
 * was.
 */
public final class ChainEditSubgraphAssembly {

  private ChainEditSubgraphAssembly() {}

  /**
   * The graph this edit proposes: the imported chain, plus the captured container and its branches.
   *
   * @throws ChainEditScopeException when the capture describes something the intent did not approve
   */
  public static ChainPlanGraph assemble(
      ChainPlanGraph base, ChainEditSubgraph capture, ChainEditIntent intent) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(intent, "intent");

    Map<String, ChainPlanNode> baseById = baseNodesById(base);
    Set<String> targets = new LinkedHashSet<>(intent.targetNodeIds());
    if (!baseById.keySet().containsAll(targets)) {
      Set<String> missing = new LinkedHashSet<>(targets);
      missing.removeAll(baseById.keySet());
      throw unsatisfiable("unknown structural target ids " + missing);
    }
    String containerType = required(capture.containerType(), "capture names no container type");
    if (capture.branches().isEmpty()) {
      throw correctable("capture names container '" + containerType + "' without a branch");
    }

    Map<String, ChainEditSubgraphBranch> branchOfMovedId =
        movedElements(capture, baseById, targets);
    Set<String> reserved = new LinkedHashSet<>(baseById.keySet());
    Map<String, ChainEditSubgraphElement> newElements = newElements(capture, reserved);
    String containerNodeId = reserveId(containerType, reserved);
    Map<ChainEditSubgraphBranch, String> branchNodeIds = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      String childType = required(branch.childType(), "capture names a branch without a type");
      branchNodeIds.put(branch, reserveId(childType, reserved));
    }

    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode existing : base.nodes()) {
      ChainEditSubgraphBranch branch = branchOfMovedId.get(existing.nodeId());
      nodes.add(branch == null ? existing : reparented(existing, branchNodeIds.get(branch)));
    }
    nodes.add(
        new ChainPlanNode(
            containerNodeId,
            containerType,
            capture.containerLabel(),
            commonParent(branchOfMovedId.keySet(), baseById),
            null,
            List.of()));
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      String branchNodeId = branchNodeIds.get(branch);
      nodes.add(
          new ChainPlanNode(
              branchNodeId,
              branch.childType(),
              branch.label(),
              containerNodeId,
              branch.order(),
              List.copyOf(branch.properties())));
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        nodes.add(
            new ChainPlanNode(
                element.nodeId(), element.type(), element.label(), branchNodeId, null, List.of()));
      }
    }

    Map<String, ChainPlanNode> assembledById = new LinkedHashMap<>();
    nodes.forEach(node -> assembledById.put(node.nodeId(), node));
    Set<String> addedNodeIds = new LinkedHashSet<>();
    addedNodeIds.add(containerNodeId);
    addedNodeIds.addAll(branchNodeIds.values());
    addedNodeIds.addAll(newElements.keySet());
    List<ChainPlanEdge> bodyEdges = bodyEdges(capture, branchNodeIds, newElements, base);
    ChainEditBoundaryWiring.SubgraphEnds ends =
        ChainEditBoundaryWiring.deriveSubgraphEnds(addedNodeIds, bodyEdges, assembledById);

    List<ChainPlanEdge> edges = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      ChainPlanEdge rewired =
          ChainEditBoundaryWiring.rewireMovedEndpoint(
              existing, assembledById, baseById.keySet(), ends);
      ChainPlanEdge kept = rewired == null ? existing : rewired;
      if (connections.add(connectionKey(kept))) {
        edges.add(kept);
      }
    }
    edges.addAll(bodyEdges);
    return new ChainPlanGraph(
        base.schemaVersion(), base.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  /**
   * The branch each named element moves into.
   *
   * <p>Checked against the intent rather than trusted: the edit already knows which elements it
   * wraps, and a capture that moves one more encloses an element nobody approved. A capture that
   * moves one fewer leaves the reader with a wrapper around less than they asked for, so both
   * directions are refused while the generator can still correct them.
   */
  private static Map<String, ChainEditSubgraphBranch> movedElements(
      ChainEditSubgraph capture, Map<String, ChainPlanNode> baseById, Set<String> targets) {
    Map<String, ChainEditSubgraphBranch> branchOfMovedId = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      for (String nodeId : branch.moveExisting()) {
        if (nodeId == null || nodeId.isBlank()) {
          throw correctable("capture moves an element without naming it");
        }
        if (!baseById.containsKey(nodeId)) {
          throw correctable("capture moves '" + nodeId + "', which the chain does not have");
        }
        if (!targets.contains(nodeId)) {
          throw correctable("capture moves '" + nodeId + "', which this edit does not name");
        }
        if (branchOfMovedId.put(nodeId, branch) != null) {
          throw correctable("capture moves '" + nodeId + "' into more than one branch");
        }
      }
    }
    if (!branchOfMovedId.keySet().containsAll(targets)) {
      Set<String> left = new LinkedHashSet<>(targets);
      left.removeAll(branchOfMovedId.keySet());
      throw correctable("capture leaves out the elements this edit names: " + left);
    }
    return branchOfMovedId;
  }

  private static Map<String, ChainEditSubgraphElement> newElements(
      ChainEditSubgraph capture, Set<String> reserved) {
    Map<String, ChainEditSubgraphElement> byId = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        String nodeId = required(element.nodeId(), "capture creates an element without an id");
        if (reserved.contains(nodeId)) {
          throw correctable("capture creates '" + nodeId + "', an id the chain already uses");
        }
        if (byId.put(nodeId, element) != null) {
          throw correctable("capture creates '" + nodeId + "' twice");
        }
        required(element.type(), "capture creates '" + nodeId + "' without a type");
      }
    }
    reserved.addAll(byId.keySet());
    return byId;
  }

  /**
   * Connections inside the branches, scoped to the branch that declared them.
   *
   * <p>A connection reaching outside its own body is refused rather than dropped. Branches do not
   * connect to each other, and a capture that wires one to another has described a flow the reader
   * would not recognize from their request.
   */
  private static List<ChainPlanEdge> bodyEdges(
      ChainEditSubgraph capture,
      Map<ChainEditSubgraphBranch, String> branchNodeIds,
      Map<String, ChainEditSubgraphElement> newElements,
      ChainPlanGraph base) {
    Set<String> edgeIds = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      edgeIds.add(existing.edgeId());
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      Set<String> withinBranch = new LinkedHashSet<>();
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        withinBranch.add(element.nodeId());
      }
      for (ChainEditSubgraphConnection connection : branch.body().connections()) {
        String from = requireWithinBranch(connection.fromNodeId(), withinBranch, newElements);
        String to = requireWithinBranch(connection.toNodeId(), withinBranch, newElements);
        edges.add(
            new ChainPlanEdge(
                reserveId(from + "-to-" + to, edgeIds), from, to, branchNodeIds.get(branch)));
      }
    }
    return List.copyOf(edges);
  }

  private static String requireWithinBranch(
      String nodeId, Set<String> withinBranch, Map<String, ChainEditSubgraphElement> newElements) {
    if (nodeId == null || nodeId.isBlank()) {
      throw correctable("capture connects a branch element to nothing");
    }
    if (!withinBranch.contains(nodeId)) {
      String reason =
          newElements.containsKey(nodeId)
              ? "which another branch creates"
              : "which this branch does not create";
      throw correctable("capture connects '" + nodeId + "', " + reason);
    }
    return nodeId;
  }

  /**
   * The container the wrapper itself lands in, taken from the elements moving into it.
   *
   * <p>Wrapping an element that already sits inside a container leaves the wrapper in that
   * container. Elements from different containers share none, so the wrapper goes to chain root and
   * the reader sees where it landed before approving.
   */
  private static String commonParent(
      Collection<String> movedIds, Map<String, ChainPlanNode> baseById) {
    String shared = null;
    for (String nodeId : movedIds) {
      String parent = baseById.get(nodeId).parentNodeId();
      if (parent == null || parent.isBlank()) {
        return null;
      }
      if (shared != null && !shared.equals(parent)) {
        return null;
      }
      shared = parent;
    }
    return shared;
  }

  private static ChainPlanNode reparented(ChainPlanNode existing, String parentNodeId) {
    return new ChainPlanNode(
        existing.nodeId(),
        existing.type(),
        existing.label(),
        parentNodeId,
        existing.order(),
        existing.properties());
  }

  /** An id no element of the chain and no other part of this capture holds. */
  private static String reserveId(String stem, Set<String> reserved) {
    int index = 1;
    String candidate = stem + "-" + index;
    while (!reserved.add(candidate)) {
      index++;
      candidate = stem + "-" + index;
    }
    return candidate;
  }

  private static Map<String, ChainPlanNode> baseNodesById(ChainPlanGraph base) {
    if (base.nodes() == null || base.nodes().isEmpty()) {
      throw unsatisfiable("the edited chain has no elements");
    }
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    for (ChainPlanNode node : base.nodes()) {
      byId.put(node.nodeId(), node);
    }
    return byId;
  }

  private static List<ChainPlanEdge> baseEdges(ChainPlanGraph base) {
    return base.edges() == null ? List.of() : base.edges();
  }

  private static String connectionKey(ChainPlanEdge edge) {
    return edge.fromNodeId() + " " + edge.toNodeId();
  }

  private static String required(String value, String message) {
    if (value == null || value.isBlank()) {
      throw correctable(message);
    }
    return value;
  }

  private static ChainEditScopeException correctable(String message) {
    return new ChainEditScopeException(captureMessage(message), false);
  }

  /**
   * A refusal the generator cannot answer, because the intent names something the edited chain does
   * not hold. Asking for the capture again cannot change that.
   */
  private static ChainEditScopeException unsatisfiable(String message) {
    return new ChainEditScopeException(captureMessage(message), true);
  }

  private static String captureMessage(String message) {
    return "edit structure does not describe the approved change: " + message;
  }
}
