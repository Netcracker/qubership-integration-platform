package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;

/** One port schema read from the selected operation. The body is the catalog document, unchanged. */
public record PortSchemaMaterial(
    String contractReference,
    String operationId,
    String version,
    String port,
    String contentHash,
    JsonNode schema) {}
