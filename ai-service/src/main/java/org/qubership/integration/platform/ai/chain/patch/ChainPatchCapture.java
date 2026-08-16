package org.qubership.integration.platform.ai.chain.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * The change a model may submit against an existing chain.
 *
 * <p>Elements and connections may be added, reconfigured and removed; renaming what a chain already
 * has stays out of reach. The shape is the first bound on what a chat-driven edit can do to a chain
 * someone already runs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPatchCapture(
    @Description("Short id for this patch") String patchId,
    @Description(
            "Element changes, one entry per element. To add: operation ADD and the whole element in"
                + " node, with an id you invent in node.nodeId. To delete: operation REMOVE and the"
                + " existing element's id in targetNodeId. Never split one element across two"
                + " entries")
        List<NodePatch> nodePatches,
    @Description(
            "Connection changes, one entry per connection. To add: operation ADD and the whole"
                + " connection in edge, with an id you invent in edge.edgeId. To delete: operation"
                + " REMOVE and the existing connection's id in targetEdgeId")
        List<EdgePatch> edgePatches,
    @Description("Property changes, each naming the node id it applies to")
        List<PropertyPatch> propertyPatches,
    @Description("What the change does, in one sentence for the reader") String rationale) {

  /**
   * One change built from two tool calls, in the order the model made them.
   *
   * <p>The tool asks to be called once and the model does not always oblige: it decomposes an edit
   * -- add the element, connect it, cut the connection it replaces -- across calls. Keeping only the
   * last one silently threw away most of what it said and left a fragment that reads as a different
   * change entirely. Nothing here judges whether the pieces fit; the removal closure refuses a patch
   * that contradicts itself and the semantic validator refuses one that would break the chain.
   */
  public ChainPatchCapture mergedWith(ChainPatchCapture later) {
    if (later == null) {
      return this;
    }
    return new ChainPatchCapture(
        patchId != null ? patchId : later.patchId(),
        concat(nodePatches, later.nodePatches()),
        concat(edgePatches, later.edgePatches()),
        concat(propertyPatches, later.propertyPatches()),
        joinRationales(rationale, later.rationale()));
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    if (first == null || first.isEmpty()) {
      return second == null ? List.of() : List.copyOf(second);
    }
    if (second == null || second.isEmpty()) {
      return List.copyOf(first);
    }
    return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
  }

  /** Both sentences reach the card: each call explains only the part of the change it carried. */
  private static String joinRationales(String first, String second) {
    if (first == null || first.isBlank()) {
      return second;
    }
    if (second == null || second.isBlank() || first.contains(second)) {
      return first;
    }
    return first.strip() + " " + second.strip();
  }
}
