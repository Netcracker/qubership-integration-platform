package org.qubership.integration.platform.ai.chain.deploy;

/**
 * A replacement or removal shown to the reader and waiting to be answered.
 *
 * <p>{@code operationId} is what the decision card carries: an answer naming a different id belongs
 * to a card the conversation has moved past. {@code undeploy} marks a removal rather than a
 * replace, including a domain wait that resumes into undeploy. {@code waitingForLoggingLevel} is
 * the session-logging card that runs after the reader has committed to deploy or redeploy.
 */
public record PendingRedeploy(
    String chainId,
    String domain,
    String existingDeploymentId,
    String operationId,
    String snapshotId,
    boolean confirmFirstDeploy,
    boolean undeploy,
    boolean waitingForLoggingLevel) {

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId) {
    this(chainId, domain, existingDeploymentId, operationId, snapshotId, false, false, false);
  }

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId,
      boolean confirmFirstDeploy) {
    this(
        chainId,
        domain,
        existingDeploymentId,
        operationId,
        snapshotId,
        confirmFirstDeploy,
        false,
        false);
  }

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId,
      boolean confirmFirstDeploy,
      boolean undeploy) {
    this(
        chainId,
        domain,
        existingDeploymentId,
        operationId,
        snapshotId,
        confirmFirstDeploy,
        undeploy,
        false);
  }

  /** A token wait for the reader to name an engine domain on the next turn. */
  public static PendingRedeploy domainWait(
      String chainId, String snapshotId, boolean confirmFirstDeploy) {
    return new PendingRedeploy(chainId, null, null, null, snapshotId, confirmFirstDeploy, false);
  }

  /** A token wait for the reader to name which live domain to undeploy. */
  public static PendingRedeploy undeployDomainWait(String chainId) {
    return new PendingRedeploy(chainId, null, null, null, null, false, true);
  }

  public static PendingRedeploy pendingUndeploy(
      String chainId, String domain, String existingDeploymentId, String operationId) {
    return new PendingRedeploy(
        chainId, domain, existingDeploymentId, operationId, null, false, true);
  }

  /**
   * After deploy or redeploy is confirmed, wait for a typed session-logging action before writing.
   */
  public static PendingRedeploy loggingWait(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId) {
    return new PendingRedeploy(
        chainId, domain, existingDeploymentId, operationId, snapshotId, false, false, true);
  }

  public boolean waitingForDomain() {
    return operationId == null;
  }
}
