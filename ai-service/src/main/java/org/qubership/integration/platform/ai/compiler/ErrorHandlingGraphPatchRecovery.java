package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/**
 * When {@code cip-error-handling-generator} tries to ADD EH nodes while the plan already has a
 * try-catch-finally-2 topology (from GP-01 / chain-generator), coerce the capture to the
 * documented happy path: property-enrich incomplete {@code catch-2}, or {@code notApplicable=true}
 * when topology is already complete.
 *
 * <p>Covers both same-id ADD collisions ("already exists") and greenfield wraps that use new node
 * ids and fail later containment validation (for example service-call parentNodeId).
 */
final class ErrorHandlingGraphPatchRecovery {

  static final String CAPABILITY_ID = "cip-error-handling-generator";

  private static final Set<String> OWNED_EH_TYPES = ChainElementFamilies.TRY_CATCH;

  private ErrorHandlingGraphPatchRecovery() {}

  static Optional<GraphPatchCapture> recover(
      String capabilityId,
      ChainPlanGraph base,
      GraphPatchCapture attempted,
      String previewFailure) {
    if (!CAPABILITY_ID.equals(capabilityId) || base == null || attempted == null) {
      return Optional.empty();
    }
    if (previewFailure == null || previewFailure.isBlank()) {
      return Optional.empty();
    }
    if (!addsOwnedEhNodes(attempted)) {
      return Optional.empty();
    }
    // Only recover when the base plan already has EH shells — otherwise a failed greenfield wrap
    // is a real generator error the agent must repair.
    if (!hasEhTopology(base)) {
      return Optional.empty();
    }
    Optional<ChainPlanNode> incompleteCatch = findCatchMissingMandatoryProps(base);
    if (incompleteCatch.isPresent()) {
      return Optional.of(propertyEnrich(capabilityId, incompleteCatch.get()));
    }
    if (hasCompleteTryCatch(base)) {
      return Optional.of(notApplicable(capabilityId));
    }
    return Optional.empty();
  }

  private static boolean addsOwnedEhNodes(GraphPatchCapture attempted) {
    if (attempted.nodePatches() == null) {
      return false;
    }
    for (NodePatch nodePatch : attempted.nodePatches()) {
      if (nodePatch == null
          || nodePatch.operation() != GraphPatchOperation.ADD
          || nodePatch.node() == null) {
        continue;
      }
      if (OWNED_EH_TYPES.contains(nodePatch.node().type())) {
        return true;
      }
    }
    return false;
  }

  /** True when the plan already has try-catch-finally-2 with try-2 and catch-2 children. */
  private static boolean hasEhTopology(ChainPlanGraph graph) {
    if (graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!"try-catch-finally-2".equals(node.type())) {
        continue;
      }
      if (hasChildOfType(graph.nodes(), node.nodeId(), "try-2")
          && hasChildOfType(graph.nodes(), node.nodeId(), "catch-2")) {
        return true;
      }
    }
    return false;
  }

  private static Optional<ChainPlanNode> findCatchMissingMandatoryProps(ChainPlanGraph graph) {
    if (graph.nodes() == null) {
      return Optional.empty();
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!"try-catch-finally-2".equals(node.type())) {
        continue;
      }
      ChainPlanNode catchNode = findChildOfType(graph.nodes(), node.nodeId(), "catch-2");
      if (catchNode == null) {
        continue;
      }
      if (!hasNonBlankProperty(catchNode, "exception")
          || !hasNonBlankProperty(catchNode, "priority")) {
        return Optional.of(catchNode);
      }
    }
    return Optional.empty();
  }

  private static boolean hasCompleteTryCatch(ChainPlanGraph graph) {
    if (graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    boolean hasWrapper = false;
    for (ChainPlanNode node : graph.nodes()) {
      if (!"try-catch-finally-2".equals(node.type())) {
        continue;
      }
      hasWrapper = true;
      if (!hasChildOfType(graph.nodes(), node.nodeId(), "try-2")) {
        return false;
      }
      ChainPlanNode catchNode = findChildOfType(graph.nodes(), node.nodeId(), "catch-2");
      if (catchNode == null || !hasNonBlankProperty(catchNode, "exception")) {
        return false;
      }
    }
    return hasWrapper;
  }

  private static GraphPatchCapture propertyEnrich(String capabilityId, ChainPlanNode catchNode) {
    List<PropertyPatchCapture> propertyPatches = new ArrayList<>(2);
    if (!hasNonBlankProperty(catchNode, "exception")) {
      propertyPatches.add(
          new PropertyPatchCapture(
              GraphPatchOperation.ADD,
              catchNode.nodeId(),
              "exception",
              JsonNodeFactory.instance.textNode("java.lang.Exception")));
    }
    if (!hasNonBlankProperty(catchNode, "priority")) {
      propertyPatches.add(
          new PropertyPatchCapture(
              GraphPatchOperation.ADD,
              catchNode.nodeId(),
              "priority",
              JsonNodeFactory.instance.numberNode(0)));
    }
    return new GraphPatchCapture(
        "eh-recover-catch-2-mandatory-properties",
        capabilityId,
        List.of(),
        List.of(),
        propertyPatches,
        List.of(),
        List.of(),
        "Recovered from EH ADD on existing topology: property-enrich catch-2.",
        false);
  }

  private static GraphPatchCapture notApplicable(String capabilityId) {
    return new GraphPatchCapture(
        "eh-recover-not-applicable",
        capabilityId,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Recovered from EH ADD on existing topology: already complete.",
        true);
  }

  private static boolean hasChildOfType(
      List<ChainPlanNode> nodes, String parentNodeId, String childType) {
    return findChildOfType(nodes, parentNodeId, childType) != null;
  }

  private static ChainPlanNode findChildOfType(
      List<ChainPlanNode> nodes, String parentNodeId, String childType) {
    return nodes.stream()
        .filter(node -> childType.equals(node.type()) && parentNodeId.equals(node.parentNodeId()))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasNonBlankProperty(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())
          && property.value() != null
          && !property.value().isBlank()) {
        return true;
      }
    }
    return false;
  }
}
