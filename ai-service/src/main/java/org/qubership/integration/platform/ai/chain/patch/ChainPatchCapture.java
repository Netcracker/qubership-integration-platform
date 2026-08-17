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
   * <p>The tool asks to be called once and the model does not always oblige, and it does not oblige
   * in one of two ways. It decomposes -- add the element in one call, cut the connection it replaces
   * in the next -- and it corrects, re-sending the whole change with the part it forgot. Keeping
   * only the last call threw away most of a decomposed edit; appending blindly turned a correction
   * into a duplicate of everything it repeated. So operations are folded by what they act on: a
   * later call that names the same element again replaces what the earlier one said about it, and
   * anything it does not name is left standing.
   *
   * <p>Nothing here judges whether the pieces fit. Adding and removing the same element is still a
   * contradiction, kept as two entries for the removal closure to refuse rather than quietly
   * resolved by whichever call came last.
   */
  public ChainPatchCapture mergedWith(ChainPatchCapture later) {
    if (later == null) {
      return this;
    }
    return new ChainPatchCapture(
        patchId != null ? patchId : later.patchId(),
        fold(nodePatches, later.nodePatches(), ChainPatchCapture::nodeKey),
        fold(edgePatches, later.edgePatches(), ChainPatchCapture::edgeKey),
        fold(propertyPatches, later.propertyPatches(), ChainPatchCapture::propertyKey),
        joinRationales(rationale, later.rationale()));
  }

  /** Earlier first, later winning on a repeat, and the position of first mention preserved. */
  private static <T> List<T> fold(
      List<T> first, List<T> second, java.util.function.Function<T, String> key) {
    if (first == null || first.isEmpty()) {
      return second == null ? List.of() : List.copyOf(second);
    }
    if (second == null || second.isEmpty()) {
      return List.copyOf(first);
    }
    java.util.Map<String, T> folded = new java.util.LinkedHashMap<>();
    for (T patch : first) {
      folded.put(key.apply(patch), patch);
    }
    for (T patch : second) {
      folded.put(key.apply(patch), patch);
    }
    return List.copyOf(folded.values());
  }

  /** Operation stays in the key, so an add and a remove of one element read as the conflict it is. */
  private static String nodeKey(NodePatch patch) {
    if (patch == null) {
      return "node:null";
    }
    String id =
        patch.node() != null && patch.node().nodeId() != null
            ? patch.node().nodeId()
            : patch.targetNodeId();
    return "node:" + patch.operation() + ":" + id;
  }

  private static String edgeKey(EdgePatch patch) {
    if (patch == null) {
      return "edge:null";
    }
    String id =
        patch.edge() != null && patch.edge().edgeId() != null
            ? patch.edge().edgeId()
            : patch.targetEdgeId();
    return "edge:" + patch.operation() + ":" + id;
  }

  /** Keyed without the operation: a second value for one key is a correction, not a contradiction. */
  private static String propertyKey(PropertyPatch patch) {
    if (patch == null) {
      return "property:null";
    }
    String key = patch.property() == null ? null : patch.property().key();
    return "property:" + patch.targetNodeId() + ":" + key;
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
