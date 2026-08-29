package org.qubership.integration.platform.ai.chain.deploy;

/**
 * A replacement shown to the reader and waiting to be answered.
 *
 * <p>{@code operationId} is what the decision card carries: an answer naming a different id belongs
 * to a card the conversation has moved past.
 */
public record PendingRedeploy(
    String chainId, String domain, String existingDeploymentId, String operationId) {}
