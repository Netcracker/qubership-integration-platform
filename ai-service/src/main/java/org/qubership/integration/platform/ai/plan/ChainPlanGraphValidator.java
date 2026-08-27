package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Validates structural invariants of a {@link ChainPlanGraph} before it is
 * stored or materialized.
 * Returns a list of error messages; an empty list means the graph is valid.
 */
@ApplicationScoped
public class ChainPlanGraphValidator {

  private static final int MAX_ALLOWED_KEYS_IN_MESSAGE = 10;

  /**
   * Executable nodes that belong inside try-2 when reached from the try branch.
   */
  private static final Set<String> TRY_INNER_FLOW_TYPES = Set.of(
      "script",
      "condition",
      "choice",
      "service-call",
      "http-sender",
      "graphql-sender",
      "mapper-2",
      "chain-call-2",
      "chain-call",
      "loop-2",
      "split-2",
      "kafka-sender-2",
      "rabbitmq-sender-2",
      "header-modification",
      "log-record");

  private final DeterministicElementSchemaService schemaService;

  @Inject
  public ChainPlanGraphValidator(DeterministicElementSchemaService schemaService) {
    this.schemaService = schemaService;
  }

  public static boolean isTriggerElementType(String type) {
    String normalized = trim(type);
    if (normalized == null || normalized.isEmpty()) {
      return false;
    }
    return ChainElementFamilies.isTrigger(normalized);
  }

  private static String trim(String type) {
    return type != null ? type.trim() : null;
  }

  public List<String> validate(ChainPlanGraph graph) {
    var errors = new ArrayList<String>();

    if (graph.nodes() == null || graph.nodes().isEmpty()) {
      errors.add("nodes must not be empty");
      return errors;
    }

    Set<String> knownIds = collectNodeIds(graph.nodes(), errors);
    Map<String, ChainPlanNode> nodesById = indexNodes(graph.nodes());
    checkParentRefs(graph.nodes(), knownIds, errors);
    checkTriggerContainment(graph.nodes(), errors);
    checkWrapperShellContainment(graph.nodes(), nodesById, errors);
    checkContainmentCycles(graph.nodes(), errors);
    checkPropertyKeys(graph.nodes(), errors);
    if (graph.edges() != null) {
      checkEdgeRefs(graph.edges(), knownIds, errors);
      checkContainerFlowContainment(graph.edges(), nodesById, errors);
      checkSiblingFlowConnectivity(graph.nodes(), graph.edges(), nodesById, errors);
    }
    checkTriggerFlow(graph.nodes(), graph.edges(), errors);

    return errors;
  }

  /**
   * Adds execution edges between disconnected flow siblings under the same parent container.
   * Chains siblings in plan node order when any sibling lacks an execution edge to another sibling.
   */
  public ChainPlanGraph normalizeMissingSiblingExecutionEdges(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return graph;
    }
    List<ChainPlanEdge> edges =
        graph.edges() != null ? new ArrayList<>(graph.edges()) : new ArrayList<>();
    Map<String, ChainPlanNode> nodesById = indexNodes(graph.nodes());
    Map<String, List<ChainPlanNode>> byParent = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      String parentKey = blankToNull(node.parentNodeId());
      byParent.computeIfAbsent(parentKey == null ? "" : parentKey, ignored -> new ArrayList<>()).add(node);
    }
    Set<String> existingEdgeIds =
        edges.stream()
            .map(ChainPlanEdge::edgeId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
    for (Map.Entry<String, List<ChainPlanNode>> entry : byParent.entrySet()) {
      String parentId = entry.getKey().isEmpty() ? null : entry.getKey();
      ChainPlanNode parent = parentId != null ? nodesById.get(parentId) : null;
      List<ChainPlanNode> flowSiblings =
          entry.getValue().stream().filter(node -> isFlowSibling(node, parent)).toList();
      if (flowSiblings.size() <= 1) {
        continue;
      }
      Set<String> siblingIds =
          flowSiblings.stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet());
      boolean needsEdges =
          flowSiblings.stream()
              .anyMatch(sibling -> !hasSiblingExecutionEdge(sibling.nodeId(), siblingIds, edges));
      if (!needsEdges) {
        continue;
      }
      for (int i = 0; i < flowSiblings.size() - 1; i++) {
        String fromId = flowSiblings.get(i).nodeId();
        String toId = flowSiblings.get(i + 1).nodeId();
        if (hasSiblingExecutionEdge(fromId, siblingIds, edges)
            && hasSiblingExecutionEdge(toId, siblingIds, edges)) {
          continue;
        }
        if (hasDirectExecutionEdge(fromId, toId, edges)
            || hasDirectExecutionEdge(toId, fromId, edges)) {
          continue;
        }
        String edgeId = uniqueSiblingEdgeId(fromId, toId, existingEdgeIds);
        edges.add(new ChainPlanEdge(edgeId, fromId, toId, null));
        existingEdgeIds.add(edgeId);
      }
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), graph.nodes(), List.copyOf(edges));
  }

  public List<ChainPlanRepairIssue> diagnoseForRepair(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return List.of();
    }
    List<ChainPlanRepairIssue> issues = new ArrayList<>();
    Set<String> knownIds = graph.nodes().stream()
        .map(ChainPlanNode::nodeId)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());
    Map<String, ChainPlanNode> nodesById = indexNodes(graph.nodes());
    if (graph.edges() != null) {
      diagnoseBadEdgeRefs(graph.edges(), knownIds, issues);
      diagnoseMissingSiblingExecutionEdges(graph.nodes(), graph.edges(), nodesById, issues);
    }
    return List.copyOf(issues);
  }

  /**
   * Resolves catalog containment parent for skeleton materialization. Uses
   * explicit
   * {@link ChainPlanNode#parentNodeId()} when set; otherwise infers from incoming
   * flow edges
   * (for example try-catch-finally-2 → script implies parent try-2).
   */
  public static String effectiveParentNodeId(ChainPlanNode node, ChainPlanGraph graph) {
    if (node == null) {
      return null;
    }
    String explicit = blankToNull(node.parentNodeId());
    if (explicit != null) {
      return explicit;
    }
    if (graph == null || graph.edges() == null || graph.nodes() == null) {
      return null;
    }
    Map<String, ChainPlanNode> nodesById = indexNodes(graph.nodes());
    return inferParentFromIncomingFlowEdges(node, graph.edges(), nodesById);
  }

  private static Map<String, ChainPlanNode> indexNodes(List<ChainPlanNode> nodes) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    if (nodes == null) {
      return nodesById;
    }
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null) {
        nodesById.put(node.nodeId(), node);
      }
    }
    return nodesById;
  }

  private static String inferParentFromIncomingFlowEdges(
      ChainPlanNode node, List<ChainPlanEdge> edges, Map<String, ChainPlanNode> nodesById) {
    String nodeType = trim(node.type());
    if (nodeType == null || (!TRY_INNER_FLOW_TYPES.contains(nodeType) && !"script".equals(nodeType))) {
      return null;
    }
    for (ChainPlanEdge edge : edges) {
      if (!node.nodeId().equals(edge.toNodeId())) {
        continue;
      }
      ChainPlanNode from = nodesById.get(edge.fromNodeId());
      if (from == null) {
        continue;
      }
      String expected = expectedContainerParent(from, node, nodesById);
      if (expected != null) {
        return expected;
      }
    }
    return null;
  }

  private void checkSiblingFlowConnectivity(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      Map<String, ChainPlanNode> nodesById,
      List<String> errors) {
    Map<String, List<ChainPlanNode>> byParent = new LinkedHashMap<>();
    for (ChainPlanNode node : nodes) {
      String parentKey = blankToNull(node.parentNodeId());
      byParent.computeIfAbsent(parentKey == null ? "" : parentKey, ignored -> new ArrayList<>()).add(node);
    }
    for (Map.Entry<String, List<ChainPlanNode>> entry : byParent.entrySet()) {
      String parentId = entry.getKey().isEmpty() ? null : entry.getKey();
      ChainPlanNode parent = parentId != null ? nodesById.get(parentId) : null;
      List<ChainPlanNode> flowSiblings = entry.getValue().stream().filter(node -> isFlowSibling(node, parent)).toList();
      if (flowSiblings.size() <= 1) {
        continue;
      }
      Set<String> siblingIds = flowSiblings.stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet());
      for (ChainPlanNode sibling : flowSiblings) {
        if (!hasSiblingExecutionEdge(sibling.nodeId(), siblingIds, edges)) {
          errors.add(
              "node '"
                  + sibling.nodeId()
                  + "' ("
                  + trim(sibling.type())
                  + ") must have an execution edge to another sibling at the same containment"
                  + " level");
        }
      }
    }
  }

  private void diagnoseMissingSiblingExecutionEdges(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      Map<String, ChainPlanNode> nodesById,
      List<ChainPlanRepairIssue> issues) {
    Map<String, List<ChainPlanNode>> byParent = new LinkedHashMap<>();
    for (ChainPlanNode node : nodes) {
      String parentKey = blankToNull(node.parentNodeId());
      byParent.computeIfAbsent(parentKey == null ? "" : parentKey, ignored -> new ArrayList<>()).add(node);
    }
    for (Map.Entry<String, List<ChainPlanNode>> entry : byParent.entrySet()) {
      String parentId = entry.getKey().isEmpty() ? null : entry.getKey();
      ChainPlanNode parent = parentId != null ? nodesById.get(parentId) : null;
      List<ChainPlanNode> flowSiblings = entry.getValue().stream().filter(node -> isFlowSibling(node, parent)).toList();
      if (flowSiblings.size() <= 1) {
        continue;
      }
      List<String> siblingNodeIds = flowSiblings.stream().map(ChainPlanNode::nodeId).toList();
      Set<String> siblingIds = new HashSet<>(siblingNodeIds);
      List<ChainPlanEdge> scopeEdges = edges.stream()
          .filter(
              edge -> siblingIds.contains(edge.fromNodeId()) || siblingIds.contains(edge.toNodeId()))
          .toList();
      for (ChainPlanNode sibling : flowSiblings) {
        if (!hasSiblingExecutionEdge(sibling.nodeId(), siblingIds, edges)) {
          issues.add(
              new ChainPlanRepairIssue(
                  "MISSING_SIBLING_EXECUTION_EDGE",
                  "node '"
                      + sibling.nodeId()
                      + "' ("
                      + trim(sibling.type())
                      + ") must have an execution edge to another sibling at the same containment level",
                  sibling.nodeId(),
                  trim(sibling.type()),
                  parentId,
                  siblingNodeIds,
                  scopeEdges,
                  parentId,
                  null,
                  List.of()));
        }
      }
    }
  }

  private static boolean isFlowSibling(ChainPlanNode node, ChainPlanNode parent) {
    String type = trim(node.type());
    if (type == null) {
      return false;
    }
    if (parent == null) {
      return !isTriggerElementType(type) && !ChainElementFamilies.TRY_CATCH_WRAPPER.contains(type);
    }
    String parentType = trim(parent.type());
    if ("condition".equals(parentType) || "choice".equals(parentType)) {
      return !ChainElementFamilies.ROUTING_BRANCH_CHILDREN.contains(type);
    }
    if (ChainElementFamilies.TRY_CATCH_WRAPPER.contains(parentType)) {
      return !ChainElementFamilies.TRY_CATCH_SHELL.contains(type);
    }
    return true;
  }

  private static boolean hasSiblingExecutionEdge(
      String nodeId, Set<String> siblingIds, List<ChainPlanEdge> edges) {
    for (ChainPlanEdge edge : edges) {
      if (nodeId.equals(edge.fromNodeId()) && siblingIds.contains(edge.toNodeId())) {
        return true;
      }
      if (nodeId.equals(edge.toNodeId()) && siblingIds.contains(edge.fromNodeId())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDirectExecutionEdge(
      String fromNodeId, String toNodeId, List<ChainPlanEdge> edges) {
    for (ChainPlanEdge edge : edges) {
      if (fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId())) {
        return true;
      }
    }
    return false;
  }

  private static String uniqueSiblingEdgeId(
      String fromNodeId, String toNodeId, Set<String> existingEdgeIds) {
    String base = "auto-sibling-" + fromNodeId + "-" + toNodeId;
    if (!existingEdgeIds.contains(base)) {
      return base;
    }
    int suffix = 2;
    while (existingEdgeIds.contains(base + "-" + suffix)) {
      suffix++;
    }
    return base + "-" + suffix;
  }

  private void checkContainerFlowContainment(
      List<ChainPlanEdge> edges, Map<String, ChainPlanNode> nodesById, List<String> errors) {
    for (ChainPlanEdge edge : edges) {
      ChainPlanNode from = nodesById.get(edge.fromNodeId());
      ChainPlanNode to = nodesById.get(edge.toNodeId());
      if (from == null || to == null) {
        continue;
      }
      if (isStructuralBranchEntry(from, to)) {
        if (!from.nodeId().equals(blankToNull(to.parentNodeId()))) {
          errors.add(
              "node '"
                  + to.nodeId()
                  + "' ("
                  + trim(to.type())
                  + ") must have parentNodeId='"
                  + from.nodeId()
                  + "' (structural child of "
                  + trim(from.type())
                  + ")");
        }
        continue;
      }
      String expected = expectedContainerParent(from, to, nodesById);
      if (expected == null) {
        continue;
      }
      String actual = blankToNull(to.parentNodeId());
      if (!expected.equals(actual)) {
        errors.add(
            "node '"
                + to.nodeId()
                + "' ("
                + trim(to.type())
                + ") must have parentNodeId='"
                + expected
                + "' for catalog containment; edges alone do not place elements inside try-2");
      }
    }
  }

  private void checkTriggerFlow(
      List<ChainPlanNode> nodes, List<ChainPlanEdge> edges, List<String> errors) {
    Set<String> outgoing = edges == null
        ? Set.of()
        : edges.stream().map(ChainPlanEdge::fromNodeId).collect(Collectors.toSet());
    for (ChainPlanNode node : nodes) {
      if (isTriggerElementType(node.type()) && !outgoing.contains(node.nodeId())) {
        errors.add(
            "node '"
                + node.nodeId()
                + "' ("
                + trim(node.type())
                + ") must have an outgoing edge to the first executable node");
      }
    }
  }

  private void checkWrapperShellContainment(
      List<ChainPlanNode> nodes, Map<String, ChainPlanNode> nodesById, List<String> errors) {
    for (ChainPlanNode node : nodes) {
      if (!ChainElementFamilies.TRY_CATCH_SHELL.contains(trim(node.type()))) {
        continue;
      }
      String parentNodeId = blankToNull(node.parentNodeId());
      ChainPlanNode parent = parentNodeId != null ? nodesById.get(parentNodeId) : null;
      if (parent == null || !ChainElementFamilies.TRY_CATCH_WRAPPER.contains(trim(parent.type()))) {
        errors.add(
            "node '"
                + node.nodeId()
                + "' ("
                + trim(node.type())
                + ") must have parentNodeId of a try-catch-finally-2 node");
      }
    }
  }

  private static boolean isStructuralBranchEntry(ChainPlanNode from, ChainPlanNode to) {
    String parent = blankToNull(to.parentNodeId());
    return parent != null && parent.equals(from.nodeId());
  }

  private static String expectedContainerParent(
      ChainPlanNode from, ChainPlanNode to, Map<String, ChainPlanNode> nodesById) {
    String fromType = trim(from.type());
    String toType = trim(to.type());
    if (fromType == null || toType == null || ChainElementFamilies.TRY_CATCH_SHELL.contains(toType)) {
      return null;
    }
    if ("try-2".equals(fromType) && TRY_INNER_FLOW_TYPES.contains(toType)) {
      return from.nodeId();
    }
    if ("catch-2".equals(fromType) && "script".equals(toType)) {
      return from.nodeId();
    }
    if (ChainElementFamilies.TRY_CATCH_WRAPPER.contains(fromType) && TRY_INNER_FLOW_TYPES.contains(toType)) {
      return findTryChildId(nodesById, from.nodeId());
    }
    return null;
  }

  private static String findTryChildId(Map<String, ChainPlanNode> nodesById, String wrapperNodeId) {
    return nodesById.values().stream()
        .filter(
            node -> "try-2".equals(trim(node.type()))
                && wrapperNodeId.equals(blankToNull(node.parentNodeId())))
        .map(ChainPlanNode::nodeId)
        .findFirst()
        .orElse(null);
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private void checkPropertyKeys(List<ChainPlanNode> nodes, List<String> errors) {
    for (ChainPlanNode node : nodes) {
      String elementType = trim(node.type());
      if (elementType == null || elementType.isEmpty()) {
        continue;
      }
      Set<String> allowedKeys = schemaService.allowedPatchPropertyKeys(elementType);
      boolean knownSchema = schemaService.hasElementSchema(elementType);
      Set<String> presentKeys = new HashSet<>();
      if (node.properties() != null) {
        for (PlanProperty property : node.properties()) {
          if (property.key() == null || property.key().isBlank()) {
            continue;
          }
          String key = property.key().trim();
          presentKeys.add(key);
          if (MappingExecutionSite.isCompilerMetadataKey(key)) {
            continue;
          }
          if (knownSchema && !allowedKeys.contains(key)) {
            errors.add(unknownPropertyKeyMessage(node.nodeId(), elementType, key));
          }
        }
      }
    }
  }

  private String unknownPropertyKeyMessage(String nodeId, String elementType, String key) {
    String hint = hintForUnknownKey(elementType, key);
    StringBuilder message = new StringBuilder(
        "node '"
            + nodeId
            + "' ("
            + elementType
            + ") has unknown property key '"
            + key
            + "'. Use describeElementPatchSchema for allowed keys and the Runtime Context Package"
            + " for generation guidance.");
    if (hint != null) {
      message.append(' ').append(hint);
    }
    Set<String> allowedKeys = schemaService.allowedPatchPropertyKeys(elementType);
    if (!allowedKeys.isEmpty()) {
      String sample = allowedKeys.stream()
          .sorted()
          .limit(MAX_ALLOWED_KEYS_IN_MESSAGE)
          .collect(Collectors.joining(", "));
      message.append(" Allowed keys: ").append(sample).append('.');
    }
    return message.toString();
  }

  private static String hintForUnknownKey(String elementType, String key) {
    if ("http-trigger".equals(elementType) && "path".equals(key)) {
      return "http-trigger uses contextPath, not path.";
    }
    if ("http-trigger".equals(elementType) && "method".equals(key)) {
      return "http-trigger uses httpMethodRestrict, not method.";
    }
    if ("catch-2".equals(elementType) && "exceptionType".equals(key)) {
      return "catch-2 uses exception, not exceptionType.";
    }
    return null;
  }

  private Set<String> collectNodeIds(List<ChainPlanNode> nodes, List<String> errors) {
    Set<String> ids = new HashSet<>();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() == null || node.nodeId().isBlank()) {
        errors.add("a node has a blank or missing nodeId");
        continue;
      }
      if (!ids.add(node.nodeId())) {
        errors.add("duplicate nodeId: " + node.nodeId());
      }
    }
    return ids;
  }

  private void checkParentRefs(
      List<ChainPlanNode> nodes, Set<String> knownIds, List<String> errors) {
    for (ChainPlanNode node : nodes) {
      if (node.parentNodeId() != null && !knownIds.contains(node.parentNodeId())) {
        errors.add(
            "node '"
                + node.nodeId()
                + "' references unknown parentNodeId: '"
                + node.parentNodeId()
                + "'");
      }
    }
  }

  private void checkTriggerContainment(List<ChainPlanNode> nodes, List<String> errors) {
    for (ChainPlanNode node : nodes) {
      if (isTriggerElementType(node.type())
          && node.parentNodeId() != null
          && !node.parentNodeId().isBlank()) {
        errors.add(
            "trigger node '"
                + node.nodeId()
                + "' must not have parentNodeId; use edges for flow into containers");
      }
    }
  }

  private void checkContainmentCycles(List<ChainPlanNode> nodes, List<String> errors) {
    Map<String, String> parentOf = new HashMap<>();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null && node.parentNodeId() != null) {
        parentOf.put(node.nodeId(), node.parentNodeId());
      }
    }
    Set<String> reported = new HashSet<>();
    for (String nodeId : parentOf.keySet()) {
      if (!reported.contains(nodeId) && hasCycle(nodeId, parentOf, new HashSet<>())) {
        errors.add("containment cycle detected involving nodeId: '" + nodeId + "'");
        reported.add(nodeId);
      }
    }
  }

  private boolean hasCycle(String nodeId, Map<String, String> parentOf, Set<String> visited) {
    if (!visited.add(nodeId)) {
      return true;
    }
    String parent = parentOf.get(nodeId);
    return parent != null && hasCycle(parent, parentOf, visited);
  }

  private void checkEdgeRefs(
      List<ChainPlanEdge> edges, Set<String> knownIds, List<String> errors) {
    for (ChainPlanEdge edge : edges) {
      if (!knownIds.contains(edge.fromNodeId())) {
        errors.add("edge fromNodeId '" + edge.fromNodeId() + "' is not a known nodeId");
      }
      if (!knownIds.contains(edge.toNodeId())) {
        errors.add("edge toNodeId '" + edge.toNodeId() + "' is not a known nodeId");
      }
      if (edge.scopeNodeId() != null && !knownIds.contains(edge.scopeNodeId())) {
        errors.add("edge scopeNodeId '" + edge.scopeNodeId() + "' is not a known nodeId");
      }
    }
  }

  private void diagnoseBadEdgeRefs(
      List<ChainPlanEdge> edges, Set<String> knownIds, List<ChainPlanRepairIssue> issues) {
    for (ChainPlanEdge edge : edges) {
      List<String> invalidRefs = new ArrayList<>();
      if (!knownIds.contains(edge.fromNodeId())) {
        invalidRefs.add("fromNodeId:" + edge.fromNodeId());
      }
      if (!knownIds.contains(edge.toNodeId())) {
        invalidRefs.add("toNodeId:" + edge.toNodeId());
      }
      if (edge.scopeNodeId() != null && !knownIds.contains(edge.scopeNodeId())) {
        invalidRefs.add("scopeNodeId:" + edge.scopeNodeId());
      }
      if (!invalidRefs.isEmpty()) {
        issues.add(
            new ChainPlanRepairIssue(
                "BAD_EDGE_REFERENCE",
                "edge '"
                    + edge.edgeId()
                    + "' references unknown node ids: "
                    + String.join(", ", invalidRefs),
                null,
                null,
                null,
                List.of(),
                List.of(edge),
                null,
                edge.edgeId(),
                List.copyOf(invalidRefs)));
      }
    }
  }
}
