package org.qubership.integration.platform.ai.a2a.persistence;

/**
 * Typed failure for A2A PostgreSQL persistence. Callers must not treat this as a successful
 * Task transition.
 */
public class A2aPersistenceException extends RuntimeException {

  public A2aPersistenceException(String message) {
    super(message);
  }

  public A2aPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
