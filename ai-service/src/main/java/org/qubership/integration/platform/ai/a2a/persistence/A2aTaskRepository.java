package org.qubership.integration.platform.ai.a2a.persistence;

import java.util.Optional;

/**
 * Application-owned A2A Task store. Prompts 04 and 05 call this interface; do not persist through
 * SDK {@code TaskStore} types here.
 */
public interface A2aTaskRepository {

  /**
   * Inserts a new Task at revision {@code 1}.
   *
   * @throws A2aPersistenceException when the database rejects the write
   */
  A2aPersistedTask insert(A2aTaskCreate create);

  /** Loads a Task by public {@code taskId}. */
  Optional<A2aPersistedTask> findByTaskId(String taskId);

  /**
   * Applies {@code update} when the stored revision equals {@link A2aTaskUpdate#expectedRevision()}.
   * On success the stored revision becomes {@code expectedRevision + 1}.
   */
  A2aTaskTransitionResult transition(String taskId, A2aTaskUpdate update);

  /** Readiness probe: runs a trivial database round trip. */
  void ping();
}
