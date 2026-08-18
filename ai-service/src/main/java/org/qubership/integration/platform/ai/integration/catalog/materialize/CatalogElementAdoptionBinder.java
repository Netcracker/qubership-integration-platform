package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Binds planned graph nodes to unclaimed catalog elements by parent id, exact type, and deterministic
 * sibling order. Shared by CREATE resume read-back and skeleton generated-child adoption.
 */
public final class CatalogElementAdoptionBinder {

  private CatalogElementAdoptionBinder() {}

  /**
   * Keeps every checkpoint mapping and adopts unmapped desired nodes from imported catalog elements.
   */
  public static Map<String, String> mergeImportedBindings(
      ChainPlanGraph desired,
      Map<String, String> checkpointMap,
      List<ChainCatalogElement> importedElements) {
    Map<String, String> merged = new LinkedHashMap<>();
    if (checkpointMap != null) {
      merged.putAll(checkpointMap);
    }
    Set<String> usedElementIds = new HashSet<>(merged.values());
    List<ChainCatalogElement> candidates =
        importedElements == null ? List.of() : importedElements;
    for (ChainPlanNode node : ChainPlanSkeletonMaterializer.orderParentBeforeChild(desired)) {
      if (merged.containsKey(node.nodeId())) {
        continue;
      }
      String parentCatalogId = resolveParentCatalogId(node, merged);
      String adopted =
          firstUnclaimedImportedElement(candidates, parentCatalogId, node.type(), usedElementIds);
      if (adopted != null) {
        merged.put(node.nodeId(), adopted);
        usedElementIds.add(adopted);
      }
    }
    return merged;
  }

  public static String firstUnclaimedOfType(
      List<CatalogRestClient.ElementSummaryDto> generated,
      String childType,
      Set<String> usedElementIds) {
    String typeKey = trim(childType);
    if (typeKey == null || typeKey.isEmpty() || generated == null) {
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

  private static String resolveParentCatalogId(ChainPlanNode node, Map<String, String> map) {
    String parentNodeId = trim(node.parentNodeId());
    if (parentNodeId == null || parentNodeId.isEmpty()) {
      return null;
    }
    return map.get(parentNodeId);
  }

  private static String firstUnclaimedImportedElement(
      List<ChainCatalogElement> imported,
      String parentCatalogId,
      String childType,
      Set<String> usedElementIds) {
    String typeKey = trim(childType);
    if (typeKey == null || typeKey.isEmpty()) {
      return null;
    }
    List<ChainCatalogElement> matching = new ArrayList<>();
    for (ChainCatalogElement element : imported) {
      if (element == null) {
        continue;
      }
      String elementId = trim(element.elementId());
      if (elementId == null
          || usedElementIds.contains(elementId)
          || !typeKey.equals(trim(element.type()))) {
        continue;
      }
      String elementParent = trim(element.parentElementId());
      if (Objects.equals(parentCatalogId, elementParent)) {
        matching.add(element);
      }
    }
    matching.sort(
        Comparator.comparing(
            element -> trim(element.elementId()), Comparator.nullsLast(String::compareTo)));
    if (matching.isEmpty()) {
      return null;
    }
    return trim(matching.get(0).elementId());
  }

  private static String trim(String value) {
    return value != null ? value.trim() : null;
  }
}
