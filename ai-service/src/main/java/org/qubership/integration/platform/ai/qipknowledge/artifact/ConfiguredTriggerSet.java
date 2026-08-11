package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Set of configured triggers produced before chain structure generation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfiguredTriggerSet(
    int schemaVersion,
    List<ConfiguredTrigger> triggers,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations) {

  public ConfiguredTriggerSet {
    triggers = triggers == null ? List.of() : List.copyOf(triggers);
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }
}
