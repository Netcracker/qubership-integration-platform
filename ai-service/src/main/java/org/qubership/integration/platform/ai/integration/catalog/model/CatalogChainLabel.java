package org.qubership.integration.platform.ai.integration.catalog.model;

/** Label on a catalog chain ({@code ChainLabelDTO} in runtime-catalog). */
public record CatalogChainLabel(String name, boolean technical) {

  public CatalogChainLabel {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("label name is required");
    }
  }
}
