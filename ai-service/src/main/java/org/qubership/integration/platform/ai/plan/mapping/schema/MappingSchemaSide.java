package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

/** One persisted request or response JSON schema for a bound service call. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingSchemaSide(
    String schemaVersion,
    String serviceCallId,
    String operationId,
    MappingPort direction,
    String contentType,
    String responseCode,
    String sha256,
    String provenance,
    JsonNode schema) {

  /** Catalog content type; stable name used by the boundary resolver. */
  public String mediaType() {
    return contentType;
  }
}
