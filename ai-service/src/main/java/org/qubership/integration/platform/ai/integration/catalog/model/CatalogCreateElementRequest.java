package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /v1/chains/{chainId}/elements}.
 *
 * <p>Matches runtime-catalog {@code CreateElementRequest}: only placement/type on create. Apply
 * {@code name} and {@code properties} via PATCH after creation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogCreateElementRequest(String type, String parentElementId, String swimlaneId) {}
