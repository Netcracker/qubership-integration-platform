package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
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
 *
 * <p>A structural edit that nests existing elements captures {@code subgraph} instead of
 * {@code graph}: it describes what the edit adds, and Java assembles the graph from it. Only one of
 * the two is ever present, and the capture tool stores the assembled graph either way, so nothing
 * downstream has to know which shape arrived.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainStructure(
    @Description("The whole chain this run plans, for a new chain or an edit that rebuilds it")
        ChainPlanGraph graph,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations,
    @Description("What a nesting edit adds; captured instead of graph, never beside it")
        ChainEditSubgraph subgraph) {

  public ChainStructure {
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }

  /** A structure that carries the whole graph, which is every capture except a nesting edit. */
  public ChainStructure(
      ChainPlanGraph graph,
      List<String> sourceRequirementFactIds,
      List<QipKnowledgeCitation> knowledgeCitations) {
    this(graph, sourceRequirementFactIds, knowledgeCitations, null);
  }
}
