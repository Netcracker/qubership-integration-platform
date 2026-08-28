package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** Polarity of an explicit requirement fact. */
public enum RequirementFactPolarity {
  POSITIVE,
  NEGATIVE;

  /**
   * Tolerant tool-argument parsing: unknown labels become {@code null} so {@link RequirementFact}
   * can default instead of failing the whole capture call.
   */
  @JsonCreator
  public static RequirementFactPolarity fromToolValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (RequirementFactPolarity value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    return null;
  }
}
