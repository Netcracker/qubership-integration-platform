package org.qubership.integration.platform.ai.plan.mapping.schema;

/** Loads catalog operation schemas and persists mapping schema sides. */
public interface OperationSchemaLoader {

  OperationSchemaMaps load(String operationId);

  /** Returns the current catalog snapshot; caching adapters must refresh their stored value. */
  default OperationSchemaMaps loadFresh(String operationId) {
    return load(operationId);
  }

  MappingSchemaSide persistRequest(
      String compilationId, String serviceCallId, String operationId, String contentType);

  MappingSchemaSide persistResponse(
      String compilationId,
      String serviceCallId,
      String operationId,
      String contentType,
      String responseCode);
}
