package org.qubership.integration.platform.ai.a2a.access;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;

/**
 * Local policy that permits every operation after validating arguments.
 *
 * <p>Prompt 07 hardens denials without changing {@link TaskAccessPolicy}.
 */
@ApplicationScoped
public class LocalPermitAllTaskAccessPolicy implements TaskAccessPolicy {

  @Override
  public void check(CallerContext caller, TaskOperation operation, TaskIdentity task) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(task, "task");
  }
}
