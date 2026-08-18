package org.qubership.integration.platform.ai.qipknowledge.patch;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

/** Enforces pinned ownership policy boundaries for graph patch operations. */
@ApplicationScoped
public class GraphPatchOwnershipValidator {

  /** {@link ValidationIssue#issueId()} this validator raises -- distinct from a structural block a
   * later stage (e.g. {@link GraphPatchApplier}) may also refuse a patch for. */
  public static final String OWNERSHIP_VIOLATION_ISSUE_ID = "ownership-violation";

  public ValidationResult validate(GraphPatchExecutionContext context, GraphPatch patch) {
    if (context == null) {
      return invalid("Graph patch execution context is required");
    }
    if (patch == null) {
      return invalid("Graph patch is required");
    }
    GraphPatchOwnershipPolicy ownership = context.ownership();
    Map<String, ChainPlanNode> existingNodesById = indexNodes(context.inputGraph());
    Map<String, String> preExistingNodeTypeByNodeId = indexNodeTypes(context.inputGraph());
    Map<String, String> nodeTypeByNodeId = new LinkedHashMap<>(preExistingNodeTypeByNodeId);
    Set<String> samePatchAddedNodeIds = indexAddedNodes(nodeTypeByNodeId, patch);

    List<String> findings = new ArrayList<>();
    validateNodeOwnership(
        ownership,
        patch,
        nodeTypeByNodeId,
        samePatchAddedNodeIds,
        existingNodesById,
        context.editTargetNodeIds(),
        findings);
    validateEdgeOwnership(
        ownership,
        patch,
        indexEdges(context.inputGraph()),
        preExistingNodeTypeByNodeId,
        nodeTypeByNodeId,
        samePatchAddedNodeIds,
        findings);
    validatePropertyOwnership(ownership, patch, nodeTypeByNodeId, findings);
    validateChainOwnership(ownership, patch, findings);

    if (findings.isEmpty()) {
      return new ValidationResult(true, List.of(), "Ownership validation passed");
    }
    return invalid(String.join("; ", findings));
  }

  private static void validateNodeOwnership(
      GraphPatchOwnershipPolicy ownership,
      GraphPatch patch,
      Map<String, String> nodeTypeByNodeId,
      Set<String> samePatchAddedNodeIds,
      Map<String, ChainPlanNode> existingNodesById,
      List<String> editTargetNodeIds,
      List<String> findings) {
    if (patch.nodePatches() == null) {
      return;
    }
    for (NodePatch nodePatch : patch.nodePatches()) {
      if (nodePatch == null) {
        continue;
      }
      if (nodePatch.operation() == GraphPatchOperation.REMOVE) {
        validateNodeRemoval(ownership, nodePatch, existingNodesById, findings);
        continue;
      }
      if (nodePatch.operation() == GraphPatchOperation.ADD) {
        ChainPlanNode node = nodePatch.node();
        if (!ownership.mayAddNodes()) {
          findings.add("ownership violation: node ADD is not allowed");
          continue;
        }
        if (node != null
            && node.type() != null
            && !ownership.nodeTypes().contains(node.type())) {
          findings.add("ownership violation: node type '" + node.type() + "' is not owned");
        }
        if (node != null && node.type() != null) {
          validateEmbeddedPropertyKeys(ownership, node.type(), node.properties(), findings);
        }
        continue;
      }
      if (nodePatch.operation() != GraphPatchOperation.UPDATE) {
        continue;
      }
      String targetNodeId = nodePatch.targetNodeId();
      ChainPlanNode existing = existingNodesById.get(targetNodeId);
      ChainPlanNode incoming = nodePatch.node();
      String targetType = existing != null ? existing.type() : nodeTypeByNodeId.get(targetNodeId);
      if (existing == null || incoming == null || targetType == null) {
        continue;
      }
      if (incoming.type() != null && !Objects.equals(incoming.type(), existing.type())) {
        findings.add(
            "ownership violation: node '"
                + targetNodeId
                + "' type mutation from '"
                + existing.type()
                + "' to '"
                + incoming.type()
                + "' is not allowed");
      }
      if (rejectDisallowedReparent(
          existing, incoming, targetNodeId, editTargetNodeIds, samePatchAddedNodeIds, findings)) {
        continue;
      }
      if (ownership.nodeTypes().contains(targetType)) {
        validateEmbeddedPropertyKeys(ownership, targetType, incoming.properties(), findings);
        continue;
      }
      validateForeignNodeUpdate(
          ownership, targetNodeId, existing, incoming, samePatchAddedNodeIds, nodeTypeByNodeId, findings);
    }
  }

  private static boolean rejectDisallowedReparent(
      ChainPlanNode existing,
      ChainPlanNode incoming,
      String targetNodeId,
      List<String> editTargetNodeIds,
      Set<String> samePatchAddedNodeIds,
      List<String> findings) {
    boolean parentChanging =
        incoming.parentNodeId() != null
            && !Objects.equals(incoming.parentNodeId(), existing.parentNodeId());
    if (!parentChanging) {
      return false;
    }
    if (ChainPlanGraphValidator.isTriggerElementType(existing.type())) {
      findings.add(
          "cannot reparent trigger node '"
              + targetNodeId
              + "'; keep the trigger at root and wrap the named edit target");
      return true;
    }
    if (editTargetNodeIds != null
        && !editTargetNodeIds.isEmpty()
        && !editTargetNodeIds.contains(targetNodeId)
        && !samePatchAddedNodeIds.contains(targetNodeId)) {
      findings.add(
          "cannot reparent node '"
              + targetNodeId
              + "'; UPDATE parentNodeId only for named edit targets");
      return true;
    }
    return false;
  }

  /**
   * A removal is permitted only when the policy allows removing nodes at all and the element's own
   * type is owned -- the same bar an ADD of that type has to clear. An unknown target is a finding
   * rather than a silent pass: refusing to delete something we cannot see is the safe reading.
   */
  private static void validateNodeRemoval(
      GraphPatchOwnershipPolicy ownership,
      NodePatch nodePatch,
      Map<String, ChainPlanNode> existingNodesById,
      List<String> findings) {
    if (!ownership.mayRemoveNodes()) {
      findings.add("ownership violation: node REMOVE is not allowed");
      return;
    }
    String targetNodeId = nodePatch.targetNodeId();
    ChainPlanNode existing = targetNodeId == null ? null : existingNodesById.get(targetNodeId);
    if (existing == null) {
      findings.add("ownership violation: node REMOVE names unknown node '" + targetNodeId + "'");
      return;
    }
    if (existing.type() != null && !ownership.nodeTypes().contains(existing.type())) {
      findings.add(
          "ownership violation: node type '" + existing.type() + "' is not owned, so it may not be removed");
    }
  }

  /**
   * Mirrors {@link #validateNodeRemoval} for edges, reusing the ADD path's endpoint rule: one end
   * of the edge must be something this policy owns.
   */
  private static void validateEdgeRemoval(
      GraphPatchOwnershipPolicy ownership,
      EdgePatch edgePatch,
      Map<String, ChainPlanEdge> existingEdgesById,
      Map<String, String> preExistingNodeTypeByNodeId,
      Map<String, String> nodeTypeByNodeId,
      Set<String> samePatchAddedNodeIds,
      List<String> findings) {
    if (!ownership.mayRemoveEdges()) {
      findings.add("ownership violation: edge REMOVE is not allowed");
      return;
    }
    String targetEdgeId = edgePatch.targetEdgeId();
    ChainPlanEdge existing = targetEdgeId == null ? null : existingEdgesById.get(targetEdgeId);
    if (existing == null) {
      findings.add("ownership violation: edge REMOVE names unknown edge '" + targetEdgeId + "'");
      return;
    }
    if (!isAllowedEdgeEndpoint(
            existing.fromNodeId(), ownership, preExistingNodeTypeByNodeId, nodeTypeByNodeId, samePatchAddedNodeIds)
        && !isAllowedEdgeEndpoint(
            existing.toNodeId(), ownership, preExistingNodeTypeByNodeId, nodeTypeByNodeId, samePatchAddedNodeIds)) {
      findings.add(
          "ownership violation: edge endpoint ownership is required to remove the edge between '"
              + existing.fromNodeId()
              + "' and '"
              + existing.toNodeId()
              + "'");
    }
  }

  private static void validateForeignNodeUpdate(
      GraphPatchOwnershipPolicy ownership,
      String targetNodeId,
      ChainPlanNode existing,
      ChainPlanNode incoming,
      Set<String> samePatchAddedNodeIds,
      Map<String, String> nodeTypeByNodeId,
      List<String> findings) {
    if (incoming.type() != null && !Objects.equals(incoming.type(), existing.type())) {
      return;
    }
    if (incoming.label() != null && !Objects.equals(incoming.label(), existing.label())) {
      findings.add(
          "ownership violation: foreign node '"
              + targetNodeId
              + "' label mutation is not allowed");
      return;
    }
    if (propertiesMutated(existing, incoming)) {
      findings.add(
          "ownership violation: foreign node '"
              + targetNodeId
              + "' properties mutation is not allowed");
      return;
    }
    boolean parentChanging =
        incoming.parentNodeId() != null
            && !Objects.equals(incoming.parentNodeId(), existing.parentNodeId());
    boolean orderChanging =
        incoming.order() != null && !Objects.equals(incoming.order(), existing.order());
    if (!parentChanging) {
      if (orderChanging) {
        findings.add(
            "ownership violation: foreign node '"
                + targetNodeId
                + "' order may change only together with a permitted reparent");
      } else {
        findings.add(
            "ownership violation: foreign node '"
                + targetNodeId
                + "' UPDATE is only allowed as reparent under an owned node added in the same patch");
      }
      return;
    }
    if (!ownership.mayAddNodes()) {
      findings.add(
          "ownership violation: foreign node '"
              + targetNodeId
              + "' reparent requires mayAddNodes");
      return;
    }
    String newParentId = incoming.parentNodeId();
    if (!samePatchAddedNodeIds.contains(newParentId)) {
      findings.add(
          "ownership violation: foreign node '"
              + targetNodeId
              + "' may only be reparented under an owned node added in the same patch");
      return;
    }
    String newParentType = nodeTypeByNodeId.get(newParentId);
    if (newParentType == null || !ownership.nodeTypes().contains(newParentType)) {
      findings.add(
          "ownership violation: foreign node '"
              + targetNodeId
              + "' new parent '"
              + newParentId
              + "' type is not owned");
    }
  }

  private static void validateEmbeddedPropertyKeys(
      GraphPatchOwnershipPolicy ownership,
      String nodeType,
      List<PlanProperty> properties,
      List<String> findings) {
    if (properties == null || properties.isEmpty()) {
      return;
    }
    Set<String> allowedKeys = ownership.properties().get(nodeType);
    for (PlanProperty property : properties) {
      if (property == null || property.key() == null) {
        continue;
      }
      if (allowedKeys == null || !allowedKeys.contains(property.key())) {
        findings.add(
            "ownership violation: property key '"
                + property.key()
                + "' on node type '"
                + nodeType
                + "' is not owned");
      }
    }
  }

  private static boolean propertiesMutated(ChainPlanNode existing, ChainPlanNode incoming) {
    if (incoming.properties() == null || incoming.properties().isEmpty()) {
      return false;
    }
    Map<String, Object> existingByKey = propertyValuesByKey(existing.properties());
    Map<String, Object> incomingByKey = propertyValuesByKey(incoming.properties());
    return !existingByKey.equals(incomingByKey);
  }

  private static Map<String, Object> propertyValuesByKey(List<PlanProperty> properties) {
    Map<String, Object> byKey = new LinkedHashMap<>();
    if (properties == null) {
      return byKey;
    }
    for (PlanProperty property : properties) {
      if (property == null || property.key() == null) {
        continue;
      }
      byKey.put(property.key(), property.value());
    }
    return byKey;
  }

  private static void validateEdgeOwnership(
      GraphPatchOwnershipPolicy ownership,
      GraphPatch patch,
      Map<String, ChainPlanEdge> existingEdgesById,
      Map<String, String> preExistingNodeTypeByNodeId,
      Map<String, String> nodeTypeByNodeId,
      Set<String> samePatchAddedNodeIds,
      List<String> findings) {
    if (patch.edgePatches() == null) {
      return;
    }
    for (EdgePatch edgePatch : patch.edgePatches()) {
      if (edgePatch == null) {
        continue;
      }
      if (edgePatch.operation() == GraphPatchOperation.REMOVE) {
        validateEdgeRemoval(
            ownership,
            edgePatch,
            existingEdgesById,
            preExistingNodeTypeByNodeId,
            nodeTypeByNodeId,
            samePatchAddedNodeIds,
            findings);
        continue;
      }
      if (edgePatch.operation() != GraphPatchOperation.ADD
          && edgePatch.operation() != GraphPatchOperation.UPDATE) {
        continue;
      }
      if (!ownership.mayAddEdges()) {
        findings.add("ownership violation: edge ADD is not allowed");
        continue;
      }
      if (edgePatch.edge() == null) {
        continue;
      }
      String fromNodeId = edgePatch.edge().fromNodeId();
      String toNodeId = edgePatch.edge().toNodeId();
      if (!isAllowedEdgeEndpoint(
              fromNodeId, ownership, preExistingNodeTypeByNodeId, nodeTypeByNodeId, samePatchAddedNodeIds)
          && !isAllowedEdgeEndpoint(
              toNodeId, ownership, preExistingNodeTypeByNodeId, nodeTypeByNodeId, samePatchAddedNodeIds)) {
        findings.add(
            "ownership violation: edge endpoint ownership is required for fromNodeId '"
                + fromNodeId
                + "' and toNodeId '"
                + toNodeId
                + "'");
      }
    }
  }

  private static void validatePropertyOwnership(
      GraphPatchOwnershipPolicy ownership,
      GraphPatch patch,
      Map<String, String> nodeTypeByNodeId,
      List<String> findings) {
    if (patch.propertyPatches() == null) {
      return;
    }
    for (PropertyPatch propertyPatch : patch.propertyPatches()) {
      if (propertyPatch == null
          || propertyPatch.property() == null
          || propertyPatch.property().key() == null) {
        continue;
      }
      if (propertyPatch.operation() == GraphPatchOperation.REMOVE) {
        findings.add("ownership violation: property REMOVE is not allowed");
        continue;
      }
      String nodeId = propertyPatch.targetNodeId();
      String nodeType = nodeTypeByNodeId.get(nodeId);
      if (nodeType == null) {
        findings.add("ownership violation: unknown property target node '" + nodeId + "'");
        continue;
      }
      Set<String> allowedKeys = ownership.properties().get(nodeType);
      if (allowedKeys == null || !allowedKeys.contains(propertyPatch.property().key())) {
        findings.add(
            "ownership violation: property key '"
                + propertyPatch.property().key()
                + "' on node type '"
                + nodeType
                + "' is not owned");
      }
    }
  }

  private static void validateChainOwnership(
      GraphPatchOwnershipPolicy ownership, GraphPatch patch, List<String> findings) {
    if (patch.chainPatches() == null) {
      return;
    }
    for (ChainPatch chainPatch : patch.chainPatches()) {
      if (chainPatch == null || chainPatch.property() == null || chainPatch.property().key() == null) {
        continue;
      }
      if (chainPatch.operation() == GraphPatchOperation.REMOVE) {
        findings.add("ownership violation: chain REMOVE is not allowed");
        continue;
      }
      if (!ownership.chainFields().contains(chainPatch.property().key())) {
        findings.add(
            "ownership violation: chain field '" + chainPatch.property().key() + "' is not owned");
      }
    }
  }

  private static boolean isAllowedEdgeEndpoint(
      String nodeId,
      GraphPatchOwnershipPolicy ownership,
      Map<String, String> preExistingNodeTypeByNodeId,
      Map<String, String> nodeTypeByNodeId,
      Set<String> samePatchAddedNodeIds) {
    if (nodeId == null || nodeId.isBlank()) {
      return false;
    }
    if (samePatchAddedNodeIds.contains(nodeId)) {
      return true;
    }
    if (!preExistingNodeTypeByNodeId.containsKey(nodeId)) {
      return false;
    }
    String nodeType = nodeTypeByNodeId.get(nodeId);
    return nodeType != null && ownership.nodeTypes().contains(nodeType);
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> byNodeId = new LinkedHashMap<>();
    if (graph == null || graph.nodes() == null) {
      return byNodeId;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.nodeId() == null) {
        continue;
      }
      byNodeId.put(node.nodeId(), node);
    }
    return byNodeId;
  }

  private static Map<String, ChainPlanEdge> indexEdges(ChainPlanGraph graph) {
    Map<String, ChainPlanEdge> byEdgeId = new LinkedHashMap<>();
    if (graph == null || graph.edges() == null) {
      return byEdgeId;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (edge == null || edge.edgeId() == null) {
        continue;
      }
      byEdgeId.put(edge.edgeId(), edge);
    }
    return byEdgeId;
  }

  private static Map<String, String> indexNodeTypes(ChainPlanGraph graph) {
    Map<String, String> byNodeId = new LinkedHashMap<>();
    if (graph == null || graph.nodes() == null) {
      return byNodeId;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.nodeId() == null || node.type() == null) {
        continue;
      }
      byNodeId.put(node.nodeId(), node.type());
    }
    return byNodeId;
  }

  private static Set<String> indexAddedNodes(Map<String, String> nodeTypeByNodeId, GraphPatch patch) {
    Set<String> addedNodeIds = new HashSet<>();
    if (patch.nodePatches() == null) {
      return addedNodeIds;
    }
    for (NodePatch nodePatch : patch.nodePatches()) {
      if (nodePatch == null
          || nodePatch.operation() != GraphPatchOperation.ADD
          || nodePatch.node() == null
          || nodePatch.node().nodeId() == null
          || nodePatch.node().type() == null) {
        continue;
      }
      nodeTypeByNodeId.put(nodePatch.node().nodeId(), nodePatch.node().type());
      addedNodeIds.add(nodePatch.node().nodeId());
    }
    return addedNodeIds;
  }

  private static ValidationResult invalid(String message) {
    String summary = Objects.requireNonNullElse(message, "Ownership validation failed");
    ValidationIssue issue =
        new ValidationIssue(
            OWNERSHIP_VIOLATION_ISSUE_ID,
            ValidationSeverity.BLOCKER,
            summary,
            "graph-patch-ownership-validator",
            List.of(),
            List.of(),
            null);
    return new ValidationResult(false, List.of(issue), summary);
  }
}
