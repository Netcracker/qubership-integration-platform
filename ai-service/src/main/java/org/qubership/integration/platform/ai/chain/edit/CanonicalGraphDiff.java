package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * The one patch that turns the imported chain into the compiled one.
 *
 * <p>A compiler run applies several generator patches in order, and replaying that sequence at the
 * catalog would show the reader intermediate states nobody asked to approve. Diffing the two end
 * graphs instead gives a single change: the same thing the decision card describes, the proposal
 * digest covers, and the writer sends. Anything a generator wrote and then rewrote never appears.
 *
 * <p>Node identity fields and node properties are separated deliberately. An {@code UPDATE} node
 * patch merges properties rather than replacing them, so a dropped property key can only be
 * expressed as a property {@code REMOVE}.
 */
public final class CanonicalGraphDiff {

  private CanonicalGraphDiff() {}

  public static GraphPatch between(
      ChainPlanGraph base,
      ChainPlanGraph result,
      String patchId,
      String ownerCapabilityId,
      String rationale) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(result, "result");

    Map<String, ChainPlanNode> baseNodes = nodesById(base);
    Map<String, ChainPlanNode> resultNodes = nodesById(result);
    List<NodePatch> nodePatches = new ArrayList<>();
    List<PropertyPatch> propertyPatches = new ArrayList<>();

    for (Map.Entry<String, ChainPlanNode> entry : resultNodes.entrySet()) {
      ChainPlanNode before = baseNodes.get(entry.getKey());
      ChainPlanNode after = entry.getValue();
      if (before == null) {
        nodePatches.add(new NodePatch(GraphPatchOperation.ADD, after, null));
        continue;
      }
      if (identityChanged(before, after)) {
        nodePatches.add(
            new NodePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanNode(
                    after.nodeId(),
                    after.type(),
                    after.label(),
                    after.parentNodeId(),
                    after.order(),
                    List.of()),
                after.nodeId()));
      }
      propertyPatches.addAll(propertyDelta(before, after));
    }
    for (String removedId : baseNodes.keySet()) {
      if (!resultNodes.containsKey(removedId)) {
        nodePatches.add(new NodePatch(GraphPatchOperation.REMOVE, null, removedId));
      }
    }

    Map<String, ChainPlanEdge> baseEdges = edgesById(base);
    Map<String, ChainPlanEdge> resultEdges = edgesById(result);
    List<EdgePatch> edgePatches = new ArrayList<>();
    for (Map.Entry<String, ChainPlanEdge> entry : resultEdges.entrySet()) {
      ChainPlanEdge before = baseEdges.get(entry.getKey());
      ChainPlanEdge after = entry.getValue();
      if (before == null) {
        edgePatches.add(new EdgePatch(GraphPatchOperation.ADD, after, null));
      } else if (!before.equals(after)) {
        edgePatches.add(new EdgePatch(GraphPatchOperation.UPDATE, after, after.edgeId()));
      }
    }
    for (String removedId : baseEdges.keySet()) {
      if (!resultEdges.containsKey(removedId)) {
        edgePatches.add(new EdgePatch(GraphPatchOperation.REMOVE, null, removedId));
      }
    }

    return new GraphPatch(
        patchId,
        ownerCapabilityId,
        List.copyOf(nodePatches),
        List.copyOf(edgePatches),
        List.copyOf(propertyPatches),
        List.of(),
        List.of(),
        rationale);
  }

  /** Whether the diff found nothing to change. */
  public static boolean isEmpty(GraphPatch patch) {
    return patch.nodePatches().isEmpty()
        && patch.edgePatches().isEmpty()
        && patch.propertyPatches().isEmpty();
  }

  private static boolean identityChanged(ChainPlanNode before, ChainPlanNode after) {
    return !Objects.equals(before.type(), after.type())
        || !Objects.equals(before.label(), after.label())
        || !Objects.equals(before.parentNodeId(), after.parentNodeId())
        || !Objects.equals(before.order(), after.order());
  }

  private static List<PropertyPatch> propertyDelta(ChainPlanNode before, ChainPlanNode after) {
    Map<String, String> baseProperties = propertiesByKey(before);
    Map<String, String> resultProperties = propertiesByKey(after);
    List<PropertyPatch> patches = new ArrayList<>();
    for (Map.Entry<String, String> entry : resultProperties.entrySet()) {
      String key = entry.getKey();
      if (!baseProperties.containsKey(key)) {
        patches.add(
            new PropertyPatch(
                GraphPatchOperation.ADD, after.nodeId(), new PlanProperty(key, entry.getValue())));
      } else if (!Objects.equals(baseProperties.get(key), entry.getValue())) {
        patches.add(
            new PropertyPatch(
                GraphPatchOperation.UPDATE,
                after.nodeId(),
                new PlanProperty(key, entry.getValue())));
      }
    }
    for (Map.Entry<String, String> entry : baseProperties.entrySet()) {
      if (!resultProperties.containsKey(entry.getKey())) {
        patches.add(
            new PropertyPatch(
                GraphPatchOperation.REMOVE,
                after.nodeId(),
                new PlanProperty(entry.getKey(), entry.getValue())));
      }
    }
    return patches;
  }

  private static Map<String, ChainPlanNode> nodesById(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    if (graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        if (node != null && node.nodeId() != null) {
          byId.put(node.nodeId(), node);
        }
      }
    }
    return byId;
  }

  private static Map<String, ChainPlanEdge> edgesById(ChainPlanGraph graph) {
    Map<String, ChainPlanEdge> byId = new LinkedHashMap<>();
    if (graph.edges() != null) {
      for (ChainPlanEdge edge : graph.edges()) {
        if (edge != null && edge.edgeId() != null) {
          byId.put(edge.edgeId(), edge);
        }
      }
    }
    return byId;
  }

  private static Map<String, String> propertiesByKey(ChainPlanNode node) {
    Map<String, String> byKey = new LinkedHashMap<>();
    if (node.properties() != null) {
      for (PlanProperty property : node.properties()) {
        if (property != null && property.key() != null) {
          byKey.put(property.key(), property.value());
        }
      }
    }
    return byKey;
  }
}
