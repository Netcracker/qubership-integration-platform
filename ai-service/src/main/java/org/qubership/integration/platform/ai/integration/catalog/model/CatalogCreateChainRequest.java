package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Request body for {@code POST /v1/chains}. Mirrors runtime-catalog {@code ChainRequest} JSON
 * shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogCreateChainRequest(
    String name, String description, List<CatalogChainLabel> labels, String parentId) {

  /** Keeps existing call sites on the three-field constructor; the chain lands at the root. */
  public CatalogCreateChainRequest(
      String name, String description, List<CatalogChainLabel> labels) {
    this(name, description, labels, null);
  }

  public static CatalogCreateChainRequest of(String name, String description) {
    return new CatalogCreateChainRequest(
        name, (description == null || description.isBlank()) ? null : description, List.of());
  }

  public static CatalogCreateChainRequest forPublicationAttempt(
      String name, String description, String attemptLabel) {
    return forPublicationAttempt(name, description, attemptLabel, null);
  }

  /** {@code parentId} is the folder to create the chain in; null puts it at the root. */
  public static CatalogCreateChainRequest forPublicationAttempt(
      String name, String description, String attemptLabel, String parentId) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (attemptLabel == null || attemptLabel.isBlank()) {
      throw new IllegalArgumentException("attemptLabel is required");
    }
    return new CatalogCreateChainRequest(
        name,
        (description == null || description.isBlank()) ? null : description,
        List.of(new CatalogChainLabel(attemptLabel, true)),
        parentId);
  }
}
