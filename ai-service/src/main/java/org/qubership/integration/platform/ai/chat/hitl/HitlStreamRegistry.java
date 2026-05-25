package org.qubership.integration.platform.ai.chat.hitl;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped registry that maps conversation IDs to Mutiny emitters.
 *
 * <p>Scenario handlers register an emitter before invoking an AI agent. During tool execution,
 * {@link org.qubership.integration.platform.ai.chat.hitl.HitlTool} looks up the emitter by
 * conversation ID and pushes HITL checkpoint events into the SSE stream.
 *
 * <p>Thread-safe: multiple conversations may be active concurrently.
 */
@ApplicationScoped
public class HitlStreamRegistry {

  private static final Logger LOG = Logger.getLogger(HitlStreamRegistry.class);

  private final ConcurrentHashMap<String, MultiEmitter<? super String>> emitters =
      new ConcurrentHashMap<>();

  /**
   * Registers an emitter for the given conversation. Called by scenario handlers before starting
   * the agent stream.
   */
  public void register(String conversationId, MultiEmitter<? super String> emitter) {
    emitters.put(conversationId, emitter);
    LOG.debugf("HITL emitter registered for conversation %s", conversationId);
  }

  /**
   * Emits a HITL event into the SSE stream for the given conversation. The event is prefixed with
   * {@code [HITL]} so that {@link org.qubership.integration.platform.ai.chat.rest.ChatController}
   * formats it as an {@code hitl_checkpoint} SSE event.
   *
   * @return true if the emitter was found and the event was emitted
   */
  public boolean emit(String conversationId, String hitlJson) {
    MultiEmitter<? super String> emitter = emitters.get(conversationId);
    if (emitter == null) {
      LOG.warnf("No HITL emitter found for conversation %s", conversationId);
      return false;
    }
    emitter.emit("[HITL]" + hitlJson);
    LOG.debugf("HITL event emitted for conversation %s", conversationId);
    return true;
  }

  /**
   * Unregisters and completes the emitter for the given conversation. Called in stream
   * completion/error callbacks to clean up resources.
   */
  public void unregister(String conversationId) {
    MultiEmitter<? super String> emitter = emitters.remove(conversationId);
    if (emitter != null) {
      LOG.debugf("HITL emitter unregistered for conversation %s", conversationId);
    }
  }

  /**
   * Wraps an agent's token stream with HITL capability by creating a shared emitter that both the
   * agent stream and {@link org.qubership.integration.platform.ai.chat.hitl.HitlTool} can write to.
   *
   * <p>Use this in scenario handlers whose agent has HITL tools registered.
   *
   * @param conversationId the conversation ID (used as lookup key for the emitter)
   * @param agentStream the {@code Multi<String>} returned by {@code agent.chat(...)}
   * @return a new Multi that merges agent tokens with HITL checkpoint events
   */
  public Multi<String> wrapWithHitl(String conversationId, Multi<String> agentStream) {
    return Multi.createFrom()
        .<String>emitter(
            emitter -> {
              register(conversationId, emitter);
              agentStream
                  .subscribe()
                  .with(
                      emitter::emit,
                      err -> {
                        unregister(conversationId);
                        emitter.fail(err);
                      },
                      () -> {
                        unregister(conversationId);
                        emitter.complete();
                      });
            });
  }
}
