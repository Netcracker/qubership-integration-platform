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
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflight;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ProjectionAction;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
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
  private final ChainPlanRemovalsMaterializer removalsMaterializer;
  private final CatalogRestClient catalogRestClient;
  private final CatalogElementDescriptorLoader descriptorLoader;

  @Inject
  public ChainPatchWriter(
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

  public ChainPatchWriteResult write(PatchedChain patched, GraphPatch patch) {
    Objects.requireNonNull(patched, "patched");
    Objects.requireNonNull(patch, "patch");

    List<String> addedNodeIds = addedNodeIds(patched.graph(), patch);
    Map<String, Set<String>> changedKeysByNodeId = changedKeysByNodeId(patch);
    List<ChainPlanEdge> addedEdges = addedEdges(patch);
    List<EdgeReplacement> replacements = edgeReplacements(patch, patched.before());
    List<ParentTransfer> parentTransfers = parentTransfers(patch, patched.before());
    // Edges, removals, and parent moves count toward "the patch does something": a patch whose only
    // content is a connection between two elements the chain already has, the removal of one, or a
    // parent-only UPDATE, adds no node and changes no property, and must still reach its writer
    // rather than being read as an empty change. The same is true of an endpoint retarget: UPDATE
    // is not ADD and not REMOVE.
    boolean removesSomething =
        !removedNodeIds(patch).isEmpty() || !removedEdges(patch, patched.before()).isEmpty();
    if (addedNodeIds.isEmpty()
        && changedKeysByNodeId.isEmpty()
        && addedEdges.isEmpty()
        && replacements.isEmpty()
        && parentTransfers.isEmpty()
        && !removesSomething) {
      return new ChainPatchWriteResult(List.of(), List.of(), null, patched.materializationMap());
    }

    CatalogElementDescriptorCache cache = new CatalogElementDescriptorCache(descriptorLoader);
    try {
      new DesiredGraphDescriptorPreflight()
          .validate(patched.graph(), patched.before(), cache);
    } catch (DesiredGraphDescriptorPreflightException e) {
      return new ChainPatchWriteResult(
          List.of(), List.of(), e.getMessage(), patched.materializationMap());
    }

    Map<String, String> nodeIdToElementId =
        new LinkedHashMap<>(patched.materializationMap().nodeIdToElementId());
    String chainId = patched.materializationMap().chainId();
    List<String> failed = new ArrayList<>();
    String error = null;

    for (String nodeId : addedNodeIds) {
      if (nodeIdToElementId.containsKey(nodeId)) {
        continue;
      }
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
    // New elements now have catalog ids, so placement-only and no-op retargets can be dropped.
    // FAIL_INVALID is recorded here so the delete/create steps below stay gated off.
    error = error == null ? invalidReplacementError(replacements, patched, map) : error;
    replacements = catalogReplacements(replacements, patched, map);
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

    // Destination checks run before any transfer mutation. An incompatible parent must not delete
    // blocking dependencies or call transfer. Catalog ids come from the map after ADD, so a newly
    // created logical parent is already the generated catalog id.
    if (!parentTransfers.isEmpty() && failed.isEmpty() && error == null) {
      error = preflightTransfers(parentTransfers, patched, map, cache);
    }

    // An endpoint retarget has to drop the old catalog dependency before the new one is written.
    // Leaving both in place is how a chain forks through the old route and the new one. The same
    // delete is what unblocks a transfer: a leftover inbound dependency fails catalog validation.
    List<ChainPlanEdge> recreatableEdges = new ArrayList<>();
    List<ChainPlanEdge> replacedOldEdges =
        replacements.stream().map(EdgeReplacement::before).filter(Objects::nonNull).toList();
    if (!replacedOldEdges.isEmpty() && failed.isEmpty() && error == null) {
      var removed =
          removalsMaterializer.apply(patched.before(), Set.of(), replacedOldEdges, map);
      if (!removed.removedDependencyIds().isEmpty()) {
        recreatableEdges.addAll(replacedOldEdges);
      }
      failed.addAll(removed.failedNodeIds().stream().filter(id -> !failed.contains(id)).toList());
      error = error == null ? removed.error() : error;
    }

    if (!parentTransfers.isEmpty() && failed.isEmpty() && error == null) {
      error = transferGroupedByParent(parentTransfers, map);
    }

    // Connections come last among the constructive steps, and only on a clean creation: an edge to
    // an element that was not created would be recorded as a failure the reader can do nothing with.
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
                  patched.graph().schemaVersion(),
                  patched.graph().chain(),
                  patched.graph().nodes(),
                  List.copyOf(edgesToCreate)),
              map);
      if (connected.failedEdgeIds().isEmpty()) {
        createdEdges = List.copyOf(edgesToCreate);
      } else {
        error = "connections not created: " + String.join(", ", connected.failedEdgeIds());
        // Whatever the materializer did get through still has to be taken back.
        createdEdges =
            edgesToCreate.stream()
                .filter(edge -> !connected.failedEdgeIds().contains(edge.edgeId()))
                .toList();
      }
    }

    // Removals come last of all, so that every step that can still be taken back has been taken
    // first -- and only on a clean write, because past this point the patch is no longer undoable
    // by anything this service can do.
    List<String> removedElementIds = List.of();
    if (failed.isEmpty() && error == null) {
      Set<String> removedNodeIds = removedNodeIds(patch);
      List<ChainPlanEdge> removedEdges = removedEdges(patch, patched.before());
      if (!removedNodeIds.isEmpty() || !removedEdges.isEmpty()) {
        var removed =
            removalsMaterializer.apply(patched.before(), removedNodeIds, removedEdges, map);
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

    ChainPatchWriteResult.RollbackOutcome rollback =
        failed.isEmpty() && error == null
            ? ChainPatchWriteResult.RollbackOutcome.NOT_ATTEMPTED
            : unwind(
                patched,
                map,
                written,
                changedKeysByNodeId,
                createdEdges,
                recreatableEdges,
                removedElementIds);

    return new ChainPatchWriteResult(
        changed, List.copyOf(failed), error, map, List.copyOf(removedElementIds), rollback);
  }

  /**
   * Takes back what a failed write already wrote, newest step first.
   *
   * <p>Every catalog call goes through a materializer, so the writer stays the one place that knows
   * which phase failed without becoming a second REST client. The one thing it will not do is
   * improvise around a deleted element: the id is gone, and a stand-in under the same name would
   * read as the chain being whole when it is not.
   */
  private ChainPatchWriteResult.RollbackOutcome unwind(
      PatchedChain patched,
      MaterializationMap map,
      List<String> createdNodeIds,
      Map<String, Set<String>> changedKeysByNodeId,
      List<ChainPlanEdge> createdEdges,
      List<ChainPlanEdge> deletedEdges,
      List<String> removedElementIds) {
    if (!removedElementIds.isEmpty()) {
      LOG.errorf(
          "Chain %s: patch failed after deleting %s; refusing to roll back",
          map.chainId(), String.join(", ", removedElementIds));
      return ChainPatchWriteResult.RollbackOutcome.REFUSED;
    }

    // Prior values for every key the patch changed on an element the chain already had. A key the
    // patch introduced has no prior value and the merge never deletes, so it stays behind.
    List<ChainPlanNode> priorValues =
        touchedNodes(patched.before(), List.of(), changedKeysByNodeId).stream()
            .filter(node -> !node.properties().isEmpty())
            .toList();
    boolean introducedKeyStays =
        introducedKeyStays(patched.before(), changedKeysByNodeId, createdNodeIds);

    if (createdNodeIds.isEmpty()
        && createdEdges.isEmpty()
        && deletedEdges.isEmpty()
        && priorValues.isEmpty()) {
      return introducedKeyStays
          ? ChainPatchWriteResult.RollbackOutcome.PARTIAL
          : ChainPatchWriteResult.RollbackOutcome.NOT_ATTEMPTED;
    }

    boolean complete = !introducedKeyStays;
    if (!deletedEdges.isEmpty()) {
      complete &=
          step(
              () ->
                  connectionsMaterializer
                      .apply(edgesOnly(patched.before(), deletedEdges), map)
                      .failedEdgeIds()
                      .isEmpty());
    }
    if (!createdEdges.isEmpty()) {
      complete &=
          step(
              () ->
                  removalsMaterializer
                      .apply(patched.graph(), Set.of(), createdEdges, map)
                      .succeeded());
    }
    if (!priorValues.isEmpty()) {
      complete &=
          step(
              () ->
                  propertiesMaterializer
                      .apply(nodesOnly(patched.before(), priorValues), map)
                      .failedNodeIds()
                      .isEmpty());
    }
    if (!createdNodeIds.isEmpty()) {
      complete &=
          step(
              () ->
                  removalsMaterializer
                      .apply(patched.graph(), new LinkedHashSet<>(createdNodeIds), List.of(), map)
                      .succeeded());
    }

    return complete
        ? ChainPatchWriteResult.RollbackOutcome.COMPLETED
        : ChainPatchWriteResult.RollbackOutcome.PARTIAL;
  }

  /** Whether the patch wrote a property key onto an element that is staying and had no such key. */
  private static boolean introducedKeyStays(
      ChainPlanGraph before, Map<String, Set<String>> changedKeysByNodeId, List<String> createdNodeIds) {
    for (Map.Entry<String, Set<String>> entry : changedKeysByNodeId.entrySet()) {
      if (createdNodeIds.contains(entry.getKey())) {
        continue;
      }
      ChainPlanNode node =
          before.nodes() == null
              ? null
              : before.nodes().stream()
                  .filter(candidate -> entry.getKey().equals(candidate.nodeId()))
                  .findFirst()
                  .orElse(null);
      if (node == null) {
        continue;
      }
      Set<String> had =
          node.properties() == null
              ? Set.of()
              : node.properties().stream().map(PlanProperty::key).collect(Collectors.toSet());
      if (!had.containsAll(entry.getValue())) {
        return true;
      }
    }
    return false;
  }

  /** A compensating call that throws has failed like any other; it must not mask the first error. */
  private boolean step(BooleanSupplier compensation) {
    try {
      return compensation.getAsBoolean();
    } catch (RuntimeException e) {
      LOG.error("Rollback step failed", e);
      return false;
    }
  }

  private static ChainPlanGraph edgesOnly(ChainPlanGraph graph, List<ChainPlanEdge> edges) {
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), graph.nodes(), List.copyOf(edges));
  }

  private static ChainPlanGraph nodesOnly(ChainPlanGraph graph, List<ChainPlanNode> nodes) {
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.of());
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

  /** Resolves each removed edge id back to the edge itself, which the pre-patch graph still has. */
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

  /**
   * Added nodes, every container ahead of the children it holds.
   *
   * <p>Graph order alone is not enough. The patched graph lists nodes in the order the patch named
   * them, and a model that adds a branch and its contents does not reliably name the branch first --
   * observed naming the two children in one tool call and the branch that holds them in the next.
   * Creating the child first fails: the catalog has no parent to attach it to.
   */
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

  /**
   * Stable reorder: a node whose parent this same patch adds waits for that parent. Nodes whose
   * parent already exists in the chain keep the order they came in.
   */
  private static List<String> parentsFirst(ChainPlanGraph graph, List<String> addedNodeIds) {
    Map<String, String> parentById = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        parentById.put(node.nodeId(), node.parentNodeId());
      }
    }
    List<String> ordered = new ArrayList<>(addedNodeIds.size());
    Set<String> placed = new LinkedHashSet<>();
    // At most one pass per node: each round places every node whose added parent is already placed,
    // and a cycle -- which the applier's own parent checks rule out -- would stall it, so whatever
    // is left over is appended rather than dropped.
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

  /**
   * Pairs each UPDATE with the edge the pre-patch graph still holds, looked up by logical edge id.
   *
   * <p>The catalog dependency is identified by endpoints, not by this id; the id is only how the
   * patch names which connection changed.
   */
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

  /**
   * Fails the write when either projected endpoint pair cannot be resolved. Returning the message
   * here, rather than skipping the UPDATE, keeps the old catalog dependency in place.
   */
  private static String invalidReplacementError(
      List<EdgeReplacement> replacements, PatchedChain patched, MaterializationMap map) {
    for (EdgeReplacement replacement : replacements) {
      var oldProjection =
          ChainPlanConnectionsMaterializer.project(replacement.before(), patched.before(), map);
      var newProjection =
          ChainPlanConnectionsMaterializer.project(replacement.after(), patched.graph(), map);
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

  /**
   * Drops parent-to-child placement edges and no-op retargets whose catalog endpoints did not
   * change. FAIL_INVALID is not a skip: {@link #invalidReplacementError} fails the write and this
   * method leaves the replacement out so the old dependency is not deleted. The decision is the
   * connections materializer's own projection, so UPDATE never writes a dependency that ADD would
   * have skipped.
   */
  private static List<EdgeReplacement> catalogReplacements(
      List<EdgeReplacement> replacements, PatchedChain patched, MaterializationMap map) {
    List<EdgeReplacement> catalog = new ArrayList<>();
    for (EdgeReplacement replacement : replacements) {
      var oldProjection =
          ChainPlanConnectionsMaterializer.project(replacement.before(), patched.before(), map);
      var newProjection =
          ChainPlanConnectionsMaterializer.project(replacement.after(), patched.graph(), map);
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

  /**
   * Node UPDATE patches whose after parent differs from the pre-patch graph. Type, label, and order
   * changes do not transfer; only a parent change does.
   */
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

  /**
   * Fails closed on an unloadable descriptor, a non-container destination, a child type the
   * destination does not allow, or a parent type the moved element does not accept.
   */
  private String preflightTransfers(
      List<ParentTransfer> transfers,
      PatchedChain patched,
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
      String destinationType = destinationType(patched.graph(), transfer.parentNodeId());
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

  /**
   * One transfer call per destination catalog parent, then a parent read-back for every moved id.
   * HTTP 200 is not proof: the catalog transfer endpoint can create a dependency instead.
   */
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
