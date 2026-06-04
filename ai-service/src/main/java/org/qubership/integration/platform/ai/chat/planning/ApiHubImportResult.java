package org.qubership.integration.platform.ai.chat.planning;

import java.time.Instant;

/** Outcome of a successful IMPORT_SPECIFICATION run stored in the planning diary. */
public record ApiHubImportResult(
    String candidateId,
    Instant importedAt,
    String systemId,
    String specificationId,
    String specificationGroupId,
    String importId,
    String apiHubSpecificationName) {}
