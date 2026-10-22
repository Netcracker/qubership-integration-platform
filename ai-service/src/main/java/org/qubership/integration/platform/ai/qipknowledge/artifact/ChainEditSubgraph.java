package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * What a structural edit adds to a chain, rather than what the chain becomes.
 *
 * <p>A wrap or a branch names a container, its branches, and the elements created inside each
 * branch. An insertion has no container, so it names none of those and carries its new elements in
 * {@code body} instead. An element the chain already has appears only as an identifier in the
 * branch it moves into, so the whole class of defect where a capture rewrites, drops, or reparents
 * an element the edit never named stops being expressible instead of being caught afterwards.
 *
 * <p>Java assembles the resulting graph from this: it places the container beside the elements that
 * move into it, or splices {@code body} at the address the edit already resolved, and derives the
 * connections between the edit and the chain around it either way. The capture supplies shape;
 * every decision that follows from shape is Java's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraph(
    @Description(
            "Catalog type of the container this edit adds, e.g. try-catch-finally-2;"
                + " omit for an insertion, which has no container")
        String containerType,
    @Description("Human-readable label for the container; omit for an insertion")
        String containerLabel,
    @Description("Branches of the container, one entry per branch; omit for an insertion")
        List<ChainEditSubgraphBranch> branches,
    @Description(
            "New elements and the connections between them, for an insertion that has no"
                + " container; omit when containerType is set")
        ChainEditSubgraphBody body) {

  public ChainEditSubgraph {
    branches = branches == null ? List.of() : List.copyOf(branches);
    body = body == null ? new ChainEditSubgraphBody(List.of(), List.of()) : body;
  }

  /** A wrap or branch capture, carrying no insertion body. */
  public ChainEditSubgraph(
      String containerType, String containerLabel, List<ChainEditSubgraphBranch> branches) {
    this(containerType, containerLabel, branches, null);
  }
}
