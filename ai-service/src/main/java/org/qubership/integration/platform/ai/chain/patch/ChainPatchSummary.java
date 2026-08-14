package org.qubership.integration.platform.ai.chain.patch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Names the element rather than its id, and shows what each property holds now beside what it
 * would hold, because that comparison is the only thing that makes a generated script body
 * judgeable without opening the element.
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
    if (addedNodes.isEmpty()
        && addedEdges.isEmpty()
        && changedProperties.isEmpty()
        && removedNodeIds.isEmpty()
        && removedEdgeIds.isEmpty()) {
      return "The change is empty: nothing would be written.";
    }
    Map<String, ChainPlanNode> nodesById = nodesById(before);
    for (NodePatch nodePatch : addedNodes) {
      nodesById.put(nodePatch.node().nodeId(), nodePatch.node());
    }

    StringBuilder text = new StringBuilder();
    if (patch.rationale() != null && !patch.rationale().isBlank()) {
      text.append(patch.rationale().strip()).append("\n\n");
    }

    // Removals lead: they are the part of a change a reader cannot get back, and burying them
    // under a list of additions is how a card gets answered without being read.
    for (String nodeId : removedNodeIds) {
      text.append("Removes ").append(elementLabel(nodesById.get(nodeId), nodeId)).append("\n\n");
    }
    for (String edgeId : removedEdgeIds) {
      text.append("Disconnects ").append(edgeLabel(before, nodesById, edgeId)).append("\n\n");
    }
    if (!removedNodeIds.isEmpty()) {
      text.append("Removing cannot be undone. To keep a way back, save a snapshot first.\n\n");
    }

    for (NodePatch nodePatch : addedNodes) {
      text.append("Adds ")
          .append(elementLabel(nodePatch.node(), nodePatch.node().nodeId()))
          .append("\n\n");
    }
    for (EdgePatch edgePatch : addedEdges) {
      text.append("Connects ")
          .append(elementLabel(nodesById.get(edgePatch.edge().fromNodeId()), edgePatch.edge().fromNodeId()))
          .append(" to ")
          .append(elementLabel(nodesById.get(edgePatch.edge().toNodeId()), edgePatch.edge().toNodeId()))
          .append("\n\n");
    }
    for (PropertyPatch propertyPatch : changedProperties) {
      if (propertyPatch == null
          || propertyPatch.property() == null
          || propertyPatch.property().key() == null) {
        continue;
      }
      ChainPlanNode node = nodesById.get(propertyPatch.targetNodeId());
      text.append(elementLabel(node, propertyPatch.targetNodeId()))
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
