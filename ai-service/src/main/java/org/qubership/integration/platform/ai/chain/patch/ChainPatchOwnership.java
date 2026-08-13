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
 * a schema for it at all. Elements and connections may be added; removal and renaming are refused by
 * the validator whatever this policy says.
 */
@ApplicationScoped
public class ChainPatchOwnership {

  private final DeterministicElementSchemaService schemaService;

  @Inject
  public ChainPatchOwnership(DeterministicElementSchemaService schemaService) {
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
  }

  public GraphPatchOwnershipPolicy forChain(ChainPlanGraph graph, GraphPatch patch) {
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
        true, true, Set.copyOf(properties.keySet()), Set.of(), properties);
  }

  private void addType(Map<String, Set<String>> properties, String type) {
    if (type != null && !properties.containsKey(type)) {
      properties.put(type, schemaService.allowedPatchPropertyKeys(type));
    }
  }
}
