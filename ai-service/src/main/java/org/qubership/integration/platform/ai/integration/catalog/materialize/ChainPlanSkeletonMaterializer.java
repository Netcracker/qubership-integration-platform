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
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflight;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** Creates catalog elements from a {@link ChainPlanGraph} skeleton in an existing chain. */
@ApplicationScoped
public class ChainPlanSkeletonMaterializer {

  private static final Logger LOG = Logger.getLogger(ChainPlanSkeletonMaterializer.class);

  private final CatalogRestClient catalogRestClient;
  private final CatalogElementDescriptorLoader descriptorLoader;
  private final UnclaimedGeneratedChildRemover generatedChildRemover;

  public ChainPlanSkeletonMaterializer(
      CatalogRestClient catalogRestClient, CatalogElementDescriptorLoader descriptorLoader) {
    this(
        catalogRestClient,
        descriptorLoader,
        new UnclaimedGeneratedChildRemover(catalogRestClient));
  }

  @Inject
  public ChainPlanSkeletonMaterializer(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogElementDescriptorLoader descriptorLoader,
      UnclaimedGeneratedChildRemover generatedChildRemover) {
    this.catalogRestClient = catalogRestClient;
    this.descriptorLoader = Objects.requireNonNull(descriptorLoader, "descriptorLoader");
    this.generatedChildRemover =
        Objects.requireNonNull(generatedChildRemover, "generatedChildRemover");
  }

  public MaterializationMap materializeElements(ChainPlanGraph graph, String chainId) {
    Objects.requireNonNull(graph, "graph");
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalArgumentException("chainId is required");
    }
    if (graph.nodes() == null || graph.nodes().isEmpty()) {
      throw new IllegalArgumentException("graph must contain at least one node");
    }

    CatalogElementDescriptorCache cache = new CatalogElementDescriptorCache(descriptorLoader);
    new DesiredGraphDescriptorPreflight()
        .validate(graph, emptyCurrentGraph(graph), cache);

    try {
      Map<String, String> nodeIdToElementId = new LinkedHashMap<>();
      Set<String> usedElementIds = new HashSet<>();
      MaterializationAttemptContext attempt = new MaterializationAttemptContext();
      for (ChainPlanNode node : orderParentBeforeChild(graph)) {
        if (!nodeIdToElementId.containsKey(node.nodeId())) {
          materializeOne(
              graph, node, chainId, nodeIdToElementId, usedElementIds, attempt);
        }
      }
      finishCreatedContainers(chainId, nodeIdToElementId, attempt, cache);
      return new MaterializationMap(chainId, Map.copyOf(nodeIdToElementId));
    } catch (DesiredGraphDescriptorPreflightException e) {
      throw e;
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
    MaterializationAttemptContext attempt = new MaterializationAttemptContext();
    CatalogElementDescriptorCache cache = new CatalogElementDescriptorCache(descriptorLoader);
    String elementId =
        materializeOne(graph, node, chainId, nodeIdToElementId, usedElementIds, attempt);
    finishCreatedContainers(chainId, nodeIdToElementId, attempt, cache);
    return elementId;
  }

  public void finishCreatedContainers(
      String chainId,
      Map<String, String> nodeIdToElementId,
      MaterializationAttemptContext attempt,
      CatalogElementDescriptorCache cache) {
    generatedChildRemover.removeUnclaimed(chainId, nodeIdToElementId, attempt, cache);
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

  private String materializeOne(
      ChainPlanGraph graph,
      ChainPlanNode node,
      String chainId,
      Map<String, String> nodeIdToElementId,
      Set<String> usedElementIds,
      MaterializationAttemptContext attempt) {
    String elementType = trim(node.type());
    if (elementType == null || elementType.isEmpty()) {
      throw new IllegalStateException("Node '" + node.nodeId() + "' has blank element type");
    }
    String parentElementId = resolveParentElementId(node, nodeIdToElementId);
    if (parentElementId != null) {
      String adoptedId =
          adoptMatchingGeneratedChild(chainId, parentElementId, elementType, usedElementIds);
      if (adoptedId != null) {
        nodeIdToElementId.put(node.nodeId(), adoptedId);
        usedElementIds.add(adoptedId);
        return adoptedId;
      }
    }
    CatalogCreateElementRequest createRequest =
        new CatalogCreateElementRequest(elementType, parentElementId, null);
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
    nodeIdToElementId.put(node.nodeId(), elementId);
    usedElementIds.add(elementId);
    List<CatalogRestClient.ElementSummaryDto> generated =
        flattenGeneratedChildren(diff, elementId);
    if (generated.isEmpty()
        && hasUnmappedPlannedDirectChildren(graph, node.nodeId(), nodeIdToElementId)) {
      generated = readBackGeneratedChildren(chainId, elementId);
    }
    adoptPlannedDirectChildren(
        graph, node.nodeId(), generated, nodeIdToElementId, usedElementIds);
    attempt.recordCreatedContainer(elementId, elementType, generated);
    return elementId;
  }

  /**
   * Bind this node to an unclaimed generated child of an already-materialized parent. The parent
   * read-back is the catalog side effect of creating that parent earlier in this attempt.
   */
  private String adoptMatchingGeneratedChild(
      String chainId, String parentElementId, String childType, Set<String> usedElementIds) {
    return firstUnclaimedOfType(
        readBackGeneratedChildren(chainId, parentElementId), childType, usedElementIds);
  }

  private List<CatalogRestClient.ElementSummaryDto> readBackGeneratedChildren(
      String chainId, String parentElementId) {
    CatalogElementResponseDto parent = catalogRestClient.getElement(chainId, parentElementId);
    return flattenGeneratedChildren(toSummary(parent), parentElementId);
  }

  private void adoptPlannedDirectChildren(
      ChainPlanGraph graph,
      String parentNodeId,
      List<CatalogRestClient.ElementSummaryDto> generated,
      Map<String, String> nodeIdToElementId,
      Set<String> usedElementIds) {
    if (generated == null || generated.isEmpty() || graph.nodes() == null) {
      return;
    }
    for (ChainPlanNode child : graph.nodes()) {
      if (!isUnmappedDirectChild(child, parentNodeId, nodeIdToElementId)) {
        continue;
      }
      String adoptedId = firstUnclaimedOfType(generated, child.type(), usedElementIds);
      if (adoptedId != null) {
        nodeIdToElementId.put(child.nodeId(), adoptedId);
        usedElementIds.add(adoptedId);
      }
    }
  }

  private static String firstUnclaimedOfType(
      List<CatalogRestClient.ElementSummaryDto> generated,
      String childType,
      Set<String> usedElementIds) {
    String typeKey = childType != null ? childType.trim() : "";
    if (typeKey.isEmpty() || generated == null) {
      return null;
    }
    for (CatalogRestClient.ElementSummaryDto candidate : generated) {
      String candidateId = trim(candidate.id());
      if (candidateId != null
          && !usedElementIds.contains(candidateId)
          && typeKey.equals(trim(candidate.type()))) {
        return candidateId;
      }
    }
    return null;
  }

  private static boolean hasUnmappedPlannedDirectChildren(
      ChainPlanGraph graph, String parentNodeId, Map<String, String> nodeIdToElementId) {
    if (graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode child : graph.nodes()) {
      if (isUnmappedDirectChild(child, parentNodeId, nodeIdToElementId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isUnmappedDirectChild(
      ChainPlanNode child, String parentNodeId, Map<String, String> nodeIdToElementId) {
    return parentNodeId.equals(child.parentNodeId())
        && !nodeIdToElementId.containsKey(child.nodeId());
  }

  private static List<CatalogRestClient.ElementSummaryDto> flattenGeneratedChildren(
      CatalogRestClient.ChainDiffDto diff, String createdParentId) {
    List<CatalogRestClient.ElementSummaryDto> out = new ArrayList<>();
    if (diff == null || diff.createdElements() == null) {
      return out;
    }
    Set<String> seen = new HashSet<>();
    for (CatalogRestClient.ElementSummaryDto row : diff.createdElements()) {
      collectGeneratedChildren(row, null, createdParentId, out, seen);
    }
    return out;
  }

  private static List<CatalogRestClient.ElementSummaryDto> flattenGeneratedChildren(
      CatalogRestClient.ElementSummaryDto root, String createdParentId) {
    List<CatalogRestClient.ElementSummaryDto> out = new ArrayList<>();
    if (root == null) {
      return out;
    }
    Set<String> seen = new HashSet<>();
    collectGeneratedChildren(root, null, createdParentId, out, seen);
    return out;
  }

  private static void collectGeneratedChildren(
      CatalogRestClient.ElementSummaryDto node,
      String implicitParentId,
      String createdParentId,
      List<CatalogRestClient.ElementSummaryDto> out,
      Set<String> seen) {
    if (node == null) {
      return;
    }
    String id = trim(node.id());
    if (id != null && !seen.add(id)) {
      return;
    }
    String parentId = trim(node.parentElementId());
    if (parentId == null) {
      parentId = implicitParentId;
    }
    if (id != null && createdParentId.equals(parentId)) {
      out.add(node);
    }
    String nextImplicit = id != null ? id : implicitParentId;
    List<CatalogRestClient.ElementSummaryDto> nested = node.children();
    if (nested == null) {
      return;
    }
    for (CatalogRestClient.ElementSummaryDto child : nested) {
      collectGeneratedChildren(child, nextImplicit, createdParentId, out, seen);
    }
  }

  private static CatalogRestClient.ElementSummaryDto toSummary(CatalogElementResponseDto element) {
    if (element == null) {
      return null;
    }
    List<CatalogRestClient.ElementSummaryDto> children = new ArrayList<>();
    if (element.children != null) {
      for (CatalogElementResponseDto child : element.children) {
        CatalogRestClient.ElementSummaryDto summary = toSummary(child);
        if (summary != null) {
          children.add(summary);
        }
      }
    }
    return new CatalogRestClient.ElementSummaryDto(
        element.id, element.type, element.properties, element.parentElementId, children);
  }

  private static String trim(String value) {
    return value != null ? value.trim() : null;
  }

  private static ChainPlanGraph emptyCurrentGraph(ChainPlanGraph desired) {
    return new ChainPlanGraph(desired.schemaVersion(), desired.chain(), List.of(), List.of());
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
      ChainPlanNode node, Map<String, String> nodeIdToElementId) {
    String elementType = trim(node.type());
    String parentNodeId = trim(node.parentNodeId());
    if (ChainPlanGraphValidator.isTriggerElementType(elementType)
        && parentNodeId != null
        && !parentNodeId.isEmpty()) {
      throw new IllegalStateException(
          "Cannot materialize trigger '"
              + node.nodeId()
              + "' under parent '"
              + parentNodeId
              + "': catalog triggers belong at chain root");
    }
    if (parentNodeId == null || parentNodeId.isEmpty()) {
      return null;
    }
    String parentElementId = nodeIdToElementId.get(parentNodeId);
    if (parentElementId == null) {
      throw new IllegalStateException(
          "Parent node '" + parentNodeId + "' was not materialized before child");
    }
    return parentElementId;
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
