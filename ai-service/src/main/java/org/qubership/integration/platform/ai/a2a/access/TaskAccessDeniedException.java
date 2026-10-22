package org.qubership.integration.platform.ai.a2a.access;

/** Raised when {@link TaskAccessPolicy} denies an operation. */
public class TaskAccessDeniedException extends RuntimeException {

  public TaskAccessDeniedException(String message) {
    super(message);
  }
}
