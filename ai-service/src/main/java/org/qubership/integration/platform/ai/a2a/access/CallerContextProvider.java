package org.qubership.integration.platform.ai.a2a.access;

/**
 * Resolves the caller for A2A operations.
 *
 * <p>Frozen by prompt 04. Implementations must not read identity from arbitrary A2A Message
 * metadata.
 */
public interface CallerContextProvider {

  /** Returns a stable caller for the current request or local runtime. */
  CallerContext current();
}
