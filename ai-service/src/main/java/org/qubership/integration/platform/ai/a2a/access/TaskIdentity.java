package org.qubership.integration.platform.ai.a2a.access;

import java.util.Objects;

/**
 * Public Task identity for access checks.
 *
 * <p>{@code contextId} may be null for create before the adapter assigns one.
 */
public record TaskIdentity(String taskId, String contextId) {

  public TaskIdentity {
    Objects.requireNonNull(taskId, "taskId");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId must not be blank");
    }
  }
}
