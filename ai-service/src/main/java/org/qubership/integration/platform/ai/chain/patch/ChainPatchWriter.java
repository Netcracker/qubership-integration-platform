package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
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
 * Writes a chain patch to the catalog, and nothing beyond it.
 *
 * <p>The materializers write every node of the graph handed to them, so what bounds this write is
 * the graph itself: one node per element the patch names. An element the patch adds is written
 * whole; an element the chain already has carries only the property keys the patch changes, so
 * everything else it holds is left to the materializer's merge — including values the plan model
 * cannot express faithfully, such as numbers, which survive precisely because they are never
 * rewritten.
 */
@ApplicationScoped
public class ChainPatchWriter {

  private static final Logger LOG = Logger.getLogger(ChainPatchWriter.class);

  private final ChainPlanPropertiesMaterializer propertiesMaterializer;
  private final ChainPlanSkeletonMaterializer skeletonMaterializer;
  private final ChainPlanConnectionsMaterializer connectionsMaterializer;

  @Inject
  public ChainPatchWriter(
      ChainPlanPropertiesMaterializer propertiesMaterializer,
      ChainPlanSkeletonMaterializer skeletonMaterializer,
      ChainPlanConnectionsMaterializer connectionsMaterializer) {
    this.propertiesMaterializer = Objects.requireNonNull(propertiesMaterializer);
    this.skeletonMaterializer = Objects.requireNonNull(skeletonMaterializer);
    this.connectionsMaterializer = Objects.requireNonNull(connectionsMaterializer);
  }

  public ChainPatchWriteResult write(PatchedChain patched, GraphPatch patch) {
    Objects.requireNonNull(patched, "patched");
    Objects.requireNonNull(patch, "patch");

    List<String> addedNodeIds = addedNodeIds(patched.graph(), patch);
    Map<String, Set<String>> changedKeysByNodeId = changedKeysByNodeId(patch);
    List<ChainPlanEdge> addedEdges = addedEdges(patch);
    if (addedNodeIds.isEmpty() && changedKeysByNodeId.isEmpty()) {
      return new ChainPatchWriteResult(List.of(), List.of(), null);
    }

    Map<String, String> nodeIdToElementId =
        new LinkedHashMap<>(patched.materializationMap().nodeIdToElementId());
    String chainId = patched.materializationMap().chainId();
    List<String> failed = new ArrayList<>();
    String error = null;

    for (String nodeId : addedNodeIds) {
      ChainPlanNode node = node(patched.graph(), nodeId);
      try {
        String elementId =
            skeletonMaterializer.materializeElement(
                patched.graph(), node, chainId, new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId)));
        nodeIdToElementId.put(nodeId, elementId);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Failed to create element for node %s in chain %s", nodeId, chainId);
        failed.add(nodeId);
        error = error == null ? e.getMessage() : error;
      }
    }

    MaterializationMap map = new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId));
    List<String> written = new ArrayList<>(addedNodeIds);
    written.removeAll(failed);
    List<ChainPlanNode> touched = touchedNodes(patched.graph(), written, changedKeysByNodeId);

    if (!touched.isEmpty()) {
      PropertiesApplyResult applied =
          propertiesMaterializer.apply(
              new ChainPlanGraph(
                  patched.graph().schemaVersion(),
                  patched.graph().chain(),
                  List.copyOf(touched),
                  List.of()),
              map);
      failed.addAll(applied.failedNodeIds().stream().filter(id -> !failed.contains(id)).toList());
      error = error == null ? applied.firstValidationError() : error;
    }

    // Connections come last and only on a clean creation: an edge to an element that was not
    // created would be recorded as a failure the reader can do nothing with.
    if (!addedEdges.isEmpty() && failed.isEmpty()) {
      var connected =
          connectionsMaterializer.apply(
              new ChainPlanGraph(
                  patched.graph().schemaVersion(),
                  patched.graph().chain(),
                  patched.graph().nodes(),
                  List.copyOf(addedEdges)),
              map);
      if (!connected.failedEdgeIds().isEmpty() && error == null) {
        error = "connections not created: " + String.join(", ", connected.failedEdgeIds());
      }
    }

    List<String> changed =
        touched.stream().map(ChainPlanNode::nodeId).filter(id -> !failed.contains(id)).toList();
    return new ChainPatchWriteResult(changed, List.copyOf(failed), error);
  }

  /** Added nodes in graph order, which puts a container before the children it holds. */
  private static List<String> addedNodeIds(ChainPlanGraph graph, GraphPatch patch) {
    Set<String> added = new LinkedHashSet<>();
    if (patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        if (nodePatch != null
            && nodePatch.operation() == GraphPatchOperation.ADD
            && nodePatch.node() != null
            && nodePatch.node().nodeId() != null) {
          added.add(nodePatch.node().nodeId());
        }
      }
    }
    if (added.isEmpty()) {
      return List.of();
    }
    return graph.nodes().stream().map(ChainPlanNode::nodeId).filter(added::contains).toList();
  }

  private static List<ChainPlanEdge> addedEdges(GraphPatch patch) {
    if (patch.edgePatches() == null) {
      return List.of();
    }
    return patch.edgePatches().stream()
        .filter(
            edgePatch ->
                edgePatch != null
                    && edgePatch.operation() == GraphPatchOperation.ADD
                    && edgePatch.edge() != null)
        .map(EdgePatch::edge)
        .toList();
  }

  private static Map<String, Set<String>> changedKeysByNodeId(GraphPatch patch) {
    Map<String, Set<String>> changedKeys = new LinkedHashMap<>();
    if (patch.propertyPatches() == null) {
      return changedKeys;
    }
    for (PropertyPatch propertyPatch : patch.propertyPatches()) {
      if (propertyPatch == null
          || propertyPatch.targetNodeId() == null
          || propertyPatch.property() == null
          || propertyPatch.property().key() == null) {
        continue;
      }
      changedKeys
          .computeIfAbsent(propertyPatch.targetNodeId(), nodeId -> new LinkedHashSet<>())
          .add(propertyPatch.property().key());
    }
    return changedKeys;
  }

  private static List<ChainPlanNode> touchedNodes(
      ChainPlanGraph graph, List<String> addedNodeIds, Map<String, Set<String>> changedKeysByNodeId) {
    List<ChainPlanNode> touched = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (addedNodeIds.contains(node.nodeId())) {
        touched.add(node);
        continue;
      }
      Set<String> changedKeys = changedKeysByNodeId.get(node.nodeId());
      if (changedKeys != null) {
        touched.add(withChangedPropertiesOnly(node, changedKeys));
      }
    }
    return touched;
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("patched graph has no node " + nodeId));
  }

  /** The label is dropped so a property edit does not rewrite the element's name as a side effect. */
  private static ChainPlanNode withChangedPropertiesOnly(ChainPlanNode node, Set<String> keys) {
    List<PlanProperty> changed =
        node.properties() == null
            ? List.of()
            : node.properties().stream().filter(property -> keys.contains(property.key())).toList();
    return new ChainPlanNode(node.nodeId(), node.type(), null, node.parentNodeId(), null, changed);
  }
}
