package org.qubership.integration.platform.ai.chain.edit.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;

/**
 * Checks that a structural plan names real graph ids, keeps original targets in try, and matches
 * a later structure capture.
 */
public final class ChainEditStructuralPlanValidator {

  private static final String SERVICE_CALL = "service-call";
  private static final String TRY_2 = "try-2";
  private static final String CATCH_2 = "catch-2";
  private static final String FINALLY_2 = "finally-2";
  private static final String OPERATION_ID = "integrationOperationId";

  public void validate(
      ChainEditStructuralPlan plan,
      ChainPlanGraph graph,
      Map<String, OperationSchemaMaps> operationSchemas) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(graph, "graph");
    Map<String, OperationSchemaMaps> schemas =
        operationSchemas == null ? Map.of() : operationSchemas;
    if (plan.clarify()) {
      validateClarify(plan);
      return;
    }
    Map<String, ChainPlanNode> nodes = nodesById(graph);
    requireKnownIds("originalTargetNodeIds", plan.originalTargetNodeIds(), nodes);
    requireKnownIds("tryMoveExisting", plan.tryMoveExisting(), nodes);
    requireKnownIds("finallyMoveExisting", plan.finallyMoveExisting(), nodes);
    if (plan.reporterNodeId() != null) {
      requireKnownIds("reporterNodeId", List.of(plan.reporterNodeId()), nodes);
    }
    if (!plan.tryMoveSet().containsAll(plan.originalTargetNodeIds())) {
      throw invalid("original target ids must stay in tryMoveExisting");
    }
    requireConnected(graph, plan.expandedTargetNodeIds());
    switch (plan.failureDelivery()) {
      case HTTP_RESPONSE -> validateHttp(plan);
      case OM_TASK_RESULT -> validateOm(plan, nodes, schemas);
      case EXPLICIT_HANDLER -> validateExplicit(plan);
      case CLARIFY -> validateClarify(plan);
    }
  }

  public void assertCaptureMatches(ChainEditStructuralPlan plan, ChainEditSubgraph capture) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(capture, "capture");
    if (plan.clarify()) {
      throw invalid("a CLARIFY plan cannot be captured as a subgraph");
    }
    Set<String> capturedTry = moveExisting(capture, TRY_2);
    Set<String> capturedCatch = moveExisting(capture, CATCH_2);
    Set<String> capturedFinally = moveExisting(capture, FINALLY_2);
    if (!capturedTry.equals(plan.tryMoveSet())) {
      throw invalid(
          "capture tryMoveExisting "
              + capturedTry
              + " does not match the structural plan "
              + plan.tryMoveSet());
    }
    if (!capturedFinally.equals(plan.finallyMoveSet())) {
      throw invalid(
          "capture finallyMoveExisting "
              + capturedFinally
              + " does not match the structural plan "
              + plan.finallyMoveSet());
    }
    if (plan.catchRole() == CatchBranchRole.NEW_SCRIPT && !capturedCatch.isEmpty()) {
      throw invalid("a NEW_SCRIPT catch must create a script, not move an existing id");
    }
    Set<String> allowed = new LinkedHashSet<>(plan.expandedTargetNodeIds());
    Set<String> unplanned = new LinkedHashSet<>();
    unplanned.addAll(capturedTry);
    unplanned.addAll(capturedCatch);
    unplanned.addAll(capturedFinally);
    unplanned.removeAll(allowed);
    if (!unplanned.isEmpty()) {
      throw invalid("capture moves ids the structural plan did not name: " + unplanned);
    }
    if (plan.catchRole() == CatchBranchRole.NEW_SCRIPT && !catchCreatesScript(capture)) {
      throw invalid("a NEW_SCRIPT catch must declare a new script in the catch body");
    }
  }

  private static void validateClarify(ChainEditStructuralPlan plan) {
    if (plan.clarificationQuestion() == null) {
      throw invalid("CLARIFY requires clarificationQuestion");
    }
    if (plan.ambiguities().isEmpty()) {
      throw invalid("CLARIFY requires ambiguities");
    }
    if (!plan.tryMoveExisting().isEmpty() || !plan.finallyMoveExisting().isEmpty()) {
      throw invalid("CLARIFY must not move existing ids");
    }
  }

  private static void validateHttp(ChainEditStructuralPlan plan) {
    if (plan.tryMoveExisting().isEmpty()) {
      throw invalid("HTTP_RESPONSE requires tryMoveExisting");
    }
    if (!plan.finallyMoveExisting().isEmpty()) {
      throw invalid("HTTP_RESPONSE must not move ids into finally-2");
    }
    if (plan.reporterNodeId() != null || plan.reporterSchemaVariant() != null) {
      throw invalid("HTTP_RESPONSE must not name a reporter");
    }
    if (plan.catchRole() != CatchBranchRole.NEW_SCRIPT
        && plan.catchRole() != CatchBranchRole.EXPLICIT_HANDLER) {
      throw invalid("HTTP_RESPONSE requires a catch script");
    }
  }

  private static void validateOm(
      ChainEditStructuralPlan plan,
      Map<String, ChainPlanNode> nodes,
      Map<String, OperationSchemaMaps> schemas) {
    if (plan.reporterNodeId() == null) {
      throw invalid("OM_TASK_RESULT requires reporterNodeId");
    }
    ChainPlanNode reporter = nodes.get(plan.reporterNodeId());
    if (reporter == null || !SERVICE_CALL.equals(reporter.type())) {
      throw invalid("OM_TASK_RESULT reporter must be a service-call");
    }
    if (!plan.finallyMoveSet().contains(plan.reporterNodeId())) {
      throw invalid("OM_TASK_RESULT reporter must move into finally-2");
    }
    if (plan.reporterSchemaVariant() == null) {
      throw invalid("OM_TASK_RESULT requires reporterSchemaVariant");
    }
    String operationId = property(reporter, OPERATION_ID);
    OperationSchemaMaps maps = operationId == null ? null : schemas.get(operationId);
    if (maps == null || !schemaDeclaresVariant(maps, plan.reporterSchemaVariant())) {
      throw invalid(
          "unknown reporter schema variant '"
              + plan.reporterSchemaVariant()
              + "' for operation "
              + operationId);
    }
    if (plan.catchRole() != CatchBranchRole.NEW_SCRIPT
        && plan.catchRole() != CatchBranchRole.EXPLICIT_HANDLER) {
      throw invalid("OM_TASK_RESULT requires a catch script");
    }
  }

  private static void validateExplicit(ChainEditStructuralPlan plan) {
    if (plan.tryMoveExisting().isEmpty()) {
      throw invalid("EXPLICIT_HANDLER requires tryMoveExisting");
    }
  }

  private static void requireKnownIds(
      String field, List<String> ids, Map<String, ChainPlanNode> nodes) {
    Set<String> missing = new LinkedHashSet<>();
    for (String id : ids) {
      if (!nodes.containsKey(id)) {
        missing.add(id);
      }
    }
    if (!missing.isEmpty()) {
      throw invalid(field + " names unknown ids " + missing);
    }
  }

  private static void requireConnected(ChainPlanGraph graph, List<String> ids) {
    if (ids.size() <= 1) {
      return;
    }
    Set<String> wanted = new LinkedHashSet<>(ids);
    Map<String, Set<String>> adjacency = new LinkedHashMap<>();
    for (String id : wanted) {
      adjacency.put(id, new LinkedHashSet<>());
    }
    if (graph.edges() != null) {
      for (ChainPlanEdge edge : graph.edges()) {
        if (edge == null) {
          continue;
        }
        if (wanted.contains(edge.fromNodeId()) && wanted.contains(edge.toNodeId())) {
          adjacency.get(edge.fromNodeId()).add(edge.toNodeId());
          adjacency.get(edge.toNodeId()).add(edge.fromNodeId());
        }
      }
    }
    String start = ids.get(0);
    Set<String> seen = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    queue.add(start);
    seen.add(start);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (String next : adjacency.getOrDefault(current, Set.of())) {
        if (seen.add(next)) {
          queue.add(next);
        }
      }
    }
    if (!seen.containsAll(wanted)) {
      Set<String> leftover = new LinkedHashSet<>(wanted);
      leftover.removeAll(seen);
      throw invalid("moved ids are not a connected section of the graph: " + leftover);
    }
  }

  static boolean schemaDeclaresVariant(OperationSchemaMaps maps, String variant) {
    if (maps == null || variant == null || variant.isBlank()) {
      return false;
    }
    // Catalog AsyncAPI names channel messages as responseSchemas keys (task-failed), not titles.
    if (maps.responseByStatusThenContentType().containsKey(variant)) {
      return true;
    }
    for (JsonNode schema : maps.requestByContentType().values()) {
      if (declaresVariant(schema, variant)) {
        return true;
      }
    }
    for (Map<String, JsonNode> byContent : maps.responseByStatusThenContentType().values()) {
      for (JsonNode schema : byContent.values()) {
        if (declaresVariant(schema, variant)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean declaresVariant(JsonNode schema, String variant) {
    if (schema == null || schema.isNull() || schema.isMissingNode()) {
      return false;
    }
    String schemaId = schema.path("$id").asText();
    if (variant.equals(schema.path("title").asText())
        || variant.equals(schema.path("x-variant").asText())
        || variant.equals(schema.path("const").asText())
        || schemaId.endsWith("/" + variant)) {
      return true;
    }
    for (String field : List.of("oneOf", "anyOf", "allOf")) {
      JsonNode options = schema.get(field);
      if (options != null && options.isArray()) {
        for (JsonNode option : options) {
          if (declaresVariant(option, variant)) {
            return true;
          }
        }
      }
    }
    JsonNode properties = schema.get("properties");
    if (properties != null && properties.isObject()) {
      var fields = properties.fields();
      while (fields.hasNext()) {
        if (declaresVariant(fields.next().getValue(), variant)) {
          return true;
        }
      }
    }
    return false;
  }

  private static Map<String, ChainPlanNode> nodesById(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> nodes = new LinkedHashMap<>();
    if (graph.nodes() == null) {
      return nodes;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        nodes.put(node.nodeId(), node);
      }
    }
    return nodes;
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        String value = property.value();
        return value == null || value.isBlank() ? null : value;
      }
    }
    return null;
  }

  private static Set<String> moveExisting(ChainEditSubgraph capture, String childType) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      if (childType.equals(branch.childType())) {
        ids.addAll(branch.moveExisting());
      }
    }
    return ids;
  }

  private static boolean catchCreatesScript(ChainEditSubgraph capture) {
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      if (!CATCH_2.equals(branch.childType()) || branch.body() == null) {
        continue;
      }
      return branch.body().elements().stream()
          .anyMatch(element -> element != null && "script".equals(element.type()));
    }
    return false;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
