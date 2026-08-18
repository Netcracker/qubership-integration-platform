package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.edit.CanonicalGraphDiff;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflight;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ProjectionAction;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;
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
 * One catalog graph materialization boundary for CREATE and EDIT.
 *
 * <p>Work is derived from the difference between the current and desired graphs. Callers keep
 * publication, checkpoints, approval, and digest outside this type.
 */
@ApplicationScoped
public class CatalogGraphMaterializer {

  private static final Logger LOG = Logger.getLogger(CatalogGraphMaterializer.class);

  private final ChainPlanPropertiesMaterializer propertiesMaterializer;
  private final ChainPlanSkeletonMaterializer skeletonMaterializer;
  private final ChainPlanConnectionsMaterializer connectionsMaterializer;
  private final ChainPlanRemovalsMaterializer removalsMaterializer;
  private final CatalogRestClient catalogRestClient;
  private final CatalogElementDescriptorLoader descriptorLoader;

  @Inject
  public CatalogGraphMaterializer(
      ChainPlanPropertiesMaterializer propertiesMaterializer,
      ChainPlanSkeletonMaterializer skeletonMaterializer,
      ChainPlanConnectionsMaterializer connectionsMaterializer,
      ChainPlanRemovalsMaterializer removalsMaterializer,
      @RestClient CatalogRestClient catalogRestClient,
      CatalogElementDescriptorLoader descriptorLoader) {
    this.propertiesMaterializer = Objects.requireNonNull(propertiesMaterializer);
    this.skeletonMaterializer = Objects.requireNonNull(skeletonMaterializer);
    this.connectionsMaterializer = Objects.requireNonNull(connectionsMaterializer);
    this.removalsMaterializer = Objects.requireNonNull(removalsMaterializer);
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient);
    this.descriptorLoader = Objects.requireNonNull(descriptorLoader);
  }

  public CatalogGraphMaterializeResult apply(
      String chainId,
      ChainPlanGraph currentGraph,
      ChainPlanGraph desiredGraph,
      MaterializationMap materializationMap) {
    Objects.requireNonNull(chainId, "chainId");
    Objects.requireNonNull(currentGraph, "currentGraph");
    Objects.requireNonNull(desiredGraph, "desiredGraph");
    Objects.requireNonNull(materializationMap, "materializationMap");

    GraphPatch patch =
        CanonicalGraphDiff.between(
            currentGraph, desiredGraph, "materialize", "catalog-graph-materializer", "");

    if (CanonicalGraphDiff.isEmpty(patch)) {
      return CatalogGraphMaterializeResult.noOp(
          new MaterializationMap(chainId, copyMap(materializationMap)));
    }

    List<String> addedNodeIds = addedNodeIds(desiredGraph, patch);
    Map<String, Set<String>> changedKeysByNodeId = changedKeysByNodeId(patch);
    List<ChainPlanEdge> addedEdges = addedEdges(patch);
    List<EdgeReplacement> replacements = edgeReplacements(patch, currentGraph);
    List<ParentTransfer> parentTransfers = parentTransfers(patch, currentGraph);
    boolean removesSomething =
        !removedNodeIds(patch).isEmpty() || !removedEdges(patch, currentGraph).isEmpty();

    CatalogElementDescriptorCache cache = new CatalogElementDescriptorCache(descriptorLoader);
    try {
      new DesiredGraphDescriptorPreflight().validate(desiredGraph, currentGraph, cache);
    } catch (DesiredGraphDescriptorPreflightException e) {
      return failedPreflight(chainId, materializationMap, e.getMessage());
    }

    Map<String, String> nodeIdToElementId =
        new LinkedHashMap<>(materializationMap.nodeIdToElementId());
    List<String> failed = new ArrayList<>();
    String error = null;

    for (String nodeId : addedNodeIds) {
      if (nodeIdToElementId.containsKey(nodeId)) {
        continue;
      }
      ChainPlanNode node = node(desiredGraph, nodeId);
      try {
        String elementId =
            skeletonMaterializer.materializeElement(
                desiredGraph, node, chainId, new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId)));
        nodeIdToElementId.put(nodeId, elementId);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Failed to create element for node %s in chain %s", nodeId, chainId);
        failed.add(nodeId);
        error = error == null ? e.getMessage() : error;
      }
    }

    MaterializationMap map = new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId));
    error = error == null ? invalidReplacementError(replacements, currentGraph, desiredGraph, map) : error;
    replacements = catalogReplacements(replacements, currentGraph, desiredGraph, map);
    List<String> written = new ArrayList<>(addedNodeIds);
    written.removeAll(failed);
    List<ChainPlanNode> touched = touchedNodes(desiredGraph, written, changedKeysByNodeId);

    if (!touched.isEmpty()) {
      PropertiesApplyResult applied =
          propertiesMaterializer.apply(
              new ChainPlanGraph(
                  desiredGraph.schemaVersion(),
                  desiredGraph.chain(),
                  List.copyOf(touched),
                  List.of()),
              map);
      failed.addAll(applied.failedNodeIds().stream().filter(id -> !failed.contains(id)).toList());
      error = error == null ? applied.firstValidationError() : error;
    }

    if (!parentTransfers.isEmpty() && failed.isEmpty() && error == null) {
      error = preflightTransfers(parentTransfers, desiredGraph, map, cache);
    }

    List<ChainPlanEdge> recreatableEdges = new ArrayList<>();
    List<ChainPlanEdge> replacedOldEdges =
        replacements.stream().map(EdgeReplacement::before).filter(Objects::nonNull).toList();
    if (!replacedOldEdges.isEmpty() && failed.isEmpty() && error == null) {
      var removed = removalsMaterializer.apply(currentGraph, Set.of(), replacedOldEdges, map);
      if (!removed.removedDependencyIds().isEmpty()) {
        recreatableEdges.addAll(replacedOldEdges);
      }
      failed.addAll(removed.failedNodeIds().stream().filter(id -> !failed.contains(id)).toList());
      error = error == null ? removed.error() : error;
    }

    if (!parentTransfers.isEmpty() && failed.isEmpty() && error == null) {
      error = transferGroupedByParent(parentTransfers, map);
    }

    List<ChainPlanEdge> edgesToCreate = new ArrayList<>(addedEdges);
    if (failed.isEmpty() && error == null) {
      replacements.stream()
          .map(EdgeReplacement::after)
          .filter(Objects::nonNull)
          .forEach(edgesToCreate::add);
    }
    List<ChainPlanEdge> createdEdges = List.of();
    if (!edgesToCreate.isEmpty() && failed.isEmpty() && error == null) {
      var connected =
          connectionsMaterializer.apply(
              new ChainPlanGraph(
                  desiredGraph.schemaVersion(),
                  desiredGraph.chain(),
                  desiredGraph.nodes(),
                  List.copyOf(edgesToCreate)),
              map);
      if (connected.failedEdgeIds().isEmpty()) {
        createdEdges = List.copyOf(edgesToCreate);
      } else {
        error = "connections not created: " + String.join(", ", connected.failedEdgeIds());
        createdEdges =
            edgesToCreate.stream()
                .filter(edge -> !connected.failedEdgeIds().contains(edge.edgeId()))
                .toList();
      }
    }

    List<String> removedElementIds = List.of();
    if (failed.isEmpty() && error == null && removesSomething) {
      Set<String> removedNodeIds = removedNodeIds(patch);
      List<ChainPlanEdge> removedEdges = removedEdges(patch, currentGraph);
      if (!removedNodeIds.isEmpty() || !removedEdges.isEmpty()) {
        var removed = removalsMaterializer.apply(currentGraph, removedNodeIds, removedEdges, map);
        removedElementIds = removed.removedElementIds();
        if (!removed.removedDependencyIds().isEmpty()) {
          recreatableEdges.addAll(removedEdges);
        }
        failed.addAll(removed.failedNodeIds().stream().filter(id -> !failed.contains(id)).toList());
        error = error == null ? removed.error() : error;
      }
    }

    List<String> changed =
        new ArrayList<>(
            touched.stream()
                .map(ChainPlanNode::nodeId)
                .filter(id -> !failed.contains(id))
                .toList());
    if (error == null) {
      for (ParentTransfer transfer : parentTransfers) {
        if (!failed.contains(transfer.nodeId()) && !changed.contains(transfer.nodeId())) {
          changed.add(transfer.nodeId());
        }
      }
    }

    return new CatalogGraphMaterializeResult(
        map,
        List.copyOf(changed),
        List.copyOf(failed),
        error,
        List.copyOf(removedElementIds),
        List.copyOf(written),
        changedKeysByNodeId,
        createdEdges,
        recreatableEdges,
        false);
  }

  /** Empty current graph for CREATE after chain publication. */
  public static ChainPlanGraph emptyCurrent(ChainPlanGraph desired) {
    return new ChainPlanGraph(desired.schemaVersion(), desired.chain(), List.of(), List.of());
  }

  private static CatalogGraphMaterializeResult failedPreflight(
      String chainId, MaterializationMap materializationMap, String message) {
    return new CatalogGraphMaterializeResult(
        new MaterializationMap(chainId, copyMap(materializationMap)),
        List.of(),
        List.of(),
        message,
        List.of(),
        List.of(),
        Map.of(),
        List.of(),
        List.of(),
        false);
  }

  private static Map<String, String> copyMap(MaterializationMap map) {
    return map.nodeIdToElementId() == null ? Map.of() : Map.copyOf(map.nodeIdToElementId());
  }

  private static Set<String> removedNodeIds(GraphPatch patch) {
    Set<String> removed = new LinkedHashSet<>();
    if (patch.nodePatches() == null) {
      return removed;
    }
    for (NodePatch nodePatch : patch.nodePatches()) {
      if (nodePatch != null
          && nodePatch.operation() == GraphPatchOperation.REMOVE
          && nodePatch.targetNodeId() != null) {
        removed.add(nodePatch.targetNodeId());
      }
    }
    return removed;
  }

  private static List<ChainPlanEdge> removedEdges(GraphPatch patch, ChainPlanGraph before) {
    if (patch.edgePatches() == null || before == null || before.edges() == null) {
      return List.of();
    }
    Set<String> removedEdgeIds = new LinkedHashSet<>();
    for (EdgePatch edgePatch : patch.edgePatches()) {
      if (edgePatch != null
          && edgePatch.operation() == GraphPatchOperation.REMOVE
          && edgePatch.targetEdgeId() != null) {
        removedEdgeIds.add(edgePatch.targetEdgeId());
      }
    }
    return before.edges().stream().filter(edge -> removedEdgeIds.contains(edge.edgeId())).toList();
  }

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
    List<String> inGraphOrder =
        graph.nodes().stream().map(ChainPlanNode::nodeId).filter(added::contains).toList();
    return parentsFirst(graph, inGraphOrder);
  }

  private static List<String> parentsFirst(ChainPlanGraph graph, List<String> addedNodeIds) {
    Map<String, String> parentById = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        parentById.put(node.nodeId(), node.parentNodeId());
      }
    }
    List<String> ordered = new ArrayList<>(addedNodeIds.size());
    Set<String> placed = new LinkedHashSet<>();
    for (int round = 0; round < addedNodeIds.size() && placed.size() < addedNodeIds.size(); round++) {
      for (String nodeId : addedNodeIds) {
        if (placed.contains(nodeId)) {
          continue;
        }
        String parentId = parentById.get(nodeId);
        boolean waitsForParent =
            parentId != null && addedNodeIds.contains(parentId) && !placed.contains(parentId);
        if (!waitsForParent) {
          ordered.add(nodeId);
          placed.add(nodeId);
        }
      }
    }
    for (String nodeId : addedNodeIds) {
      if (placed.add(nodeId)) {
        ordered.add(nodeId);
      }
    }
    return List.copyOf(ordered);
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

  private static List<EdgeReplacement> edgeReplacements(GraphPatch patch, ChainPlanGraph before) {
    if (patch.edgePatches() == null || before == null || before.edges() == null) {
      return List.of();
    }
    Map<String, ChainPlanEdge> beforeById = new LinkedHashMap<>();
    for (ChainPlanEdge edge : before.edges()) {
      if (edge != null && edge.edgeId() != null) {
        beforeById.put(edge.edgeId(), edge);
      }
    }
    List<EdgeReplacement> replacements = new ArrayList<>();
    for (EdgePatch edgePatch : patch.edgePatches()) {
      if (edgePatch == null
          || edgePatch.operation() != GraphPatchOperation.UPDATE
          || edgePatch.edge() == null) {
        continue;
      }
      String edgeId =
          edgePatch.targetEdgeId() != null ? edgePatch.targetEdgeId() : edgePatch.edge().edgeId();
      ChainPlanEdge previous = edgeId == null ? null : beforeById.get(edgeId);
      if (previous == null) {
        continue;
      }
      replacements.add(new EdgeReplacement(previous, edgePatch.edge()));
    }
    return List.copyOf(replacements);
  }

  private static String invalidReplacementError(
      List<EdgeReplacement> replacements,
      ChainPlanGraph current,
      ChainPlanGraph desired,
      MaterializationMap map) {
    for (EdgeReplacement replacement : replacements) {
      var oldProjection =
          ChainPlanConnectionsMaterializer.project(replacement.before(), current, map);
      var newProjection =
          ChainPlanConnectionsMaterializer.project(replacement.after(), desired, map);
      if (oldProjection.action() == ProjectionAction.FAIL_INVALID
          || newProjection.action() == ProjectionAction.FAIL_INVALID) {
        String edgeId =
            replacement.after() != null
                ? replacement.after().edgeId()
                : replacement.before().edgeId();
        return "cannot project updated edge " + edgeId;
      }
    }
    return null;
  }

  private static List<EdgeReplacement> catalogReplacements(
      List<EdgeReplacement> replacements,
      ChainPlanGraph current,
      ChainPlanGraph desired,
      MaterializationMap map) {
    List<EdgeReplacement> catalog = new ArrayList<>();
    for (EdgeReplacement replacement : replacements) {
      var oldProjection =
          ChainPlanConnectionsMaterializer.project(replacement.before(), current, map);
      var newProjection =
          ChainPlanConnectionsMaterializer.project(replacement.after(), desired, map);
      if (oldProjection.action() == ProjectionAction.FAIL_INVALID
          || newProjection.action() == ProjectionAction.FAIL_INVALID) {
        continue;
      }
      boolean oldCatalog = oldProjection.action() == ProjectionAction.CREATE;
      boolean newCatalog = newProjection.action() == ProjectionAction.CREATE;
      if (!oldCatalog && !newCatalog) {
        continue;
      }
      if (oldCatalog
          && newCatalog
          && Objects.equals(oldProjection.edgeKey(), newProjection.edgeKey())) {
        continue;
      }
      catalog.add(
          new EdgeReplacement(
              oldCatalog ? replacement.before() : null,
              newCatalog ? replacement.after() : null));
    }
    return List.copyOf(catalog);
  }

  private record EdgeReplacement(ChainPlanEdge before, ChainPlanEdge after) {}

  private record ParentTransfer(String nodeId, String parentNodeId, String movedType) {}

  private static List<ParentTransfer> parentTransfers(GraphPatch patch, ChainPlanGraph before) {
    if (patch.nodePatches() == null || before == null || before.nodes() == null) {
      return List.of();
    }
    Map<String, ChainPlanNode> beforeById = new LinkedHashMap<>();
    for (ChainPlanNode node : before.nodes()) {
      if (node != null && node.nodeId() != null) {
        beforeById.put(node.nodeId(), node);
      }
    }
    List<ParentTransfer> transfers = new ArrayList<>();
    for (NodePatch nodePatch : patch.nodePatches()) {
      if (nodePatch == null
          || nodePatch.operation() != GraphPatchOperation.UPDATE
          || nodePatch.node() == null) {
        continue;
      }
      String nodeId =
          nodePatch.targetNodeId() != null ? nodePatch.targetNodeId() : nodePatch.node().nodeId();
      ChainPlanNode previous = nodeId == null ? null : beforeById.get(nodeId);
      if (previous == null
          || Objects.equals(previous.parentNodeId(), nodePatch.node().parentNodeId())) {
        continue;
      }
      String movedType =
          nodePatch.node().type() != null ? nodePatch.node().type() : previous.type();
      transfers.add(new ParentTransfer(nodeId, nodePatch.node().parentNodeId(), movedType));
    }
    return List.copyOf(transfers);
  }

  private String preflightTransfers(
      List<ParentTransfer> transfers,
      ChainPlanGraph desired,
      MaterializationMap map,
      CatalogElementDescriptorCache cache) {
    for (ParentTransfer transfer : transfers) {
      String elementId = catalogId(map, transfer.nodeId());
      String parentId = catalogId(map, transfer.parentNodeId());
      if (elementId == null) {
        return cannotTransfer(transfer.nodeId(), "catalog id is unknown.");
      }
      if (transfer.parentNodeId() != null && parentId == null) {
        return cannotTransferUnder(
            elementId, transfer.parentNodeId(), "catalog id for parent is unknown.");
      }
      String destinationType = destinationType(desired, transfer.parentNodeId());
      if (transfer.parentNodeId() != null && destinationType == null) {
        return cannotTransferUnder(
            elementId, transfer.parentNodeId(), "destination node is missing.");
      }
      CatalogElementDescriptor destination;
      CatalogElementDescriptor moved;
      try {
        if (destinationType != null) {
          destination = cache.require(destinationType);
        } else {
          destination = null;
        }
        moved = cache.require(transfer.movedType());
      } catch (CatalogElementDescriptorException e) {
        return e.getMessage();
      }
      if (destination != null && !destination.container()) {
        return cannotTransferUnder(
            elementId, parentId, "destination type '" + destinationType + "' is not a container.");
      }
      if (destination != null
          && !destination.allowedChildren().isEmpty()
          && !destination.allowedChildren().containsKey(transfer.movedType())) {
        return cannotTransferUnder(
            elementId, parentId, "child type '" + transfer.movedType() + "' is not allowed.");
      }
      if (!moved.parentRestriction().isEmpty()
          && (destinationType == null || !moved.parentRestriction().contains(destinationType))) {
        return cannotTransferUnder(
            elementId, parentId, "parent type '" + destinationType + "' is not permitted.");
      }
    }
    return null;
  }

  private String transferGroupedByParent(List<ParentTransfer> transfers, MaterializationMap map) {
    Map<String, List<String>> elementsByParent = new LinkedHashMap<>();
    for (ParentTransfer transfer : transfers) {
      String elementId = catalogId(map, transfer.nodeId());
      String parentId = catalogId(map, transfer.parentNodeId());
      if (elementId == null) {
        return cannotTransfer(transfer.nodeId(), "catalog id is unknown.");
      }
      if (transfer.parentNodeId() != null && parentId == null) {
        return cannotTransferUnder(
            elementId, transfer.parentNodeId(), "catalog id for parent is unknown.");
      }
      elementsByParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(elementId);
    }
    for (Map.Entry<String, List<String>> group : elementsByParent.entrySet()) {
      String parentId = group.getKey();
      List<String> elements = List.copyOf(group.getValue());
      try {
        catalogRestClient.transferElements(
            map.chainId(), new CatalogTransferElementsRequest(parentId, null, elements));
      } catch (RuntimeException e) {
        return "Cannot transfer elements under '" + parentId + "': " + e.getMessage();
      }
      for (String elementId : elements) {
        CatalogElementResponseDto readBack;
        try {
          readBack = catalogRestClient.getElement(map.chainId(), elementId);
        } catch (RuntimeException e) {
          return cannotTransferUnder(elementId, parentId, e.getMessage());
        }
        String actualParent = readBack == null ? null : readBack.parentElementId;
        if (!Objects.equals(parentId, actualParent)) {
          return cannotTransferUnder(
              elementId, parentId, "catalog parent is still '" + actualParent + "'.");
        }
      }
    }
    return null;
  }

  private static String cannotTransfer(String elementId, String reason) {
    return "Cannot transfer element '" + elementId + "': " + reason;
  }

  private static String cannotTransferUnder(String elementId, String parentId, String reason) {
    return "Cannot transfer element '" + elementId + "' under '" + parentId + "': " + reason;
  }

  private static String destinationType(ChainPlanGraph graph, String parentNodeId) {
    if (parentNodeId == null || graph == null || graph.nodes() == null) {
      return null;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && parentNodeId.equals(node.nodeId())) {
        return node.type();
      }
    }
    return null;
  }

  private static String catalogId(MaterializationMap map, String nodeId) {
    if (nodeId == null || map == null || map.nodeIdToElementId() == null) {
      return null;
    }
    return map.nodeIdToElementId().get(nodeId);
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
        .orElseThrow(() -> new IllegalStateException("desired graph has no node " + nodeId));
  }

  private static ChainPlanNode withChangedPropertiesOnly(ChainPlanNode node, Set<String> keys) {
    List<PlanProperty> changed =
        node.properties() == null
            ? List.of()
            : node.properties().stream().filter(property -> keys.contains(property.key())).toList();
    return new ChainPlanNode(node.nodeId(), node.type(), null, node.parentNodeId(), null, changed);
  }
}
