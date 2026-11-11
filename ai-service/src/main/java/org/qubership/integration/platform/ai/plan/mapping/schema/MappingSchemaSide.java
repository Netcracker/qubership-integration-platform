package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

/** Schema evidence for a mapping boundary, including a runtime JSON input without a declared schema. */
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

  private static final String RUNTIME_JSON = "runtime-json:";

  public static MappingSchemaSide runtimeJson(String interactionId) {
    return new MappingSchemaSide("1", interactionId, null, MappingPort.OUTPUT, "application/json",
        null, RUNTIME_JSON + interactionId, RUNTIME_JSON,
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
  }

  @JsonIgnore
  public boolean isRuntimeJson() {
    return RUNTIME_JSON.equals(provenance);
  }

  /** Catalog content type; stable name used by the boundary resolver. */
  public String mediaType() {
    return contentType;
  }
}
