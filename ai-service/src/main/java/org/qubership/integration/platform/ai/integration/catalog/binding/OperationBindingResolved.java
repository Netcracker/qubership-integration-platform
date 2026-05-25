package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

import java.util.Optional;

/** Catalog operation + system context after lookup. */
public record OperationBindingResolved(
    CatalogRestClient.OperationDto operation,
    String systemId,
    String specificationGroupId,
    String protocol,
    Optional<CatalogRestClient.SystemDto> system) {}
