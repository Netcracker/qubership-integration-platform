package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Creates catalog elements from a {@link ChainPlanGraph} skeleton in an existing chain. */
@ApplicationScoped
public class ChainPlanSkeletonMaterializer {

  private static final Logger LOG = Logger.getLogger(ChainPlanSkeletonMaterializer.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainPlanSkeletonMaterializer(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  public MaterializationMap materializeElements(ChainPlanGraph graph, String chainId) {
    Objects.requireNonNull(graph, "graph");
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalArgumentException("chainId is required");
    }
    if (graph.nodes() == null || graph.nodes().isEmpty()) {
      throw new IllegalArgumentException("graph must contain at least one node");
    }

    try {
      Map<String, String> nodeIdToElementId = new LinkedHashMap<>();
      Set<String> usedElementIds = new HashSet<>();
      List<ChainPlanNode> orderedNodes = orderParentBeforeChild(graph);
      for (ChainPlanNode node : orderedNodes) {
        if (!nodeIdToElementId.containsKey(node.nodeId())) {
          String elementId =
              materializeElement(
                  graph, node, chainId, new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId)));
          nodeIdToElementId.put(node.nodeId(), elementId);
          usedElementIds.add(elementId);
          bindPendingChildShellsFromLive(
              chainId, node.nodeId(), orderedNodes, nodeIdToElementId, usedElementIds);
        }
      }
      return new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId));
    } catch (RuntimeException e) {
      boolean chainDeleted = rollbackChain(chainId, e);
      throw new SkeletonMaterializationException(chainId, chainDeleted, e);
    }
  }

  public String materializeElement(
      ChainPlanGraph graph, ChainPlanNode node, String chainId, MaterializationMap currentMap) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(currentMap, "currentMap");
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalArgumentException("chainId is required");
    }
    if (currentMap.nodeIdToElementId() != null
        && currentMap.nodeIdToElementId().containsKey(node.nodeId())) {
      throw new IllegalStateException("Node '" + node.nodeId() + "' is already materialized");
    }
    Map<String, String> nodeIdToElementId =
        new LinkedHashMap<>(
            currentMap.nodeIdToElementId() == null ? Map.of() : currentMap.nodeIdToElementId());
    Set<String> usedElementIds = new HashSet<>(nodeIdToElementId.values());
    String elementType = trim(node.type());
    if (elementType == null || elementType.isEmpty()) {
      throw new IllegalStateException("Node '" + node.nodeId() + "' has blank element type");
    }
    String containmentParentNodeId = ChainPlanGraphValidator.effectiveParentNodeId(node, graph);
    if (containmentParentNodeId != null && !containmentParentNodeId.equals(node.parentNodeId())) {
      LOG.infof(
          "Inferred containment parent for nodeId=%s type=%s parentNodeId=%s (plan had %s)",
          node.nodeId(),
          elementType,
          containmentParentNodeId,
          node.parentNodeId());
    }
    String parentElementId =
        resolveParentElementId(
            node, graph, containmentParentNodeId, nodeIdToElementId, chainId, usedElementIds);
    if (ChainPlanGraphValidator.isTriggerElementType(elementType)) {
      if (containmentParentNodeId != null && !containmentParentNodeId.isBlank()) {
        LOG.infof(
            "Materializing trigger node %s at chain root (ignoring containment parentNodeId=%s)",
            node.nodeId(),
            containmentParentNodeId);
      }
      parentElementId = null;
    }
    CatalogCreateElementRequest createRequest =
        new CatalogCreateElementRequest(elementType, parentElementId, null);
    Optional<String> shellId = tryReuseAutoShell(chainId, parentElementId, elementType, usedElementIds);
    if (shellId.isPresent()) {
      return shellId.get();
    }
    LOG.infof(
        "Creating catalog element nodeId=%s type=%s parentElementId=%s",
        node.nodeId(), elementType, parentElementId);
    Set<String> beforeCreate = new HashSet<>(listElementIds(chainId));
    CatalogRestClient.ChainDiffDto diff = catalogRestClient.createElement(chainId, createRequest);
    String elementId = extractCreatedElementId(diff, elementType);
    if (beforeCreate.contains(elementId)) {
      throw new IllegalStateException(
          "createElement did not produce a new id for node '" + node.nodeId() + "'");
    }
    return elementId;
  }

  public List<CatalogElementResponseDto> listElements(String chainId) {
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalArgumentException("chainId is required");
    }
    List<CatalogElementResponseDto> elements = catalogRestClient.listElements(chainId);
    if (elements == null) {
      return List.of();
    }
    return List.copyOf(elements);
  }

  /**
   * Container elements (try-catch-finally-2, split-2, etc.) get auto-created shell children in the
   * catalog. Reuse those ids instead of POSTing duplicate children.
   */
  private Optional<String> tryReuseAutoShell(
      String chainId, String parentElementId, String childType, Set<String> usedElementIds) {
    if (parentElementId == null || parentElementId.isBlank()) {
      return Optional.empty();
    }
    String typeKey = childType != null ? childType.trim() : "";
    if (typeKey.isEmpty()) {
      return Optional.empty();
    }
    CatalogElementResponseDto parent;
    try {
      parent = catalogRestClient.getElement(chainId, parentElementId);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Failed to load parent %s for shell rebound", parentElementId);
      return Optional.empty();
    }
    return findUnusedShell(parent, typeKey, usedElementIds);
  }

  private void bindPendingChildShellsFromLive(
      String chainId,
      String parentNodeId,
      List<ChainPlanNode> allNodes,
      Map<String, String> nodeIdToElementId,
      Set<String> usedElementIds) {
    String parentElementId = nodeIdToElementId.get(parentNodeId);
    if (parentElementId == null) {
      return;
    }
    List<ChainPlanNode> pendingChildren =
        allNodes.stream()
            .filter(node -> parentNodeId.equals(node.parentNodeId()))
            .filter(node -> !nodeIdToElementId.containsKey(node.nodeId()))
            .toList();
    if (pendingChildren.isEmpty()) {
      return;
    }
    CatalogElementResponseDto parent;
    try {
      parent = catalogRestClient.getElement(chainId, parentElementId);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Failed to load parent %s to bind child shells", parentElementId);
      return;
    }
    for (ChainPlanNode child : pendingChildren) {
      if (ChainPlanGraphValidator.isTriggerElementType(child.type())) {
        continue;
      }
      String childType = trim(child.type());
      findUnusedShell(parent, childType, usedElementIds)
          .ifPresent(
              shellId -> {
                nodeIdToElementId.put(child.nodeId(), shellId);
                usedElementIds.add(shellId);
              });
    }
  }

  private static Optional<String> findUnusedShell(
      CatalogElementResponseDto parent, String childType, Set<String> usedElementIds) {
    if (parent == null || parent.children == null || parent.children.isEmpty()) {
      return Optional.empty();
    }
    String typeKey = childType != null ? childType.trim() : "";
    if (typeKey.isEmpty()) {
      return Optional.empty();
    }
    return parent.children.stream()
        .filter(child -> child != null && typeKey.equals(trim(child.type)))
        .map(child -> trim(child.id))
        .filter(id -> id != null && !usedElementIds.contains(id))
        .sorted()
        .findFirst();
  }

  private static String trim(String value) {
    return value != null ? value.trim() : null;
  }

  private Set<String> listElementIds(String chainId) {
    Set<String> ids = new HashSet<>();
    for (CatalogElementResponseDto element : listElements(chainId)) {
      if (element != null && element.id != null && !element.id.isBlank()) {
        ids.add(element.id.trim());
      }
    }
    return ids;
  }

  private boolean rollbackChain(String chainId, RuntimeException cause) {
    try {
      catalogRestClient.deleteChain(chainId);
      LOG.warnf(
          "Rolled back partially created chain %s after skeleton failure: %s",
          chainId, cause.getMessage());
      return true;
    } catch (RuntimeException cleanupError) {
      LOG.errorf(
          cleanupError,
          "Failed to roll back chain %s after skeleton failure; manual cleanup may be required",
          chainId);
      return false;
    }
  }

  private String resolveParentElementId(
      ChainPlanNode node,
      ChainPlanGraph graph,
      String containmentParentNodeId,
      Map<String, String> nodeIdToElementId,
      String chainId,
      Set<String> usedElementIds) {
    if (containmentParentNodeId == null || containmentParentNodeId.isBlank()) {
      return resolveTryShellFromIncomingWrapperEdge(node, graph, chainId, nodeIdToElementId, usedElementIds)
          .orElse(null);
    }
    ChainPlanNode parentPlan = findPlanNode(graph, containmentParentNodeId);
    // Catalog auto-creates try-2/catch-2/finally-2 under the wrapper. Shell plan nodes must keep
    // the wrapper as parent so tryReuseAutoShell can bind those ids. Remap only non-shell content
    // that the plan incorrectly parents under the wrapper into the live try-2 branch.
    if (parentPlan != null
        && ChainElementFamilies.TRY_CATCH_WRAPPER.contains(trim(parentPlan.type()))
        && !ChainElementFamilies.isTryCatchShell(trim(node.type()))) {
      String wrapperElementId = nodeIdToElementId.get(containmentParentNodeId);
      if (wrapperElementId != null) {
        String tryShellId = findTryShellElementId(chainId, wrapperElementId, usedElementIds);
        if (tryShellId != null) {
          return tryShellId;
        }
      }
    }
    String parentElementId = nodeIdToElementId.get(containmentParentNodeId);
    if (parentElementId == null) {
      throw new IllegalStateException(
          "Parent node '" + containmentParentNodeId + "' was not materialized before child");
    }
    return parentElementId;
  }

  private Optional<String> resolveTryShellFromIncomingWrapperEdge(
      ChainPlanNode node,
      ChainPlanGraph graph,
      String chainId,
      Map<String, String> nodeIdToElementId,
      Set<String> usedElementIds) {
    if (graph.edges() == null || graph.nodes() == null) {
      return Optional.empty();
    }
    Map<String, ChainPlanNode> nodesById = indexPlanNodes(graph.nodes());
    for (var edge : graph.edges()) {
      if (!node.nodeId().equals(edge.toNodeId())) {
        continue;
      }
      ChainPlanNode from = nodesById.get(edge.fromNodeId());
      if (from == null || !ChainElementFamilies.TRY_CATCH_WRAPPER.contains(trim(from.type()))) {
        continue;
      }
      String wrapperElementId = nodeIdToElementId.get(from.nodeId());
      if (wrapperElementId == null) {
        continue;
      }
      String tryShellId = findTryShellElementId(chainId, wrapperElementId, usedElementIds);
      if (tryShellId != null) {
        return Optional.of(tryShellId);
      }
    }
    return Optional.empty();
  }

  private String findTryShellElementId(
      String chainId, String wrapperElementId, Set<String> usedElementIds) {
    try {
      CatalogElementResponseDto wrapper = catalogRestClient.getElement(chainId, wrapperElementId);
      return findUnusedShell(wrapper, "try-2", usedElementIds).orElse(null);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Failed to load wrapper %s for try-2 shell lookup", wrapperElementId);
      return null;
    }
  }

  private static ChainPlanNode findPlanNode(ChainPlanGraph graph, String nodeId) {
    if (graph.nodes() == null) {
      return null;
    }
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElse(null);
  }

  private static Map<String, ChainPlanNode> indexPlanNodes(List<ChainPlanNode> nodes) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null) {
        nodesById.put(node.nodeId(), node);
      }
    }
    return nodesById;
  }

  private static String extractCreatedElementId(
      CatalogRestClient.ChainDiffDto diff, String expectedType) {
    if (diff == null || diff.createdElements() == null || diff.createdElements().isEmpty()) {
      throw new IllegalStateException("createElement did not return created elements");
    }
    int primaryIdx = indexOfPrimaryCreated(diff.createdElements(), expectedType);
    if (primaryIdx < 0) {
      throw new IllegalStateException(
          "createElement response has no element of type " + expectedType);
    }
    String elementId = diff.createdElements().get(primaryIdx).id();
    if (elementId == null || elementId.isBlank()) {
      throw new IllegalStateException("createElement returned empty element id");
    }
    return elementId;
  }

  private static int indexOfPrimaryCreated(
      List<CatalogRestClient.ElementSummaryDto> created, String expectedType) {
    String want = expectedType != null ? expectedType.trim() : "";
    for (int i = 0; i < created.size(); i++) {
      CatalogRestClient.ElementSummaryDto element = created.get(i);
      if (element == null) {
        continue;
      }
      String type = element.type() != null ? element.type().trim() : "";
      if (want.equals(type)) {
        return i;
      }
    }
    return -1;
  }

  public static List<ChainPlanNode> orderParentBeforeChild(ChainPlanGraph graph) {
    List<ChainPlanNode> nodes = graph != null && graph.nodes() != null ? graph.nodes() : List.of();
    List<ChainPlanNode> pending = new ArrayList<>(nodes);
    List<ChainPlanNode> ordered = new ArrayList<>();
    var created = new java.util.HashSet<String>();

    while (!pending.isEmpty()) {
      List<ChainPlanNode> ready = new ArrayList<>();
      Iterator<ChainPlanNode> iterator = pending.iterator();
      while (iterator.hasNext()) {
        ChainPlanNode node = iterator.next();
        String parentNodeId = ChainPlanGraphValidator.effectiveParentNodeId(node, graph);
        if (parentNodeId == null
            || parentNodeId.isBlank()
            || created.contains(parentNodeId)) {
          ready.add(node);
          iterator.remove();
        }
      }
      if (ready.isEmpty()) {
        throw new IllegalStateException("Cannot order nodes: missing parent or containment cycle");
      }
      ready.sort(ChainPlanSkeletonMaterializer::compareMaterializationOrder);
      for (ChainPlanNode node : ready) {
        ordered.add(node);
        created.add(node.nodeId());
      }
    }
    return ordered;
  }

  private static int compareMaterializationOrder(ChainPlanNode left, ChainPlanNode right) {
    boolean leftTrigger = ChainPlanGraphValidator.isTriggerElementType(left.type());
    boolean rightTrigger = ChainPlanGraphValidator.isTriggerElementType(right.type());
    if (leftTrigger == rightTrigger) {
      return 0;
    }
    return leftTrigger ? -1 : 1;
  }
}
