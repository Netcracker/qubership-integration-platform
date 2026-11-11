package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request body for {@code POST /v1/chains/{chainId}/elements/transfer}.
 *
 * <p>Matches runtime-catalog {@code TransferElementRequest}: moves existing elements under a
 * container {@code parentId} (or swimlane when applicable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogTransferElementsRequest(
    String parentId, String swimlaneId, List<String> elements) {}
