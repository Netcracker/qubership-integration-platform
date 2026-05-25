package org.qubership.integration.platform.ai.chat.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates Human-in-the-Loop checkpoints.
 *
 * <p>Responsible for:
 *
 * <ul>
 *   <li>Creating {@link HitlCheckpoint} objects with unique IDs
 *   <li>Serializing checkpoints to SSE-compatible JSON for streaming to the frontend
 *   <li>Blocking scenario graph threads until the user answers (with timeout)
 *   <li>Delegating answer resolution to {@link HitlCheckpointStore}
 * </ul>
 */
@ApplicationScoped
public class HitlService {

  private static final Logger LOG = Logger.getLogger(HitlService.class);

  @Inject HitlCheckpointStore store;

  @Inject AppConfig config;

  @Inject ObjectMapper objectMapper;

  /**
   * Creates a HITL checkpoint, serializes it to JSON, and blocks until the user answers. The caller
   * should emit the returned {@link HitlCheckpoint} as an SSE event of type {@code hitl_checkpoint}
   * before calling this method.
   *
   * @param conversationId conversation this checkpoint belongs to
   * @param question the question to present to the user
   * @param options optional list of allowed choices; null for free-text answer
   * @return the checkpoint (contains the checkpointId for the SSE event payload)
   */
  public HitlCheckpoint createCheckpoint(
      String conversationId, String question, List<String> options) {
    String checkpointId = UUID.randomUUID().toString();
    return new HitlCheckpoint(checkpointId, conversationId, question, options);
  }

  /** Serializes a checkpoint to JSON string for SSE emission. */
  public String serializeCheckpoint(HitlCheckpoint checkpoint) {
    try {
      return objectMapper.writeValueAsString(checkpoint);
    } catch (Exception e) {
      return "{\"checkpointId\":\"" + checkpoint.getCheckpointId() + "\",\"question\":\"error\"}";
    }
  }

  /**
   * Registers the checkpoint and blocks until the user answers or the timeout expires.
   *
   * @param checkpointId the checkpoint ID from {@link #createCheckpoint}
   * @return the user's answer
   * @throws TimeoutException if the user doesn't answer within the configured timeout
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public HitlCheckpointAnswer awaitAnswer(String checkpointId)
      throws TimeoutException, InterruptedException {
    CompletableFuture<HitlCheckpointAnswer> future = store.register(checkpointId);
    try {
      return future.get(config.hitl().timeoutSeconds(), TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new RuntimeException("HITL checkpoint failed", e.getCause());
    } catch (TimeoutException e) {
      store.cancel(checkpointId);
      LOG.warnf("HITL checkpoint timed out: %s", checkpointId);
      throw e;
    }
  }

  /** Resolves a checkpoint with the user's answer (called from HitlResource). */
  public boolean resolveCheckpoint(String checkpointId, HitlCheckpointAnswer answer) {
    return store.resolve(checkpointId, answer);
  }
}
