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
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CompletedTransfer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/**
 * EDIT lifecycle adapter for catalog graph materialization.
 *
 * <p>Decision-card approval and base-digest verification stay outside. The write body delegates to
 * {@link CatalogGraphMaterializer#apply(String, ChainPlanGraph, ChainPlanGraph, MaterializationMap)}.
 */
@ApplicationScoped
public class ChainPatchWriter {

  private static final Logger LOG = Logger.getLogger(ChainPatchWriter.class);

  private final CatalogGraphMaterializer catalogGraphMaterializer;
  private final ChainPlanPropertiesMaterializer propertiesMaterializer;
  private final ChainPlanConnectionsMaterializer connectionsMaterializer;
  private final ChainPlanRemovalsMaterializer removalsMaterializer;
  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainPatchWriter(
      CatalogGraphMaterializer catalogGraphMaterializer,
      ChainPlanPropertiesMaterializer propertiesMaterializer,
      ChainPlanConnectionsMaterializer connectionsMaterializer,
      ChainPlanRemovalsMaterializer removalsMaterializer,
      @RestClient CatalogRestClient catalogRestClient) {
    this.catalogGraphMaterializer = Objects.requireNonNull(catalogGraphMaterializer);
    this.propertiesMaterializer = Objects.requireNonNull(propertiesMaterializer);
    this.connectionsMaterializer = Objects.requireNonNull(connectionsMaterializer);
    this.removalsMaterializer = Objects.requireNonNull(removalsMaterializer);
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient);
  }

  public ChainPatchWriteResult write(PatchedChain patched, GraphPatch patch) {
    Objects.requireNonNull(patched, "patched");
    Objects.requireNonNull(patch, "patch");

    CatalogGraphMaterializeResult applied =
        catalogGraphMaterializer.apply(
            patched.materializationMap().chainId(),
            patched.before(),
            patched.graph(),
            patched.materializationMap());

    if (applied.noOp()) {
      return new ChainPatchWriteResult(
          List.of(), List.of(), null, applied.materializationMap());
    }

    UnwindResult unwind =
        applied.succeeded()
            ? UnwindResult.notAttempted()
            : unwind(patched, applied);

    return new ChainPatchWriteResult(
        applied.changedNodeIds(),
        applied.failedNodeIds(),
        applied.error(),
        applied.materializationMap(),
        applied.removedElementIds(),
        unwind.outcome(),
        unwind.uncompensated());
  }

  private UnwindResult unwind(PatchedChain patched, CatalogGraphMaterializeResult applied) {
    MaterializationMap map = applied.materializationMap();
    if (!applied.removedElementIds().isEmpty()) {
      LOG.errorf(
          "Chain %s: patch failed after deleting %s; refusing to roll back",
          map.chainId(), String.join(", ", applied.removedElementIds()));
      return UnwindResult.refused();
    }

    List<ChainPlanNode> priorValues =
        touchedNodes(patched.before(), List.of(), applied.changedKeysByNodeId()).stream()
            .filter(node -> !node.properties().isEmpty())
            .toList();
    boolean introducedKeyStays =
        introducedKeyStays(
            patched.before(), applied.changedKeysByNodeId(), applied.createdNodeIds());

    List<String> introducedKeys =
        introducedPropertyKeys(
            patched.before(), applied.changedKeysByNodeId(), applied.createdNodeIds());

    if (applied.createdNodeIds().isEmpty()
        && applied.createdEdges().isEmpty()
        && applied.recreatableEdges().isEmpty()
        && applied.completedTransfers().isEmpty()
        && applied.adoptedGeneratedCatalogIds().isEmpty()
        && priorValues.isEmpty()) {
      return introducedKeyStays ? UnwindResult.partial(introducedKeys) : UnwindResult.notAttempted();
    }

    List<String> uncompensated = new ArrayList<>();
    if (introducedKeyStays) {
      uncompensated.addAll(introducedKeys);
    }
    boolean complete = !introducedKeyStays;

    if (!applied.createdEdges().isEmpty()) {
      List<String> createdDepMarkers = edgeIds(applied.createdEdges());
      complete &=
          step(
              () ->
                  removalsMaterializer
                      .apply(patched.graph(), Set.of(), applied.createdEdges(), map)
                      .succeeded(),
              uncompensated,
              createdDepMarkers);
    }
    if (!applied.completedTransfers().isEmpty()) {
      complete &= inverseTransfer(map, applied.completedTransfers(), uncompensated);
    }
    if (!applied.recreatableEdges().isEmpty()) {
      List<String> removedDepMarkers = edgeIds(applied.recreatableEdges());
      complete &=
          step(
              () ->
                  connectionsMaterializer
                      .apply(edgesOnly(patched.before(), applied.recreatableEdges()), map)
                      .failedEdgeIds()
                      .isEmpty(),
              uncompensated,
              removedDepMarkers);
    }
    if (!priorValues.isEmpty()) {
      List<String> propertyMarkers =
          propertyRestoreMarkers(patched.before(), applied.changedKeysByNodeId(), priorValues);
      complete &=
          step(
              () ->
                  propertiesMaterializer
                      .apply(nodesOnly(patched.before(), priorValues), map)
                      .failedNodeIds()
                      .isEmpty(),
              uncompensated,
              propertyMarkers);
    }
    if (!applied.createdNodeIds().isEmpty() || !applied.adoptedGeneratedCatalogIds().isEmpty()) {
      List<String> createdElementMarkers = createdElementMarkers(applied, map);
      complete &=
          step(
              () -> deleteCreatedElements(patched.graph(), applied, map),
              uncompensated,
              createdElementMarkers);
    }

    return complete
        ? UnwindResult.completed(uncompensated)
        : UnwindResult.partial(uncompensated);
  }

  private boolean inverseTransfer(
      MaterializationMap map,
      List<CompletedTransfer> transfers,
      List<String> uncompensated) {
    Map<String, List<String>> elementsByParent = new LinkedHashMap<>();
    for (CompletedTransfer transfer : transfers.reversed()) {
      elementsByParent
          .computeIfAbsent(transfer.previousParentCatalogId(), key -> new ArrayList<>())
          .add(transfer.catalogElementId());
    }
    boolean complete = true;
    for (Map.Entry<String, List<String>> group : elementsByParent.entrySet()) {
      String priorParentId = group.getKey();
      List<String> elements = List.copyOf(group.getValue());
      try {
        catalogRestClient.transferElements(
            map.chainId(),
            new CatalogTransferElementsRequest(priorParentId, null, elements));
      } catch (RuntimeException e) {
        LOG.error("Inverse transfer failed", e);
        uncompensated.addAll(elements);
        complete = false;
        continue;
      }
      for (String elementId : elements) {
        CatalogElementResponseDto readBack;
        try {
          readBack = catalogRestClient.getElement(map.chainId(), elementId);
        } catch (RuntimeException e) {
          LOG.error("Inverse transfer read-back failed", e);
          uncompensated.add(elementId);
          complete = false;
          continue;
        }
        String actualParent = readBack == null ? null : readBack.parentElementId;
        if (!Objects.equals(priorParentId, actualParent)) {
          uncompensated.add(elementId);
          complete = false;
        }
      }
    }
    return complete;
  }

  private boolean deleteCreatedElements(
      ChainPlanGraph graph, CatalogGraphMaterializeResult applied, MaterializationMap map) {
    Set<String> nodeIdsToRemove = new LinkedHashSet<>(applied.createdNodeIds());
    for (String catalogId : applied.adoptedGeneratedCatalogIds()) {
      nodeIdsToRemove.add(catalogId);
    }
    Map<String, String> expandedMap = new LinkedHashMap<>(map.nodeIdToElementId());
    for (String catalogId : applied.adoptedGeneratedCatalogIds()) {
      expandedMap.putIfAbsent(catalogId, catalogId);
    }
    return removalsMaterializer
        .apply(
            graph,
            nodeIdsToRemove,
            List.of(),
            new MaterializationMap(map.chainId(), Map.copyOf(expandedMap), Map.of(), Map.of()))
        .succeeded();
  }

  private record UnwindResult(
      ChainPatchWriteResult.RollbackOutcome outcome, List<String> uncompensated) {

    static UnwindResult notAttempted() {
      return new UnwindResult(ChainPatchWriteResult.RollbackOutcome.NOT_ATTEMPTED, List.of());
    }

    static UnwindResult refused() {
      return new UnwindResult(ChainPatchWriteResult.RollbackOutcome.REFUSED, List.of());
    }

    static UnwindResult completed(List<String> uncompensated) {
      return new UnwindResult(ChainPatchWriteResult.RollbackOutcome.COMPLETED, uncompensated);
    }

    static UnwindResult partial(List<String> uncompensated) {
      return new UnwindResult(ChainPatchWriteResult.RollbackOutcome.PARTIAL, uncompensated);
    }
  }

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

  private static boolean step(
      BooleanSupplier compensation, List<String> uncompensated, List<String> failureMarkers) {
    try {
      boolean succeeded = compensation.getAsBoolean();
      if (!succeeded) {
        uncompensated.addAll(failureMarkers);
      }
      return succeeded;
    } catch (RuntimeException e) {
      LOG.error("Rollback step failed", e);
      uncompensated.addAll(failureMarkers);
      return false;
    }
  }

  private static List<String> introducedPropertyKeys(
      ChainPlanGraph before,
      Map<String, Set<String>> changedKeysByNodeId,
      List<String> createdNodeIds) {
    List<String> keys = new ArrayList<>();
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
      for (String key : entry.getValue()) {
        if (!had.contains(key)) {
          keys.add(entry.getKey() + ":" + key);
        }
      }
    }
    return keys;
  }

  private static List<String> propertyRestoreMarkers(
      ChainPlanGraph before,
      Map<String, Set<String>> changedKeysByNodeId,
      List<ChainPlanNode> priorValues) {
    List<String> markers = new ArrayList<>();
    for (ChainPlanNode node : priorValues) {
      Set<String> changedKeys = changedKeysByNodeId.get(node.nodeId());
      if (changedKeys == null) {
        continue;
      }
      for (String key : changedKeys) {
        markers.add(node.nodeId() + ":" + key);
      }
    }
    return markers;
  }

  private static List<String> edgeIds(List<ChainPlanEdge> edges) {
    return edges.stream().map(ChainPlanEdge::edgeId).toList();
  }

  private static List<String> createdElementMarkers(
      CatalogGraphMaterializeResult applied, MaterializationMap map) {
    List<String> markers = new ArrayList<>();
    for (String nodeId : applied.createdNodeIds()) {
      markers.add(map.nodeIdToElementId().getOrDefault(nodeId, nodeId));
    }
    markers.addAll(applied.adoptedGeneratedCatalogIds());
    return markers;
  }

  private static ChainPlanGraph edgesOnly(ChainPlanGraph graph, List<ChainPlanEdge> edges) {
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), graph.nodes(), List.copyOf(edges));
  }

  private static ChainPlanGraph nodesOnly(ChainPlanGraph graph, List<ChainPlanNode> nodes) {
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.of());
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

  private static ChainPlanNode withChangedPropertiesOnly(ChainPlanNode node, Set<String> keys) {
    List<PlanProperty> changed =
        node.properties() == null
            ? List.of()
            : node.properties().stream().filter(property -> keys.contains(property.key())).toList();
    return new ChainPlanNode(node.nodeId(), node.type(), null, node.parentNodeId(), null, changed);
  }
}
