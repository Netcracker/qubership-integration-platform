package org.qubership.integration.platform.ai.plan.mapping.schema;

/** Loads catalog operation schemas and persists mapping schema sides. */
public interface OperationSchemaLoader {

  OperationSchemaMaps load(String operationId);

  MappingSchemaSide persistRequest(
      String compilationId, String serviceCallId, String operationId, String contentType);

  MappingSchemaSide persistResponse(
      String compilationId,
      String serviceCallId,
      String operationId,
      String contentType,
      String responseCode);
}
