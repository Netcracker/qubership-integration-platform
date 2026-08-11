package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Explicit mapping intent captured on a {@link RequirementBrief} before normalized flow step IDs
 * exist. {@code fromIntentRef} / {@code toIntentRef} point at trigger or service-call fact ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementDataMapping(
    String mappingId,
    Stage stage,
    String fromIntentRef,
    String toIntentRef,
    Mode mode,
    List<Rule> rules,
    List<String> sourceFactIds) {

  public RequirementDataMapping {
    mappingId = mappingId == null ? "" : mappingId.trim();
    fromIntentRef = fromIntentRef == null ? "" : fromIntentRef.trim();
    toIntentRef = toIntentRef == null ? "" : toIntentRef.trim();
    rules = rules == null ? List.of() : List.copyOf(rules);
    sourceFactIds = sourceFactIds == null ? List.of() : List.copyOf(sourceFactIds);
  }

  public enum Stage {
    INITIALIZATION,
    CONVERSION,
    RESPONSE
  }

  public enum Mode {
    EXPLICIT,
    PASS_THROUGH
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Rule(String sourcePath, String targetPath, String expression) {

    public Rule {
      sourcePath = sourcePath == null ? "" : sourcePath.trim();
      targetPath = targetPath == null ? "" : targetPath.trim();
      expression = expression == null || expression.isBlank() ? null : expression.trim();
    }
  }
}
