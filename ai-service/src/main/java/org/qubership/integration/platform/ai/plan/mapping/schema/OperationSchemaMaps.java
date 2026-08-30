package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Cached request and response schema maps for one catalog operation. */
public record OperationSchemaMaps(
    String operationId,
    Map<String, JsonNode> requestByContentType,
    Map<String, Map<String, JsonNode>> responseByStatusThenContentType) {

  public OperationSchemaMaps {
    requestByContentType =
        requestByContentType == null ? Map.of() : Map.copyOf(requestByContentType);
    responseByStatusThenContentType =
        responseByStatusThenContentType == null
            ? Map.of()
            : Map.copyOf(responseByStatusThenContentType);
  }
}
