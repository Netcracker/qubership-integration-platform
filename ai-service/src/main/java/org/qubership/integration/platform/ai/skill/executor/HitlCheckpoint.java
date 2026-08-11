package org.qubership.integration.platform.ai.skill.executor;

/**
 * Describes a skill pause awaiting user input to resolve an unresolvable binding.
 */
public record HitlCheckpoint(
    String checkpointId, String nodeId, String question, String propertyKey) {

  public static final String INTEGRATION_OPERATION_ID = "integrationOperationId";

  public static HitlCheckpoint forIntegrationOperationId(
      String checkpointId, String nodeId, String question) {
    return new HitlCheckpoint(checkpointId, nodeId, question, INTEGRATION_OPERATION_ID);
  }
}
