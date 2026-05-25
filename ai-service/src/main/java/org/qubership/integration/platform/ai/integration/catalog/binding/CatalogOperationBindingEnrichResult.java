package org.qubership.integration.platform.ai.integration.catalog.binding;

import java.util.Map;
import java.util.Optional;

/** Outcome of {@link CatalogOperationBindingResolver#enrichForProperties} before property PATCH. */
public record CatalogOperationBindingEnrichResult(
    Map<String, Object> properties, Optional<String> unresolvedReason) {

  public static CatalogOperationBindingEnrichResult unchanged(Map<String, Object> properties) {
    return new CatalogOperationBindingEnrichResult(properties, Optional.empty());
  }

  public static CatalogOperationBindingEnrichResult unresolved(
      Map<String, Object> properties, String reason) {
    return new CatalogOperationBindingEnrichResult(properties, Optional.of(reason));
  }
}
