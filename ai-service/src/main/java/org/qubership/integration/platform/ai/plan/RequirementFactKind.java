package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** Kind of an explicit requirement fact preserved from the approved draft. */
public enum RequirementFactKind {
  GOAL,
  ENDPOINT,
  PARAMETER,
  BEHAVIOR,
  CONSTRAINT,
  CAPABILITY,
  VISIBILITY,
  ROUTING,
  /** Downstream catalog/API call named in the requirement (LLM often emits this label). */
  SERVICE_CALL;

  /**
   * Tolerant tool-argument parsing: accept known values and common aliases; unknown labels become
   * {@code null} so {@link RequirementFact} can default from polarity instead of failing the whole
   * {@code captureRequirementDraft} call.
   */
  @JsonCreator
  public static RequirementFactKind fromToolValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized =
        raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    for (RequirementFactKind kind : values()) {
      if (kind.name().equals(normalized)) {
        return kind;
      }
    }
    return switch (normalized) {
      case "SERVICE", "API_CALL", "INTEGRATION", "SERVICECALL" -> SERVICE_CALL;
      case "HTTP", "PATH", "URL", "ROUTE" -> ENDPOINT;
      case "LIMIT", "MUST_NOT", "FORBIDDEN" -> CONSTRAINT;
      default -> null;
    };
  }
}
