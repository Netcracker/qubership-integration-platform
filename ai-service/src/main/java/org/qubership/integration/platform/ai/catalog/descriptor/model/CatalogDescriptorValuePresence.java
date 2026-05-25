package org.qubership.integration.platform.ai.catalog.descriptor.model;

import java.util.Collection;
import java.util.Map;

/**
 * Approximates Apache {@code ObjectUtils.isNotEmpty} for catalog property maps (PATCH JSON values).
 */
public final class CatalogDescriptorValuePresence {

  private CatalogDescriptorValuePresence() {}

  public static boolean isPresent(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof String s) {
      return !s.isBlank();
    }
    if (value instanceof Collection<?> c) {
      return !c.isEmpty();
    }
    if (value instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    if (value instanceof Boolean || value instanceof Number) {
      return true;
    }
    return true;
  }

  public static boolean equalsIgnoreCase(String actual, String expected) {
    if (actual == null || expected == null) {
      return false;
    }
    return actual.equalsIgnoreCase(expected);
  }
}
