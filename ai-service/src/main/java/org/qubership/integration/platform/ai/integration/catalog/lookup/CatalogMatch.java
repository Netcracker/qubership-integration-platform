package org.qubership.integration.platform.ai.integration.catalog.lookup;

/**
 * One complete catalog hierarchy hit for a single outbound service call.
 */
public record CatalogMatch(
    String systemId,
    String specificationGroupId,
    String specificationId,
    String integrationOperationId,
    String systemName,
    String protocol,
    String method,
    String path,
    String operationName,
    String evidenceRef) {}
