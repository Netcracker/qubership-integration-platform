package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Explicit business-data adaptation across one source port and one target port. Pass-through is
 * the absence of a mapping intent, not a row in this list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingIntent(
    @JsonProperty("mappingIntentId") String mappingIntentId,
    @JsonProperty("sourceRef") String sourceRef,
    @JsonProperty("sourcePort") MappingPort sourcePort,
    @JsonProperty("targetRef") String targetRef,
    @JsonProperty("targetPort") MappingPort targetPort,
    @JsonProperty("rules") List<MappingIntentRule> rules,
    @JsonProperty("implementationPreference") String implementationPreference) {

  public MappingIntent {
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
  public MappingIntent(
      String mappingIntentId,
      String sourceRef,
      MappingPort sourcePort,
      String targetRef,
      MappingPort targetPort,
      List<MappingIntentRule> rules) {
    this(mappingIntentId, sourceRef, sourcePort, targetRef, targetPort, rules, null);
  }

  public MappingIntent withRules(List<MappingIntentRule> newRules) {
    return new MappingIntent(
        mappingIntentId,
        sourceRef,
        sourcePort,
        targetRef,
        targetPort,
        newRules,
        implementationPreference);
  }
}
