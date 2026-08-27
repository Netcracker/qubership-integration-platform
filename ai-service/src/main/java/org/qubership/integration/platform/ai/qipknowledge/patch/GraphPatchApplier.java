package org.qubership.integration.platform.ai.qipknowledge.patch;

import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies typed {@link GraphPatch} records to a {@link ChainPlanGraph} without mutating the input graph. */
@ApplicationScoped
public class GraphPatchApplier {

  private static final Set<String> CHAIN_PROPERTY_KEYS =
      Set.of("name", "description", "maskingEnabled", "maskedFieldNames");

  public GraphPatchApplyResult apply(ChainPlanGraph graph, GraphPatch patch) {
    List<String> shapeErrors = GraphPatchShapeValidator.validate(patch);
    if (!shapeErrors.isEmpty()) {
      return new GraphPatchApplyResult(
          graph,
          new ValidationResult(
              false,
              List.of(
                  blocker(
                      patch,
                      1,
                      GraphPatchShapeValidator.summarize(shapeErrors),
                      null,
                      List.of())),
              "Invalid graph patch shape: " + GraphPatchShapeValidator.summarize(shapeErrors)));
    }

    List<ChainPlanNode> nodes = copyNodes(graph);
    List<ChainPlanEdge> edges = copyEdges(graph);
    List<ValidationIssue> issues = new ArrayList<>();
    int issueCounter = 1;

    List<NodePatch> deferredNodeRemovals = new ArrayList<>();
    if (patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        if (nodePatch != null && nodePatch.operation() == GraphPatchOperation.REMOVE) {
          deferredNodeRemovals.add(nodePatch);
          continue;
        }
        issueCounter = applyNodePatch(nodes, edges, patch, nodePatch, issues, issueCounter);
      }
    }
    if (patch.edgePatches() != null) {
      for (EdgePatch edgePatch : patch.edgePatches()) {
        issueCounter = applyEdgePatch(nodes, edges, patch, edgePatch, issues, issueCounter);
      }
    }
    if (patch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : patch.propertyPatches()) {
        issueCounter = applyPropertyPatch(nodes, patch, propertyPatch, issues, issueCounter);
      }
    }

    ChainSection chain = copyChainSection(graph.chain());
    if (patch.chainPatches() != null) {
      for (ChainPatch chainPatch : patch.chainPatches()) {
        ChainPatchApplyStep step = applyChainPatch(chain, patch, chainPatch, issues, issueCounter);
        chain = step.chain();
        issueCounter = step.issueCounter();
      }
    }
    for (NodePatch nodePatch : deferredNodeRemovals) {
      issueCounter = applyNodePatch(nodes, edges, patch, nodePatch, issues, issueCounter);
    }

    if (hasBlockers(issues)) {
      return new GraphPatchApplyResult(
          graph,
          new ValidationResult(
              false,
              List.copyOf(issues),
              blockedSummary(issues)));
    }

    ChainPlanGraph patched =
        new ChainPlanGraph(
            graph.schemaVersion(), chain, List.copyOf(nodes), List.copyOf(edges));
    return new GraphPatchApplyResult(
        patched, new ValidationResult(true, List.of(), "Patch applied"));
  }

  private int applyNodePatch(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      NodePatch nodePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    return switch (nodePatch.operation()) {
      case ADD -> issueCounter + tryAddNode(nodes, patch, nodePatch, issues, issueCounter);
      case UPDATE -> issueCounter + tryUpdateNode(nodes, patch, nodePatch, issues, issueCounter);
      case REMOVE -> issueCounter + tryRemoveNode(nodes, edges, patch, nodePatch, issues, issueCounter);
    };
  }

  private int tryAddNode(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      NodePatch nodePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    ChainPlanNode node = nodePatch.node();
    if (node == null || isBlank(node.nodeId())) {
      issues.add(blocker(patch, issueCounter, "ADD node patch requires node.nodeId()", null, List.of()));
      return 1;
    }
    if (findNodeIndex(nodes, node.nodeId()) >= 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + node.nodeId() + "' already exists",
              "Use UPDATE for existing node '" + node.nodeId() + "'.",
              List.of(node.nodeId())));
      return 1;
    }
    nodes.add(node);
    return 0;
  }

  private int tryUpdateNode(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      NodePatch nodePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetNodeId = nodePatch.targetNodeId();
    ChainPlanNode node = nodePatch.node();
    if (isBlank(targetNodeId) || node == null) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE node patch requires targetNodeId and node",
              null,
              targetNodeId != null ? List.of(targetNodeId) : List.of()));
      return 1;
    }
    if (findNodeIndex(nodes, targetNodeId) < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not exist",
              "Use ADD for new node '" + targetNodeId + "'.",
              List.of(targetNodeId)));
      return 1;
    }
    if (!Objects.equals(node.nodeId(), targetNodeId)) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE node patch nodeId '" + node.nodeId() + "' does not match targetNodeId '"
                  + targetNodeId
                  + "'",
              "Set node.nodeId() to '" + targetNodeId + "'.",
              List.of(targetNodeId)));
      return 1;
    }
    ChainPlanNode existing = nodes.get(findNodeIndex(nodes, targetNodeId));
    nodes.set(findNodeIndex(nodes, targetNodeId), mergeNodeUpdate(existing, node));
    return 0;
  }

  private static ChainPlanNode mergeNodeUpdate(ChainPlanNode existing, ChainPlanNode incoming) {
    List<PlanProperty> mergedProperties = mergeNodeProperties(existing, incoming);
    return new ChainPlanNode(
        incoming.nodeId(),
        incoming.type() != null ? incoming.type() : existing.type(),
        incoming.label() != null ? incoming.label() : existing.label(),
        incoming.parentNodeId() != null ? incoming.parentNodeId() : existing.parentNodeId(),
        incoming.order() != null ? incoming.order() : existing.order(),
        mergedProperties);
  }

  private static List<PlanProperty> mergeNodeProperties(ChainPlanNode existing, ChainPlanNode incoming) {
    if (incoming.properties() == null || incoming.properties().isEmpty()) {
      return existing.properties() == null ? List.of() : List.copyOf(existing.properties());
    }
    Map<String, PlanProperty> byKey = new LinkedHashMap<>();
    if (existing.properties() != null) {
      for (PlanProperty property : existing.properties()) {
        if (property.key() != null) {
          byKey.put(property.key(), property);
        }
      }
    }
    for (PlanProperty property : incoming.properties()) {
      if (property.key() != null) {
        byKey.put(property.key(), property);
      }
    }
    return List.copyOf(byKey.values());
  }

  private int tryRemoveNode(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      NodePatch nodePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetNodeId = nodePatch.targetNodeId();
    if (isBlank(targetNodeId)) {
      issues.add(
          blocker(patch, issueCounter, "REMOVE node patch requires targetNodeId", null, List.of()));
      return 1;
    }
    if (findNodeIndex(nodes, targetNodeId) < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not exist",
              null,
              List.of(targetNodeId)));
      return 1;
    }
    for (ChainPlanNode node : nodes) {
      if (Objects.equals(node.parentNodeId(), targetNodeId)) {
        issues.add(
            blocker(
                patch,
                issueCounter,
                "Cannot remove node '" + targetNodeId + "' because child node '" + node.nodeId()
                    + "' references it as parentNodeId",
                "Remove or reparent child nodes first.",
                List.of(targetNodeId, node.nodeId())));
        return 1;
      }
    }
    for (ChainPlanEdge edge : edges) {
      if (referencesNode(edge, targetNodeId)) {
        issues.add(
            blocker(
                patch,
                issueCounter,
                "Cannot remove node '" + targetNodeId + "' because edge '" + edge.edgeId()
                    + "' still references it",
                "Remove referencing edges first.",
                List.of(targetNodeId)));
        return 1;
      }
    }
    nodes.removeIf(node -> Objects.equals(node.nodeId(), targetNodeId));
    return 0;
  }

  private int applyEdgePatch(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      EdgePatch edgePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    return switch (edgePatch.operation()) {
      case ADD -> issueCounter + tryAddEdge(nodes, edges, patch, edgePatch, issues, issueCounter);
      case UPDATE -> issueCounter + tryUpdateEdge(nodes, edges, patch, edgePatch, issues, issueCounter);
      case REMOVE -> issueCounter + tryRemoveEdge(edges, patch, edgePatch, issues, issueCounter);
    };
  }

  private int tryAddEdge(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      EdgePatch edgePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    ChainPlanEdge edge = edgePatch.edge();
    if (edge == null || isBlank(edge.edgeId())) {
      issues.add(blocker(patch, issueCounter, "ADD edge patch requires edge.edgeId()", null, List.of()));
      return 1;
    }
    if (findEdgeIndex(edges, edge.edgeId()) >= 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge '" + edge.edgeId() + "' already exists",
              "Use UPDATE for existing edge '" + edge.edgeId() + "'.",
              List.of()));
      return 1;
    }
    int added = validateEdgeNodeRefs(nodes, patch, edge, issues, issueCounter);
    if (added > 0) {
      return added;
    }
    edges.add(edge);
    return 0;
  }

  private int tryUpdateEdge(
      List<ChainPlanNode> nodes,
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      EdgePatch edgePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetEdgeId = edgePatch.targetEdgeId();
    ChainPlanEdge edge = edgePatch.edge();
    if (isBlank(targetEdgeId) || edge == null) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE edge patch requires targetEdgeId and edge",
              null,
              List.of()));
      return 1;
    }
    if (findEdgeIndex(edges, targetEdgeId) < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge '" + targetEdgeId + "' does not exist",
              "Use ADD for new edge '" + targetEdgeId + "'.",
              List.of()));
      return 1;
    }
    if (!Objects.equals(edge.edgeId(), targetEdgeId)) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE edge patch edgeId '" + edge.edgeId() + "' does not match targetEdgeId '"
                  + targetEdgeId
                  + "'",
              "Set edge.edgeId() to '" + targetEdgeId + "'.",
              List.of()));
      return 1;
    }
    int added = validateEdgeNodeRefs(nodes, patch, edge, issues, issueCounter);
    if (added > 0) {
      return added;
    }
    edges.set(findEdgeIndex(edges, targetEdgeId), edge);
    return 0;
  }

  private int tryRemoveEdge(
      List<ChainPlanEdge> edges,
      GraphPatch patch,
      EdgePatch edgePatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetEdgeId = edgePatch.targetEdgeId();
    if (isBlank(targetEdgeId)) {
      issues.add(
          blocker(patch, issueCounter, "REMOVE edge patch requires targetEdgeId", null, List.of()));
      return 1;
    }
    if (findEdgeIndex(edges, targetEdgeId) < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge '" + targetEdgeId + "' does not exist",
              null,
              List.of()));
      return 1;
    }
    edges.removeIf(edge -> Objects.equals(edge.edgeId(), targetEdgeId));
    return 0;
  }

  private int validateEdgeNodeRefs(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      ChainPlanEdge edge,
      List<ValidationIssue> issues,
      int issueCounter) {
    Set<String> nodeIds = nodeIdSet(nodes);
    if (!nodeIds.contains(edge.fromNodeId())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge fromNodeId '" + edge.fromNodeId() + "' is not a known nodeId",
              "Add node '" + edge.fromNodeId() + "' before adding the edge.",
              List.of(edge.fromNodeId())));
      return 1;
    }
    if (!nodeIds.contains(edge.toNodeId())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge toNodeId '" + edge.toNodeId() + "' is not a known nodeId",
              "Add node '" + edge.toNodeId() + "' before adding the edge.",
              List.of(edge.toNodeId())));
      return 1;
    }
    if (edge.scopeNodeId() != null && !nodeIds.contains(edge.scopeNodeId())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Edge scopeNodeId '" + edge.scopeNodeId() + "' is not a known nodeId",
              "Add node '" + edge.scopeNodeId() + "' before adding the edge.",
              List.of(edge.scopeNodeId())));
      return 1;
    }
    return 0;
  }

  private int applyPropertyPatch(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      PropertyPatch propertyPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    return switch (propertyPatch.operation()) {
      case ADD -> issueCounter + tryAddProperty(nodes, patch, propertyPatch, issues, issueCounter);
      case UPDATE -> issueCounter + tryUpdateProperty(nodes, patch, propertyPatch, issues, issueCounter);
      case REMOVE -> issueCounter + tryRemoveProperty(nodes, patch, propertyPatch, issues, issueCounter);
    };
  }

  private int tryAddProperty(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      PropertyPatch propertyPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetNodeId = propertyPatch.targetNodeId();
    PlanProperty property = propertyPatch.property();
    if (isBlank(targetNodeId) || property == null || isBlank(property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "ADD property patch requires targetNodeId and property.key()",
              null,
              targetNodeId != null ? List.of(targetNodeId) : List.of()));
      return 1;
    }
    int nodeIndex = findNodeIndex(nodes, targetNodeId);
    if (nodeIndex < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not exist",
              "Add node '" + targetNodeId + "' before adding a property.",
              List.of(targetNodeId)));
      return 1;
    }
    ChainPlanNode node = nodes.get(nodeIndex);
    if (hasPropertyKey(node, property.key())) {
      nodes.set(nodeIndex, replaceProperty(node, property));
      return 0;
    }
    nodes.set(nodeIndex, appendProperty(node, property));
    return 0;
  }

  private int tryUpdateProperty(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      PropertyPatch propertyPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetNodeId = propertyPatch.targetNodeId();
    PlanProperty property = propertyPatch.property();
    if (isBlank(targetNodeId) || property == null || isBlank(property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE property patch requires targetNodeId and property.key()",
              null,
              targetNodeId != null ? List.of(targetNodeId) : List.of()));
      return 1;
    }
    int nodeIndex = findNodeIndex(nodes, targetNodeId);
    if (nodeIndex < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not exist",
              null,
              List.of(targetNodeId)));
      return 1;
    }
    ChainPlanNode node = nodes.get(nodeIndex);
    if (!hasPropertyKey(node, property.key())) {
      nodes.set(nodeIndex, appendProperty(node, property));
      return 0;
    }
    nodes.set(nodeIndex, replaceProperty(node, property));
    return 0;
  }

  private int tryRemoveProperty(
      List<ChainPlanNode> nodes,
      GraphPatch patch,
      PropertyPatch propertyPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    String targetNodeId = propertyPatch.targetNodeId();
    PlanProperty property = propertyPatch.property();
    if (isBlank(targetNodeId) || property == null || isBlank(property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "REMOVE property patch requires targetNodeId and property.key()",
              null,
              targetNodeId != null ? List.of(targetNodeId) : List.of()));
      return 1;
    }
    int nodeIndex = findNodeIndex(nodes, targetNodeId);
    if (nodeIndex < 0) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not exist",
              null,
              List.of(targetNodeId)));
      return 1;
    }
    ChainPlanNode node = nodes.get(nodeIndex);
    if (!hasPropertyKey(node, property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Node '" + targetNodeId + "' does not have property key '" + property.key() + "'",
              null,
              List.of(targetNodeId)));
      return 1;
    }
    nodes.set(nodeIndex, removeProperty(node, property.key()));
    return 0;
  }

  private record ChainPatchApplyStep(ChainSection chain, int issueCounter) {}

  private ChainPatchApplyStep applyChainPatch(
      ChainSection chain,
      GraphPatch patch,
      ChainPatch chainPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    ChainPropertyStep step =
        switch (chainPatch.operation()) {
          case ADD -> tryAddChainProperty(chain, patch, chainPatch, issues, issueCounter);
          case UPDATE -> tryUpdateChainProperty(chain, patch, chainPatch, issues, issueCounter);
          case REMOVE -> tryRemoveChainProperty(chain, patch, chainPatch, issues, issueCounter);
        };
    return new ChainPatchApplyStep(step.chain(), issueCounter + step.issuesAdded());
  }

  private record ChainPropertyStep(ChainSection chain, int issuesAdded) {}

  private ChainPropertyStep tryAddChainProperty(
      ChainSection chain,
      GraphPatch patch,
      ChainPatch chainPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    PlanProperty property = chainPatch.property();
    if (property == null || isBlank(property.key()) || isBlank(property.value())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "ADD chain patch requires property.key and property.value",
              null,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    if (!"maskedFieldNames".equals(property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "ADD chain patch supports maskedFieldNames only",
              "Use UPDATE for maskingEnabled.",
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    String fieldName = property.value().trim();
    List<String> names = maskedFieldNamesCopy(chain);
    if (!names.contains(fieldName)) {
      names.add(fieldName);
    }
    return new ChainPropertyStep(withMaskedFieldNames(chain, names), 0);
  }

  private ChainPropertyStep tryUpdateChainProperty(
      ChainSection chain,
      GraphPatch patch,
      ChainPatch chainPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    PlanProperty property = chainPatch.property();
    if (property == null || isBlank(property.key()) || property.value() == null) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "UPDATE chain patch requires property.key and property.value",
              null,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    String key = property.key().trim();
    if (!CHAIN_PROPERTY_KEYS.contains(key)) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "Unknown chain property key '" + key + "'",
              "Allowed keys: " + CHAIN_PROPERTY_KEYS,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    if ("name".equals(key)) {
      String name = property.value().trim();
      if (isBlank(name)) {
        issues.add(
            blocker(
                patch,
                issueCounter,
                "name UPDATE requires a non-blank value",
                null,
                List.of()));
        return new ChainPropertyStep(chain, 1);
      }
      return new ChainPropertyStep(
          copySection(
              chain, name, chain.description(), chain.maskingEnabled(), chain.maskedFieldNames()),
          0);
    }
    if ("description".equals(key)) {
      return new ChainPropertyStep(
          copySection(
              chain,
              chain.name(),
              property.value().trim(),
              chain.maskingEnabled(),
              chain.maskedFieldNames()),
          0);
    }
    if ("maskingEnabled".equals(key)) {
      Boolean enabled = parseBoolean(property.value());
      if (enabled == null) {
        issues.add(
            blocker(
                patch,
                issueCounter,
                "maskingEnabled requires 'true' or 'false'",
                null,
                List.of()));
        return new ChainPropertyStep(chain, 1);
      }
      return new ChainPropertyStep(
          copySection(chain, chain.name(), chain.description(), enabled, chain.maskedFieldNames()),
          0);
    }
    List<String> parsed = parseMaskedFieldNamesList(property.value());
    if (parsed == null) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "maskedFieldNames UPDATE requires a JSON array string",
              "Example: [\"customerEmail\"]",
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    return new ChainPropertyStep(withMaskedFieldNames(chain, parsed), 0);
  }

  private ChainPropertyStep tryRemoveChainProperty(
      ChainSection chain,
      GraphPatch patch,
      ChainPatch chainPatch,
      List<ValidationIssue> issues,
      int issueCounter) {
    PlanProperty property = chainPatch.property();
    if (property == null || isBlank(property.key()) || isBlank(property.value())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "REMOVE chain patch requires property.key and property.value",
              null,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    if (!"maskedFieldNames".equals(property.key())) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "REMOVE chain patch supports maskedFieldNames only",
              null,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    String fieldName = property.value().trim();
    List<String> names = maskedFieldNamesCopy(chain);
    if (!names.remove(fieldName)) {
      issues.add(
          blocker(
              patch,
              issueCounter,
              "maskedFieldNames does not contain '" + fieldName + "'",
              null,
              List.of()));
      return new ChainPropertyStep(chain, 1);
    }
    return new ChainPropertyStep(withMaskedFieldNames(chain, names), 0);
  }

  private static ChainSection copyChainSection(ChainSection chain) {
    if (chain == null) {
      return new ChainSection("chain", null);
    }
    return copySection(
        chain,
        chain.name(),
        chain.description(),
        chain.maskingEnabled(),
        chain.maskedFieldNames() != null ? List.copyOf(chain.maskedFieldNames()) : null);
  }

  private static List<String> maskedFieldNamesCopy(ChainSection chain) {
    List<String> names = new ArrayList<>();
    if (chain.maskedFieldNames() != null) {
      names.addAll(chain.maskedFieldNames());
    }
    return names;
  }

  private static ChainSection withMaskedFieldNames(ChainSection chain, List<String> names) {
    List<String> copy = names == null || names.isEmpty() ? null : List.copyOf(names);
    return copySection(chain, chain.name(), chain.description(), chain.maskingEnabled(), copy);
  }

  private static ChainSection copySection(
      ChainSection chain,
      String name,
      String description,
      Boolean maskingEnabled,
      List<String> maskedFieldNames) {
    return new ChainSection(
        name,
        description,
        maskingEnabled,
        maskedFieldNames,
        chain.semanticRevisionId(),
        chain.compilerContractVersion());
  }

  private static Boolean parseBoolean(String rawValue) {
    if ("true".equalsIgnoreCase(rawValue.trim())) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(rawValue.trim())) {
      return Boolean.FALSE;
    }
    return null;
  }

  private static List<String> parseMaskedFieldNamesList(String rawValue) {
    String trimmed = rawValue.trim();
    if (!trimmed.startsWith("[")) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(trimmed);
      if (!node.isArray()) {
        return null;
      }
      List<String> names = new ArrayList<>();
      for (com.fasterxml.jackson.databind.JsonNode item : node) {
        if (!item.isTextual()) {
          return null;
        }
        names.add(item.asText());
      }
      return names;
    } catch (Exception e) {
      return null;
    }
  }

  private ValidationIssue blocker(
      GraphPatch patch,
      int issueCounter,
      String message,
      String suggestedFix,
      List<String> affectedNodeIds) {
    List<QipKnowledgeCitation> ruleRefs =
        patch.usedKnowledgeRefs() != null ? patch.usedKnowledgeRefs() : List.of();
    return new ValidationIssue(
        "patch-conflict-" + issueCounter,
        ValidationSeverity.BLOCKER,
        message,
        patch.ownerCapabilityId(),
        affectedNodeIds,
        ruleRefs,
        suggestedFix);
  }

  private static List<ChainPlanNode> copyNodes(ChainPlanGraph graph) {
    return new ArrayList<>(graph.nodes() != null ? graph.nodes() : List.of());
  }

  private static List<ChainPlanEdge> copyEdges(ChainPlanGraph graph) {
    return new ArrayList<>(graph.edges() != null ? graph.edges() : List.of());
  }

  private static int findNodeIndex(List<ChainPlanNode> nodes, String nodeId) {
    for (int i = 0; i < nodes.size(); i++) {
      if (Objects.equals(nodes.get(i).nodeId(), nodeId)) {
        return i;
      }
    }
    return -1;
  }

  private static int findEdgeIndex(List<ChainPlanEdge> edges, String edgeId) {
    for (int i = 0; i < edges.size(); i++) {
      if (Objects.equals(edges.get(i).edgeId(), edgeId)) {
        return i;
      }
    }
    return -1;
  }

  private static Set<String> nodeIdSet(List<ChainPlanNode> nodes) {
    Set<String> ids = new HashSet<>();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null) {
        ids.add(node.nodeId());
      }
    }
    return ids;
  }

  private static boolean referencesNode(ChainPlanEdge edge, String nodeId) {
    return Objects.equals(edge.fromNodeId(), nodeId)
        || Objects.equals(edge.toNodeId(), nodeId)
        || Objects.equals(edge.scopeNodeId(), nodeId);
  }

  private static boolean hasPropertyKey(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    return node.properties().stream().anyMatch(property -> Objects.equals(property.key(), key));
  }

  private static ChainPlanNode appendProperty(ChainPlanNode node, PlanProperty property) {
    List<PlanProperty> properties = new ArrayList<>();
    if (node.properties() != null) {
      properties.addAll(node.properties());
    }
    properties.add(property);
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  private static ChainPlanNode replaceProperty(ChainPlanNode node, PlanProperty property) {
    List<PlanProperty> properties = new ArrayList<>();
    if (node.properties() != null) {
      for (PlanProperty existing : node.properties()) {
        properties.add(
            Objects.equals(existing.key(), property.key()) ? property : existing);
      }
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  private static ChainPlanNode removeProperty(ChainPlanNode node, String key) {
    List<PlanProperty> properties = new ArrayList<>();
    if (node.properties() != null) {
      for (PlanProperty existing : node.properties()) {
        if (!Objects.equals(existing.key(), key)) {
          properties.add(existing);
        }
      }
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  private static boolean hasBlockers(List<ValidationIssue> issues) {
    return issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
  }

  private static int blockerCount(List<ValidationIssue> issues) {
    return (int)
        issues.stream().filter(issue -> issue.severity() == ValidationSeverity.BLOCKER).count();
  }

  private static String blockedSummary(List<ValidationIssue> issues) {
    StringBuilder summary =
        new StringBuilder("Patch blocked by ")
            .append(blockerCount(issues))
            .append(" conflict(s)");
    issues.stream()
        .filter(issue -> issue.severity() == ValidationSeverity.BLOCKER)
        .map(ValidationIssue::message)
        .filter(message -> message != null && !message.isBlank())
        .limit(3)
        .forEach(message -> summary.append(": ").append(message));
    return summary.toString();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
