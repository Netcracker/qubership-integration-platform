package org.qubership.integration.platform.ai.integration.catalog.model;

/**
 * Request body for {@code POST /v1/systems/search} (runtime-catalog {@link
 * SystemSearchRequestDTO}).
 */
public record CatalogSystemSearchRequest(String searchCondition) {}
