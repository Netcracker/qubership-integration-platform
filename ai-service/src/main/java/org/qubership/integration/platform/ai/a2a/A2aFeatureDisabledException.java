package org.qubership.integration.platform.ai.a2a;

/** Raised when an A2A operation is attempted while {@code qip.ai.a2a.enabled=false}. */
public class A2aFeatureDisabledException extends RuntimeException {

  public A2aFeatureDisabledException(String message) {
    super(message);
  }
}
