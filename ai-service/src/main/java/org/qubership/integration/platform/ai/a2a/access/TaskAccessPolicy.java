package org.qubership.integration.platform.ai.a2a.access;

/**
 * Authorization seam for every A2A Task operation.
 *
 * <p>Frozen by prompt 04. Prompt 07 adds denial coverage without changing this contract.
 */
public interface TaskAccessPolicy {

  /**
   * Allows or denies {@code operation} for {@code caller} on {@code task}.
   *
   * @throws TaskAccessDeniedException when the caller is not permitted
   */
  void check(CallerContext caller, TaskOperation operation, TaskIdentity task);
}
