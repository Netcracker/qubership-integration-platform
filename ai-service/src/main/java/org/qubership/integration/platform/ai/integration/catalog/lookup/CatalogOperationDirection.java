package org.qubership.integration.platform.ai.integration.catalog.lookup;

import java.util.Locale;
import java.util.Optional;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Catalog operation direction from the specification owner's point of view. HTTP operations are
 * consumed by the catalog system. Async publish/send are produced by it; subscribe/receive are
 * consumed by it.
 */
public enum CatalogOperationDirection {
  PRODUCED_BY_SYSTEM,
  CONSUMED_BY_SYSTEM;

  public static Optional<CatalogOperationDirection> from(String protocol, String method) {
    String transport = normalize(protocol);
    String verb = normalize(method);
    if (transport == null || verb == null) {
      return Optional.empty();
    }
    if (isHttp(transport)) {
      return Optional.of(CONSUMED_BY_SYSTEM);
    }
    if (isAsync(transport)) {
      if (isProduced(verb)) {
        return Optional.of(PRODUCED_BY_SYSTEM);
      }
      if (isConsumed(verb)) {
        return Optional.of(CONSUMED_BY_SYSTEM);
      }
    }
    return Optional.empty();
  }

  private static boolean isHttp(String protocol) {
    return "http".equals(protocol) || "https".equals(protocol) || "rest".equals(protocol);
  }

  private static boolean isAsync(String protocol) {
    return "kafka".equals(protocol) || "amqp".equals(protocol);
  }

  private static boolean isProduced(String method) {
    return "publish".equals(method) || "send".equals(method);
  }

  private static boolean isConsumed(String method) {
    return "subscribe".equals(method) || "receive".equals(method);
  }

  private static String normalize(String value) {
    String trimmed = CatalogStrings.blankToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }
}
