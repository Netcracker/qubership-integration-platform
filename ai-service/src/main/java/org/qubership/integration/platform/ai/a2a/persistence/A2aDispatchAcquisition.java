package org.qubership.integration.platform.ai.a2a.persistence;

import java.util.Objects;
import java.util.UUID;

/** Result of attempting to acquire exclusive receipt dispatch ownership. */
public record A2aDispatchAcquisition(Result result, UUID ownerToken) {

  public enum Result {
    ACQUIRED,
    BUSY,
    COMPLETED
  }

  public A2aDispatchAcquisition {
    Objects.requireNonNull(result, "result");
    if (result == Result.ACQUIRED) {
      Objects.requireNonNull(ownerToken, "ownerToken");
    }
  }

  public static A2aDispatchAcquisition acquired(UUID ownerToken) {
    return new A2aDispatchAcquisition(Result.ACQUIRED, ownerToken);
  }

  public static A2aDispatchAcquisition busy() {
    return new A2aDispatchAcquisition(Result.BUSY, null);
  }

  public static A2aDispatchAcquisition completed() {
    return new A2aDispatchAcquisition(Result.COMPLETED, null);
  }
}
