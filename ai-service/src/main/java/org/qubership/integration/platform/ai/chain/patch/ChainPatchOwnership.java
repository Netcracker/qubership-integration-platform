package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * What a chat-driven chain patch is allowed to touch.
 *
 * <p>Derived from the chain and the change proposed against it rather than pinned. A property is
 * owned when the element schema accepts it on that element's type — the same source the properties
 * materializer uses to decide what it will write — and an element type is owned when the catalog has
 * a schema for it at all. Elements and connections may be added, and removed when the caller asks
 * for it. Renaming an existing element, and removing a property or a chain field, stay refused by
 * the validator whatever this policy says.
 */
@ApplicationScoped
public class ChainPatchOwnership {

  private final DeterministicElementSchemaService schemaService;

  @Inject
  public ChainPatchOwnership(DeterministicElementSchemaService schemaService) {
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
  }

  /** Additive policy: what a patch may add and reconfigure, with removal off. */
  public GraphPatchOwnershipPolicy forChain(ChainPlanGraph graph, GraphPatch patch) {
    return forChain(graph, patch, false);
  }

  /**
   * @param mayRemove whether this caller may delete <em>elements</em>. Off unless the caller says
   *     otherwise: a deleted element is the one thing here nothing downstream can take back.
   *     Connections are not gated by it. A connection carries no id or property of its own, so
   *     cutting one is undone by drawing it again, and the pipeline has to be free to cut one on its
   *     own account -- inserting an element between two that are joined means the join it replaces
   *     goes with it.
   */
  public GraphPatchOwnershipPolicy forChain(
      ChainPlanGraph graph, GraphPatch patch, boolean mayRemove) {
    Objects.requireNonNull(graph, "graph");
    Map<String, Set<String>> properties = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      addType(properties, node == null ? null : node.type());
    }
    if (patch != null && patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        ChainPlanNode node = nodePatch == null ? null : nodePatch.node();
        String type = node == null ? null : node.type();
        // A type the chain does not have yet is ownable only if the catalog knows it.
        if (type != null && schemaService.hasElementSchema(type)) {
          addType(properties, type);
        }
      }
    }
    return new GraphPatchOwnershipPolicy(
        true, true, mayRemove, true, Set.copyOf(properties.keySet()), Set.of(), properties);
  }

  private void addType(Map<String, Set<String>> properties, String type) {
    if (type != null && !properties.containsKey(type)) {
      properties.put(type, schemaService.allowedPatchPropertyKeys(type));
    }
  }
}
