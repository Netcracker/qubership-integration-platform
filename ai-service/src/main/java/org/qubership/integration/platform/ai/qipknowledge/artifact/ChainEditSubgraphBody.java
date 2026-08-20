package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * The elements one branch of a structural edit creates, and how they connect to each other.
 *
 * <p>Both ends of every connection are elements of this body. Branches never connect to each other,
 * and the connections between the edit and the chain around it are derived rather than captured.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraphBody(
    @Description("New elements this branch creates") List<ChainEditSubgraphElement> elements,
    @Description("Connections between the new elements of this branch")
        List<ChainEditSubgraphConnection> connections) {

  public ChainEditSubgraphBody {
    elements = elements == null ? List.of() : List.copyOf(elements);
    connections = connections == null ? List.of() : List.copyOf(connections);
  }

  public boolean isEmpty() {
    return elements.isEmpty() && connections.isEmpty();
  }
}
