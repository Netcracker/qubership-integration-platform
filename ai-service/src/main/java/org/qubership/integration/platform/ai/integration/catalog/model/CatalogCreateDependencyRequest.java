package org.qubership.integration.platform.ai.integration.catalog.model;

/** Request body for {@code POST /v1/chains/{chainId}/dependencies}. */
public record CatalogCreateDependencyRequest(String from, String to) {}
