package org.qubership.integration.platform.ai.chain.deploy;

/**
 * A replacement shown to the reader and waiting to be answered.
 *
 * <p>{@code operationId} is what the decision card carries: an answer naming a different id belongs
 * to a card the conversation has moved past.
 */
public record PendingRedeploy(
    String chainId,
    String domain,
    String existingDeploymentId,
    String operationId,
    String snapshotId,
    boolean confirmFirstDeploy) {

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId) {
    this(chainId, domain, existingDeploymentId, operationId, snapshotId, false);
  }

  /** A token wait for the reader to name an engine domain on the next turn. */
  public static PendingRedeploy domainWait(
      String chainId, String snapshotId, boolean confirmFirstDeploy) {
    return new PendingRedeploy(chainId, null, null, null, snapshotId, confirmFirstDeploy);
  }

  public boolean waitingForDomain() {
    return operationId == null;
  }
}
