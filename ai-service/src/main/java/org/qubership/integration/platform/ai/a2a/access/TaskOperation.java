package org.qubership.integration.platform.ai.a2a.access;

/**
 * Application-level A2A Task operations checked by {@link TaskAccessPolicy}.
 *
 * <p>Frozen by prompt 04. Distinct from the SDK {@code org.a2aproject.sdk.server.auth.TaskOperation}.
 */
public enum TaskOperation {
  CREATE,
  READ,
  CONTINUE,
  SUBSCRIBE,
  APPROVE,
  CANCEL
}
