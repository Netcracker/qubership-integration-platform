package org.qubership.integration.platform.ai.chain.edit.planning;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Inputs for one pinned {@code cip-chain-edit-planner} invocation. */
public record ChainEditPlannerRequest(
    String conversationId,
    String pinnedSkillHash,
    String userRequest,
    ChainPlanGraph graph,
    ChainEditIntent intent,
    List<ResolvedServiceCallBinding> bindings,
    Map<String, OperationSchemaMaps> operationSchemas) {

  public ChainEditPlannerRequest {
    conversationId = requireText(conversationId, "conversationId");
    pinnedSkillHash = requireText(pinnedSkillHash, "pinnedSkillHash");
    userRequest = userRequest == null ? "" : userRequest;
    graph = Objects.requireNonNull(graph, "graph");
    intent = Objects.requireNonNull(intent, "intent");
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    operationSchemas = operationSchemas == null ? Map.of() : Map.copyOf(operationSchemas);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
