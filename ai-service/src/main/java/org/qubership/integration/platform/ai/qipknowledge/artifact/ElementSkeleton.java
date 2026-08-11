package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structure roles and obligations without generated node IDs, catalog IDs, scripts, or behavioral
 * properties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementSkeleton(
    int schemaVersion,
    String selectedPatternId,
    List<String> entryPointRoleIds,
    List<ElementRole> elementRoles,
    List<String> cardinalityObligations,
    List<String> requiredCapabilities,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations) {

  public ElementSkeleton {
    entryPointRoleIds = entryPointRoleIds == null ? List.of() : List.copyOf(entryPointRoleIds);
    elementRoles = elementRoles == null ? List.of() : List.copyOf(elementRoles);
    cardinalityObligations =
        cardinalityObligations == null ? List.of() : List.copyOf(cardinalityObligations);
    requiredCapabilities =
        requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }
}
