package org.qubership.integration.platform.ai.integration.catalog.model;

/**
 * Request body for {@code POST /v1/systems}. {@code type} values match runtime-catalog enum JSON
 * (INTERNAL, EXTERNAL, …).
 */
public record CatalogCreateSystemRequest(String name, String type) {}
