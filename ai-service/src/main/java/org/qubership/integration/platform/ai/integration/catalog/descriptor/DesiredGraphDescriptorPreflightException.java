package org.qubership.integration.platform.ai.integration.catalog.descriptor;

/**
 * The desired graph violates a catalog descriptor rule. Callers must not mutate the chain.
 */
public final class DesiredGraphDescriptorPreflightException extends RuntimeException {

  public DesiredGraphDescriptorPreflightException(String message) {
    super(message);
  }

  public DesiredGraphDescriptorPreflightException(String message, Throwable cause) {
    super(message, cause);
  }
}
