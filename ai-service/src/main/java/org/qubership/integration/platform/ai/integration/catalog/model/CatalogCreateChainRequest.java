package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request body for {@code POST /v1/chains}. Mirrors runtime-catalog {@code ChainRequest} JSON
 * shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogCreateChainRequest(String name, String description, List<Object> labels) {

  public static CatalogCreateChainRequest of(String name, String description) {
    return new CatalogCreateChainRequest(
        name, (description == null || description.isBlank()) ? null : description, List.of());
  }
}
