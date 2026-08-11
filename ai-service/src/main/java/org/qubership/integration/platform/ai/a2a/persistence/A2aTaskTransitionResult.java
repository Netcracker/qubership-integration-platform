package org.qubership.integration.platform.ai.a2a.persistence;

/**
 * Result of an optimistic Task transition. Not last-write-wins: a stale revision is rejected.
 */
public sealed interface A2aTaskTransitionResult
    permits A2aTaskTransitionResult.Applied, A2aTaskTransitionResult.StaleRevision {

  record Applied(A2aPersistedTask task) implements A2aTaskTransitionResult {}

  /**
   * Another writer already advanced the Task. {@code current} is the row that won.
   */
  record StaleRevision(A2aPersistedTask current) implements A2aTaskTransitionResult {}
}
