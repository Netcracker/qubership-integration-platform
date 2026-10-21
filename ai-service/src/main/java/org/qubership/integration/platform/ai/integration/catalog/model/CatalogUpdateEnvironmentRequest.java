package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Body for PUT /v1/systems/{systemId}/environments/{environmentId}. */
public record CatalogUpdateEnvironmentRequest(
    String name, String address, String sourceType, JsonNode properties) {}
