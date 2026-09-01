package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

/**
 * LLM-facing mapping row: source and target refs plus field rules. Mapping ports are not captured;
 * {@link RequirementBriefProjector} assigns them from the approved flow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapturedMappingIntent(
    String mappingIntentId,
    String sourceRef,
    String targetRef,
    List<MappingIntentRule> rules,
    String implementationPreference) {

  public CapturedMappingIntent {
    mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
    sourceRef = sourceRef == null ? "" : sourceRef.trim();
    targetRef = targetRef == null ? "" : targetRef.trim();
    rules = rules == null ? List.of() : List.copyOf(rules);
    implementationPreference =
        implementationPreference == null || implementationPreference.isBlank()
            ? null
            : implementationPreference.trim();
  }

  @JsonIgnore
  public CapturedMappingIntent(
      String mappingIntentId,
      String sourceRef,
      String targetRef,
      List<MappingIntentRule> rules) {
    this(mappingIntentId, sourceRef, targetRef, rules, null);
  }

  public MappingIntent toIntent() {
    return new MappingIntent(
        mappingIntentId,
        sourceRef,
        null,
        targetRef,
        null,
        rules,
        implementationPreference);
  }
}
