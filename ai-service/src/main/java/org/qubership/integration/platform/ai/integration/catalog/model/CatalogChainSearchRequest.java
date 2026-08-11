package org.qubership.integration.platform.ai.integration.catalog.model;

/** Request body for {@code POST /v1/folders/search}. */
public record CatalogChainSearchRequest(String searchCondition) {

  public CatalogChainSearchRequest {
    if (searchCondition == null || searchCondition.isBlank()) {
      throw new IllegalArgumentException("searchCondition is required");
    }
  }
}
