package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared null-normalization helpers for durable design artifacts. */
public final class DesignArtifacts {

  private DesignArtifacts() {}

  public static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  public static String optionalText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public static String nullableTrimmed(String value) {
    return value == null ? null : value.trim();
  }

  public static <T> List<T> copyList(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  public static Map<String, String> copyStringMap(Map<String, String> values) {
    return values == null ? Map.of() : Map.copyOf(values);
  }

  public static <T> T requireNonNull(T value, String name) {
    return Objects.requireNonNull(value, name);
  }
}
