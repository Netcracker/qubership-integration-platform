package org.qubership.integration.platform.ai.integration.catalog.util;

import java.util.regex.Pattern;

/** Shared catalog id shape heuristics. */
public final class CatalogIdPatterns {

  public static final Pattern UUID_LIKE =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
          Pattern.CASE_INSENSITIVE);

  private CatalogIdPatterns() {}

  public static boolean isUuidLike(String value) {
    return value != null && UUID_LIKE.matcher(value).matches();
  }
}
