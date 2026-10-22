package org.qubership.integration.platform.ai.productpipeline.materialization;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Resolves one pending node to a previously created catalog element after a crash window.
 *
 * <p>Identity is exact element type + effective parent element id + element label, excluding
 * catalog ids already bound in the current materialization map.
 */
@ApplicationScoped
public class PendingNodeRecoveryResolver {

  public String resolve(
      ChainPlanNode pendingNode,
      List<CatalogElementResponseDto> catalogElements,
      MaterializationMap currentMap) {
    Objects.requireNonNull(pendingNode, "pendingNode");
    Objects.requireNonNull(currentMap, "currentMap");
    List<CatalogElementResponseDto> elements =
        catalogElements == null ? List.of() : List.copyOf(catalogElements);
    Set<String> alreadyMapped = Set.copyOf(currentMap.nodeIdToElementId().values());
    String expectedType = normalize(pendingNode.type());
    String expectedLabel = normalize(pendingNode.label());
    String expectedParent =
        normalize(currentMap.nodeIdToElementId().get(normalize(pendingNode.parentNodeId())));

    List<String> candidates =
        elements.stream()
            .filter(candidate -> candidate != null && normalize(candidate.id) != null)
            .filter(candidate -> !alreadyMapped.contains(normalize(candidate.id)))
            .filter(candidate -> Objects.equals(expectedType, normalize(candidate.type)))
            .filter(candidate -> Objects.equals(expectedLabel, normalize(candidate.name)))
            .filter(candidate -> Objects.equals(expectedParent, normalize(candidate.parentElementId)))
            .map(candidate -> normalize(candidate.id))
            .toList();

    if (candidates.size() > 1) {
      throw new IllegalStateException(
          "multiple candidates found for pending node " + pendingNode.nodeId());
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
