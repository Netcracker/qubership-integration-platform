package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import java.util.List;
import java.util.Map;

/**
 * Structural facts for one catalog element type, loaded from the live library.
 *
 * <p>An empty {@link #allowedChildren()} map on a container means any child type is allowed. It
 * does not mean the container cannot have children.
 */
public record CatalogElementDescriptor(
    String name,
    boolean container,
    Map<String, CatalogChildQuantity> allowedChildren,
    List<String> parentRestriction,
    boolean ordered,
    String priorityProperty,
    boolean mandatoryInnerElement,
    boolean deprecated,
    boolean oldStyleContainer,
    boolean allowedInContainers) {

  public CatalogElementDescriptor {
    allowedChildren = allowedChildren == null ? Map.of() : Map.copyOf(allowedChildren);
    parentRestriction = parentRestriction == null ? List.of() : List.copyOf(parentRestriction);
    if (priorityProperty == null || priorityProperty.isBlank()) {
      priorityProperty = "priority";
    }
  }
}
