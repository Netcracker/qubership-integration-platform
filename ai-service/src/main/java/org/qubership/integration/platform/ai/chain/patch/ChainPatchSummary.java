package org.qubership.integration.platform.ai.chain.patch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * Describes a proposed patch to the reader answering the card.
 *
 * <p>Renders the plan as a numbered Markdown list with the action verb of each item in bold, so
 * the reader can scan what happens without reading every line. Names the element rather than its
 * id, and shows what each property holds now beside what it would hold, because that comparison
 * is the only thing that makes a generated script body judgeable without opening the element.
 *
 * <p>A node patch that changes {@code parentNodeId} renders as a move into the new container,
 * since that is the whole point of a wrap: the moved element is otherwise indistinguishable from
 * an unrelated addition.
 */
public final class ChainPatchSummary {

  private static final int MAX_VALUE_CHARS = 400;

  private ChainPatchSummary() {}

  public static String describe(ChainPlanGraph before, GraphPatch patch) {
    List<NodePatch> addedNodes = added(patch.nodePatches(), NodePatch::operation);
    List<EdgePatch> addedEdges = added(patch.edgePatches(), EdgePatch::operation);
    List<String> removedNodeIds = removedTargets(patch.nodePatches(), NodePatch::operation, NodePatch::targetNodeId);
    List<String> removedEdgeIds = removedTargets(patch.edgePatches(), EdgePatch::operation, EdgePatch::targetEdgeId);
    List<PropertyPatch> changedProperties =
        patch.propertyPatches() == null ? List.of() : patch.propertyPatches();
    Map<String, ChainPlanNode> nodesById = nodesById(before);
    List<NodePatch> movedNodes = movedNodes(patch.nodePatches(), nodesById);
    if (addedNodes.isEmpty()
        && addedEdges.isEmpty()
        && changedProperties.isEmpty()
        && removedNodeIds.isEmpty()
        && removedEdgeIds.isEmpty()
        && movedNodes.isEmpty()) {
      return "The change is empty: nothing would be written.";
    }
    for (NodePatch nodePatch : addedNodes) {
      nodesById.put(nodePatch.node().nodeId(), nodePatch.node());
    }

    StringBuilder text = new StringBuilder();
    if (patch.rationale() != null && !patch.rationale().isBlank()) {
      text.append(patch.rationale().strip()).append("\n\n");
    }

    int itemNumber = 0;

    // Removals lead: they are the part of a change a reader cannot get back, and burying them
    // under a list of additions is how a card gets answered without being read.
    for (String nodeId : removedNodeIds) {
      text.append(++itemNumber)
          .append(". **Removes** ")
          .append(elementLabel(nodesById.get(nodeId), nodeId))
          .append("\n\n");
    }
    for (String edgeId : removedEdgeIds) {
      text.append(++itemNumber)
          .append(". **Disconnects** ")
          .append(edgeLabel(before, nodesById, edgeId))
          .append("\n\n");
    }
    if (!removedNodeIds.isEmpty()) {
      text.append("Removing cannot be undone. To keep a way back, save a snapshot first.\n\n");
    }

    for (NodePatch nodePatch : addedNodes) {
      text.append(++itemNumber)
          .append(". **Adds** ")
          .append(elementLabel(nodePatch.node(), nodePatch.node().nodeId()))
          .append("\n\n");
    }
    for (EdgePatch edgePatch : addedEdges) {
      text.append(++itemNumber)
          .append(". **Connects** ")
          .append(elementLabel(nodesById.get(edgePatch.edge().fromNodeId()), edgePatch.edge().fromNodeId()))
          .append(" to ")
          .append(elementLabel(nodesById.get(edgePatch.edge().toNodeId()), edgePatch.edge().toNodeId()))
          .append("\n\n");
    }
    for (NodePatch movePatch : movedNodes) {
      text.append(++itemNumber)
          .append(". **Moves** ")
          .append(elementLabel(nodesById.get(movePatch.targetNodeId()), movePatch.targetNodeId()))
          .append(" into ")
          .append(containerLabel(nodesById, movePatch.node().parentNodeId()))
          .append("\n\n");
    }
    for (PropertyPatch propertyPatch : changedProperties) {
      if (propertyPatch == null
          || propertyPatch.property() == null
          || propertyPatch.property().key() == null) {
        continue;
      }
      ChainPlanNode node = nodesById.get(propertyPatch.targetNodeId());
      text.append(++itemNumber)
          .append(". **Updates** ")
          .append(elementLabel(node, propertyPatch.targetNodeId()))
          .append(" — ")
          .append(propertyPatch.property().key())
          .append("\n\nnow:\n")
          .append(block(currentValue(node, propertyPatch.property().key())))
          .append("\n\nafter:\n")
          .append(block(propertyPatch.property().value()))
          .append("\n\n");
    }
    text.append("Apply this to the chain?");
    return text.toString();
  }

  private static <T> List<T> added(List<T> patches, Function<T, GraphPatchOperation> operation) {
    if (patches == null) {
      return List.of();
    }
    return patches.stream()
        .filter(patch -> patch != null && operation.apply(patch) == GraphPatchOperation.ADD)
        .filter(ChainPatchSummary::hasBody)
        .toList();
  }

  private static <T> List<String> removedTargets(
      List<T> patches, Function<T, GraphPatchOperation> operation, Function<T, String> target) {
    if (patches == null) {
      return List.of();
    }
    return patches.stream()
        .filter(patch -> patch != null && operation.apply(patch) == GraphPatchOperation.REMOVE)
        .map(target)
        .filter(id -> id != null && !id.isBlank())
        .toList();
  }

  /** Names both ends of a connection, so "disconnects" reads as something a person can picture. */
  private static String edgeLabel(
      ChainPlanGraph before, Map<String, ChainPlanNode> nodesById, String edgeId) {
    if (before.edges() != null) {
      for (ChainPlanEdge edge : before.edges()) {
        if (edge != null && edgeId.equals(edge.edgeId())) {
          return elementLabel(nodesById.get(edge.fromNodeId()), edge.fromNodeId())
              + " from "
              + elementLabel(nodesById.get(edge.toNodeId()), edge.toNodeId());
        }
      }
    }
    return "connection " + edgeId;
  }

  /**
   * A node patch of operation {@code UPDATE} whose {@code parentNodeId} differs from the node's
   * parent in the "before" graph moves that node into a new container -- the whole point of a
   * wrap, and otherwise unrenderable.
   */
  private static List<NodePatch> movedNodes(
      List<NodePatch> nodePatches, Map<String, ChainPlanNode> beforeNodesById) {
    if (nodePatches == null) {
      return List.of();
    }
    return nodePatches.stream()
        .filter(
            patch ->
                patch != null
                    && patch.operation() == GraphPatchOperation.UPDATE
                    && patch.node() != null
                    && patch.targetNodeId() != null)
        .filter(patch -> isParentTransfer(patch, beforeNodesById))
        .toList();
  }

  private static boolean isParentTransfer(NodePatch patch, Map<String, ChainPlanNode> beforeNodesById) {
    ChainPlanNode before = beforeNodesById.get(patch.targetNodeId());
    return before != null && !Objects.equals(before.parentNodeId(), patch.node().parentNodeId());
  }

  private static String containerLabel(Map<String, ChainPlanNode> nodesById, String containerNodeId) {
    if (containerNodeId == null) {
      return "the chain root";
    }
    return elementLabel(nodesById.get(containerNodeId), containerNodeId);
  }

  private static boolean hasBody(Object patch) {
    if (patch instanceof NodePatch nodePatch) {
      return nodePatch.node() != null && nodePatch.node().nodeId() != null;
    }
    if (patch instanceof EdgePatch edgePatch) {
      return edgePatch.edge() != null;
    }
    return false;
  }

  private static String elementLabel(ChainPlanNode node, String nodeId) {
    if (node == null) {
      return "Element " + nodeId;
    }
    String label = node.label() == null || node.label().isBlank() ? node.nodeId() : node.label();
    return node.type() == null ? label : label + " (" + node.type() + ")";
  }

  private static String currentValue(ChainPlanNode node, String key) {
    if (node == null || node.properties() == null) {
      return null;
    }
    return node.properties().stream()
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }

  private static String block(String value) {
    if (value == null) {
      return "(not set)";
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return "(empty)";
    }
    if (trimmed.length() > MAX_VALUE_CHARS) {
      trimmed = trimmed.substring(0, MAX_VALUE_CHARS) + "\n… (" + value.length() + " characters)";
    }
    return "```\n" + trimmed + "\n```";
  }

  private static Map<String, ChainPlanNode> nodesById(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> index = new LinkedHashMap<>();
    List<ChainPlanNode> nodes = graph.nodes() == null ? List.of() : graph.nodes();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null) {
        index.put(node.nodeId(), node);
      }
    }
    return index;
  }
}
