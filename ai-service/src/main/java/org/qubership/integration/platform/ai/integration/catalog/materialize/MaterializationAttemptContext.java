package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/** Tracks containers created in one materialization attempt and their generated children. */
public final class MaterializationAttemptContext {

  private final Set<String> containersCreatedInAttempt = new LinkedHashSet<>();
  private final Map<String, CreatedContainer> createdContainers = new LinkedHashMap<>();

  void recordCreatedContainer(
      String containerElementId,
      String containerType,
      List<CatalogRestClient.ElementSummaryDto> generatedChildren) {
    if (containerElementId == null
        || containerElementId.isBlank()
        || containerType == null
        || containerType.isBlank()
        || generatedChildren == null
        || generatedChildren.isEmpty()) {
      return;
    }
    containersCreatedInAttempt.add(containerElementId);
    createdContainers.put(
        containerElementId,
        new CreatedContainer(containerElementId, containerType, List.copyOf(generatedChildren)));
  }

  public boolean isEmpty() {
    return containersCreatedInAttempt.isEmpty();
  }

  List<CreatedContainer> createdContainers() {
    return List.copyOf(createdContainers.values());
  }

  public record CreatedContainer(
      String elementId,
      String type,
      List<CatalogRestClient.ElementSummaryDto> generatedChildren) {}
}
