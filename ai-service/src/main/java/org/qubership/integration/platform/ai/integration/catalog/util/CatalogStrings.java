package org.qubership.integration.platform.ai.integration.catalog.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Small string helpers shared by catalog tools and services. */
public final class CatalogStrings {

  private CatalogStrings() {}

  /** {@code null} or blank → {@code null}; otherwise trimmed text. */
  public static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  /**
   * Decodes {@code %20} and other percent-encodings in a catalog id. Leaves the value unchanged
   * when it has no {@code %} or is not valid encoding. Does not treat {@code +} as a space.
   */
  public static String percentDecode(String value) {
    String trimmed = blankToNull(value);
    if (trimmed == null || trimmed.indexOf('%') < 0) {
      return trimmed;
    }
    try {
      return blankToNull(URLDecoder.decode(trimmed.replace("+", "%2B"), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException ignored) {
      return trimmed;
    }
  }
}
