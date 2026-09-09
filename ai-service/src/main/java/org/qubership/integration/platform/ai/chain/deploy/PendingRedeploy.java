package org.qubership.integration.platform.ai.chain.deploy;

import java.util.List;

/**
 * A replacement or removal shown to the reader and waiting to be answered.
 *
 * <p>{@code operationId} is what the decision card carries: an answer naming a different id belongs
 * to a card the conversation has moved past. {@code undeploy} marks a removal rather than a
 * replace, including a domain wait that resumes into undeploy. {@code waitingForLoggingLevel} is
 * the session-logging card that runs after the reader has committed to deploy or redeploy.
 * {@code waitingForMaasTopics} is the Kafka MaaS topic card; create and dismiss require it.
 */
public record PendingRedeploy(
    String chainId,
    String domain,
    String existingDeploymentId,
    String operationId,
    String snapshotId,
    boolean confirmFirstDeploy,
    boolean undeploy,
    boolean waitingForLoggingLevel,
    boolean waitingForMaasTopics,
    List<MaasKafkaTopicsFollowUp.TopicPair> maasTopics) {

  public PendingRedeploy {
    maasTopics = maasTopics == null ? List.of() : List.copyOf(maasTopics);
  }

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId) {
    this(
        chainId,
        domain,
        existingDeploymentId,
        operationId,
        snapshotId,
        false,
        false,
        false,
        false,
        List.of());
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
        false,
        false,
        List.of());
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
        false,
        false,
        List.of());
  }

  public PendingRedeploy(
      String chainId,
      String domain,
      String existingDeploymentId,
      String operationId,
      String snapshotId,
      boolean confirmFirstDeploy,
      boolean undeploy,
      boolean waitingForLoggingLevel) {
    this(
        chainId,
        domain,
        existingDeploymentId,
        operationId,
        snapshotId,
        confirmFirstDeploy,
        undeploy,
        waitingForLoggingLevel,
        false,
        List.of());
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

  /**
   * After a Kafka/MaaS missing-topic follow-up, wait for Create topics or Not now. Create and
   * dismiss require this flag and a matching {@code operationId}.
   */
  public static PendingRedeploy maasTopicsWait(
      String chainId,
      String domain,
      String snapshotId,
      String operationId,
      List<MaasKafkaTopicsFollowUp.TopicPair> topics) {
    return new PendingRedeploy(
        chainId,
        domain,
        null,
        operationId,
        snapshotId,
        false,
        false,
        false,
        true,
        topics);
  }

  public boolean waitingForDomain() {
    return operationId == null;
  }
}
