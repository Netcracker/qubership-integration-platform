package org.qubership.integration.platform.ai.integration.catalog.model;

/** Body for POST /v1/systems/{systemId}/environments (matches UI EnvironmentRequest). */
public record CatalogCreateEnvironmentRequest(String name, String address) {}
