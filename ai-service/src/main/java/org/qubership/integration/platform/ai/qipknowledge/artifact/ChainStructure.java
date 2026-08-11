package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * First complete {@link ChainPlanGraph} revision produced by structure generation.
 *
 * <p>Carries no schema version of its own. It used to, and the generated tool schema then offered a
 * model two fields named {@code schemaVersion} one nesting level apart with different types — an
 * integer here and the {@code "1.0"} string on {@link ChainPlanGraph}. Models merged the two and
 * emitted the key twice inside {@code graph}, which no record can bind. Older stored payloads still
 * carry the field; {@code ignoreUnknown} drops it on read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainStructure(
    ChainPlanGraph graph,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations) {

  public ChainStructure {
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }
}
