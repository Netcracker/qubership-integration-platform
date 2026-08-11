package org.qubership.integration.platform.ai.a2a.transport;

/**
 * @deprecated Use {@link A2aClientCorrelationCarrier}. Kept temporarily so existing unit tests that
 *     set ThreadLocal values continue to compile until they migrate.
 */
@Deprecated
public final class A2aClientCorrelationIds {

  private A2aClientCorrelationIds() {}

  public record Holder(String taskId, String contextId) {}

  public static void set(String taskId, String contextId) {
    // No-op: ThreadLocal correlation is retired. Bind through A2aClientCorrelationCarrier.
  }

  public static Holder current() {
    return new Holder(null, null);
  }

  public static void clear() {
    // No-op.
  }
}
