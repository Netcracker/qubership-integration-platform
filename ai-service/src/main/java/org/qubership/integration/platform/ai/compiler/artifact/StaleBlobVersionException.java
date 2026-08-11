package org.qubership.integration.platform.ai.compiler.artifact;

/** Raised when a compare-and-set blob write loses to a concurrent updater. */
public final class StaleBlobVersionException extends RuntimeException {

  public StaleBlobVersionException(String message) {
    super(message);
  }

  public StaleBlobVersionException(String message, Throwable cause) {
    super(message, cause);
  }
}
