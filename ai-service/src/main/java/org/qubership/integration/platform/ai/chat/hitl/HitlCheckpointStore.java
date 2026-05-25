package org.qubership.integration.platform.ai.chat.hitl;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for pending HITL checkpoint futures.
 *
 * <p>When a scenario graph reaches a checkpoint node, it registers a future here and blocks on it.
 * When the user submits an answer via the REST API, the future is completed and the graph resumes.
 *
 * <p><b>Note:</b> This is an in-memory implementation suitable for a single-instance deployment.
 * For HA/multi-instance deployments, replace with a distributed store (e.g., Redis Pub/Sub or a
 * database-backed polling mechanism).
 */
@ApplicationScoped
public class HitlCheckpointStore {

  private static final Logger LOG = Logger.getLogger(HitlCheckpointStore.class);

  private final ConcurrentHashMap<String, CompletableFuture<HitlCheckpointAnswer>> pending =
      new ConcurrentHashMap<>();

  /**
   * Registers a new checkpoint and returns a future that will be resolved when the user submits an
   * answer.
   */
  public CompletableFuture<HitlCheckpointAnswer> register(String checkpointId) {
    CompletableFuture<HitlCheckpointAnswer> future = new CompletableFuture<>();
    pending.put(checkpointId, future);
    LOG.infof("HITL checkpoint registered: %s", checkpointId);
    return future;
  }

  /**
   * Resolves a pending checkpoint with the user's answer. Returns true if the checkpoint was found
   * and resolved; false if it was not found.
   */
  public boolean resolve(String checkpointId, HitlCheckpointAnswer answer) {
    CompletableFuture<HitlCheckpointAnswer> future = pending.remove(checkpointId);
    if (future == null) {
      LOG.warnf("HITL checkpoint not found or already resolved: %s", checkpointId);
      return false;
    }
    future.complete(answer);
    LOG.infof("HITL checkpoint resolved: %s → %s", checkpointId, answer.getAnswer());
    return true;
  }

  /** Cancels and removes a pending checkpoint (e.g., on timeout or conversation end). */
  public void cancel(String checkpointId) {
    CompletableFuture<HitlCheckpointAnswer> future = pending.remove(checkpointId);
    if (future != null) {
      future.cancel(true);
      LOG.infof("HITL checkpoint cancelled: %s", checkpointId);
    }
  }
}
