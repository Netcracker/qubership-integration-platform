package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * What a structural edit adds to a chain, rather than what the chain becomes.
 *
 * <p>A capture names a container, its branches, and the elements created inside each branch. An
 * element the chain already has appears only as an identifier in the branch it moves into, so the
 * whole class of defect where a capture rewrites, drops, or reparents an element the edit never
 * named stops being expressible instead of being caught afterwards.
 *
 * <p>Java assembles the resulting graph from this: it places the container beside the elements that
 * move into it, creates each branch, and derives the connections between the edit and the chain
 * around it. The capture supplies shape; every decision that follows from shape is Java's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraph(
    @Description("Catalog type of the container this edit adds, e.g. try-catch-finally-2")
        String containerType,
    @Description("Human-readable label for the container") String containerLabel,
    @Description("Branches of the container, one entry per branch")
        List<ChainEditSubgraphBranch> branches) {

  public ChainEditSubgraph {
    branches = branches == null ? List.of() : List.copyOf(branches);
  }
}
