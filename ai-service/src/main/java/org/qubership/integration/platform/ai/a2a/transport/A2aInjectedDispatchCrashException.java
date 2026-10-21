package org.qubership.integration.platform.ai.a2a.transport;

/** One-shot failure injected by {@link A2aDispatchCrashGate} for crash-window recovery tests. */
public final class A2aInjectedDispatchCrashException extends RuntimeException {

  private final A2aDispatchCrashGate.Point point;

  public A2aInjectedDispatchCrashException(A2aDispatchCrashGate.Point point) {
    super("Injected A2A dispatch crash at " + point);
    this.point = point;
  }

  public A2aDispatchCrashGate.Point point() {
    return point;
  }
}
