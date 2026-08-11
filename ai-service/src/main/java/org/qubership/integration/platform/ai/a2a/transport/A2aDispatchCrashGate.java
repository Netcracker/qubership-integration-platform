package org.qubership.integration.platform.ai.a2a.transport;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic crash-injection seam for A2A receipt recovery tests.
 *
 * <p>Production boots leave the gate disarmed ({@link Point#NONE}). Tests arm a one-shot failure
 * window, then retry the same caller-scoped Message to prove resumable dispatch.
 */
@ApplicationScoped
public class A2aDispatchCrashGate {

  public enum Point {
    NONE,
    /** After atomic claim / incomplete resume ownership, before {@code markDispatching}. */
    AFTER_CLAIM,
    /** After {@code DISPATCHING}, before the first facade command invocation. */
    AFTER_DISPATCHING,
    /**
     * After a durable runtime step commits and before the projected A2A Task is persisted. This is
     * the window where the run document has advanced but the public Task still shows the old
     * revision.
     */
    AFTER_RUNTIME_COMMIT,
    /** After the first projected Task revision is durable, before receipt {@code COMPLETED}. */
    AFTER_FIRST_PERSIST,
    /** After receipt {@code COMPLETED}, before the HTTP handler returns. */
    AFTER_COMPLETED
  }

  private final AtomicReference<Point> armed = new AtomicReference<>(Point.NONE);

  public void arm(Point point) {
    armed.set(Objects.requireNonNull(point, "point"));
  }

  public void clear() {
    armed.set(Point.NONE);
  }

  public Point armedPoint() {
    return armed.get();
  }

  /**
   * Throws {@link A2aInjectedDispatchCrashException} once when {@code point} matches the armed
   * window, then clears the gate.
   */
  public void check(Point point) {
    Objects.requireNonNull(point, "point");
    if (point == Point.NONE) {
      return;
    }
    if (armed.compareAndSet(point, Point.NONE)) {
      throw new A2aInjectedDispatchCrashException(point);
    }
  }
}
