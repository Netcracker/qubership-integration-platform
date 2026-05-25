package org.qubership.integration.platform.ai.integration.catalog.util;

/** Small string helpers shared by catalog tools and services. */
public final class CatalogStrings {

  private CatalogStrings() {}

  /** {@code null} or blank → {@code null}; otherwise trimmed text. */
  public static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }
}
