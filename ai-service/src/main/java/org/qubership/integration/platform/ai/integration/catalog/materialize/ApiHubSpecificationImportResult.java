package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.Optional;

/** Result of importing an API Hub specification into runtime-catalog. */
public record ApiHubSpecificationImportResult(
    String systemId,
    String specificationId,
    String specificationGroupId,
    String importId,
    String specificationGroupName,
    Optional<String> catalogOperationId) {}
