package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** Semantic side of a mapping boundary on an entry point or service call. */
public enum MappingPort {
  OUTPUT,
  REQUEST,
  RESPONSE;

  /**
   * Tool-argument parsing: unknown labels become {@code null} so capture can continue instead of
   * failing the whole call.
   */
  @JsonCreator
  public static MappingPort fromToolValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (MappingPort value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    return null;
  }
}
