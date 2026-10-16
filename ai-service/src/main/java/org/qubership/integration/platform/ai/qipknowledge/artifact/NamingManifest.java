package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Chain and role labels derived from requirements for structure generation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NamingManifest(
    int schemaVersion,
    String chainName,
    Map<String, String> labelsByRoleId,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations) {

  public NamingManifest {
    labelsByRoleId = labelsByRoleId == null ? Map.of() : Map.copyOf(labelsByRoleId);
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }
}
