package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Validates the desired containment graph against live catalog descriptors before any mutation.
 *
 * <p>CREATE passes an empty current graph. EDIT passes the imported chain. A failure names the
 * graph defect and must not be followed by a catalog write.
 */
public final class DesiredGraphDescriptorPreflight {

  public void validate(
      ChainPlanGraph desired, ChainPlanGraph current, CatalogElementDescriptorCache cache) {
    Objects.requireNonNull(desired, "desired");
    Objects.requireNonNull(cache, "cache");
    List<ChainPlanNode> desiredNodes = nodes(desired);
    Map<String, ChainPlanNode> desiredById = indexById(desiredNodes);
    Set<String> currentTypes = types(current);
    Map<String, CatalogElementDescriptor> descriptors = loadDesired(desiredNodes, cache);

    rejectNewlyIntroducedDeprecated(desiredNodes, descriptors, currentTypes);
    rejectPlacementDefects(desiredNodes, desiredById, descriptors);
    rejectCardinalityAndInnerContent(desiredNodes, descriptors);
  }

  private static Map<String, CatalogElementDescriptor> loadDesired(
      List<ChainPlanNode> desiredNodes, CatalogElementDescriptorCache cache) {
    Map<String, CatalogElementDescriptor> descriptors = new HashMap<>();
    for (ChainPlanNode node : desiredNodes) {
      String type = trim(node.type());
      if (type == null || type.isEmpty()) {
        throw new DesiredGraphDescriptorPreflightException(
            "Node '" + node.nodeId() + "' has a blank element type.");
      }
      if (descriptors.containsKey(type)) {
        continue;
      }
      try {
        descriptors.put(type, cache.require(type));
      } catch (CatalogElementDescriptorException e) {
        throw new DesiredGraphDescriptorPreflightException(e.getMessage(), e);
      }
    }
    return descriptors;
  }

  private static void rejectNewlyIntroducedDeprecated(
      List<ChainPlanNode> desiredNodes,
      Map<String, CatalogElementDescriptor> descriptors,
      Set<String> currentTypes) {
    for (ChainPlanNode node : desiredNodes) {
      String type = trim(node.type());
      CatalogElementDescriptor descriptor = descriptors.get(type);
      if (descriptor != null && descriptor.deprecated() && !currentTypes.contains(type)) {
        throw new DesiredGraphDescriptorPreflightException(
            "Cannot introduce deprecated element type '"
                + type
                + "' at node '"
                + node.nodeId()
                + "'.");
      }
    }
  }

  private static void rejectPlacementDefects(
      List<ChainPlanNode> desiredNodes,
      Map<String, ChainPlanNode> desiredById,
      Map<String, CatalogElementDescriptor> descriptors) {
    for (ChainPlanNode node : desiredNodes) {
      String parentId = trim(node.parentNodeId());
      if (parentId == null || parentId.isEmpty()) {
        continue;
      }
      rejectOnePlacement(node, parentId, desiredById, descriptors);
    }
  }

  private static void rejectOnePlacement(
      ChainPlanNode node,
      String parentId,
      Map<String, ChainPlanNode> desiredById,
      Map<String, CatalogElementDescriptor> descriptors) {
    String childType = trim(node.type());
    CatalogElementDescriptor child = descriptors.get(childType);
    ChainPlanNode parent = desiredById.get(parentId);
    if (parent == null) {
      throw placement(node.nodeId(), childType, parentId, "parent node is missing.");
    }
    String parentType = trim(parent.type());
    CatalogElementDescriptor destination = descriptors.get(parentType);
    // Live catalog triggers omit allowedInContainers (DTO default true). Family membership is the
    // trigger test; allowedInContainers is a separate placement flag used by types such as reuse.
    if (ChainPlanGraphValidator.isTriggerElementType(childType)) {
      throw new DesiredGraphDescriptorPreflightException(
          "Cannot place trigger '"
              + node.nodeId()
              + "' (type '"
              + childType
              + "') under parent '"
              + parentId
              + "': catalog triggers belong at chain root.");
    }
    if (child != null && !child.allowedInContainers()) {
      throw placement(
          node.nodeId(),
          childType,
          parentId,
          "this type is not allowed in containers.");
    }
    if (destination != null && !destination.container()) {
      throw placement(
          node.nodeId(),
          childType,
          parentId,
          "parent type '" + parentType + "' is not a container.");
    }
    if (destination != null
        && !destination.allowedChildren().isEmpty()
        && !destination.allowedChildren().containsKey(childType)) {
      throw placement(
          node.nodeId(),
          childType,
          parentId,
          "child type '" + childType + "' is not allowed.");
    }
    if (child != null
        && !child.parentRestriction().isEmpty()
        && !child.parentRestriction().contains(parentType)) {
      throw placement(
          node.nodeId(),
          childType,
          parentId,
          "parent type '" + parentType + "' is not permitted.");
    }
  }

  private static DesiredGraphDescriptorPreflightException placement(
      String nodeId, String childType, String parentId, String reason) {
    return new DesiredGraphDescriptorPreflightException(
        "Cannot place node '"
            + nodeId
            + "' (type '"
            + childType
            + "') under '"
            + parentId
            + "': "
            + reason);
  }

  private static void rejectCardinalityAndInnerContent(
      List<ChainPlanNode> desiredNodes, Map<String, CatalogElementDescriptor> descriptors) {
    Map<String, List<ChainPlanNode>> childrenByParent = directChildren(desiredNodes);
    for (ChainPlanNode parent : desiredNodes) {
      rejectOneContainer(parent, descriptors, childrenByParent);
    }
  }

  private static void rejectOneContainer(
      ChainPlanNode parent,
      Map<String, CatalogElementDescriptor> descriptors,
      Map<String, List<ChainPlanNode>> childrenByParent) {
    String parentType = trim(parent.type());
    CatalogElementDescriptor descriptor = descriptors.get(parentType);
    if (descriptor == null || !descriptor.container()) {
      return;
    }
    List<ChainPlanNode> children = childrenByParent.getOrDefault(parent.nodeId(), List.of());
    if (descriptor.mandatoryInnerElement() && children.isEmpty()) {
      throw new DesiredGraphDescriptorPreflightException(
          "Container '"
              + parent.nodeId()
              + "' (type '"
              + parentType
              + "') requires inner content.");
    }
    if (descriptor.allowedChildren().isEmpty()) {
      return;
    }
    Map<String, Integer> counts = countByType(children);
    for (Map.Entry<String, CatalogChildQuantity> allowed : descriptor.allowedChildren().entrySet()) {
      rejectQuantity(parent, parentType, allowed.getKey(), allowed.getValue(), counts);
    }
  }

  private static void rejectQuantity(
      ChainPlanNode parent,
      String parentType,
      String childType,
      CatalogChildQuantity quantity,
      Map<String, Integer> counts) {
    int count = counts.getOrDefault(childType, 0);
    int min = quantity.minimum();
    Integer max = quantity.maximum();
    if (count == 0 && isMandatoryRole(quantity)) {
      throw new DesiredGraphDescriptorPreflightException(
          "Container '"
              + parent.nodeId()
              + "' (type '"
              + parentType
              + "') is missing mandatory child type '"
              + childType
              + "'.");
    }
    if (count < min) {
      throw quantityError(parent, parentType, childType, count, "minimum is " + min);
    }
    if (max != null && count > max) {
      throw quantityError(parent, parentType, childType, count, "maximum is " + max);
    }
  }

  private static DesiredGraphDescriptorPreflightException quantityError(
      ChainPlanNode parent, String parentType, String childType, int count, String bound) {
    return new DesiredGraphDescriptorPreflightException(
        "Container '"
            + parent.nodeId()
            + "' (type '"
            + parentType
            + "') has "
            + count
            + (count == 1 ? " child" : " children")
            + " of type '"
            + childType
            + "'; "
            + bound
            + ".");
  }

  private static boolean isMandatoryRole(CatalogChildQuantity quantity) {
    return quantity == CatalogChildQuantity.ONE || quantity == CatalogChildQuantity.TWO_OR_MANY;
  }

  private static Map<String, List<ChainPlanNode>> directChildren(List<ChainPlanNode> nodes) {
    Map<String, List<ChainPlanNode>> children = new HashMap<>();
    for (ChainPlanNode node : nodes) {
      String parentId = trim(node.parentNodeId());
      if (parentId == null || parentId.isEmpty()) {
        continue;
      }
      children.computeIfAbsent(parentId, key -> new ArrayList<>()).add(node);
    }
    return children;
  }

  private static Map<String, Integer> countByType(List<ChainPlanNode> children) {
    Map<String, Integer> counts = new HashMap<>();
    for (ChainPlanNode child : children) {
      String type = trim(child.type());
      if (type == null || type.isEmpty()) {
        continue;
      }
      Integer previous = counts.get(type);
      counts.put(type, previous == null ? 1 : previous + 1);
    }
    return counts;
  }

  private static Map<String, ChainPlanNode> indexById(List<ChainPlanNode> nodes) {
    Map<String, ChainPlanNode> byId = new HashMap<>();
    for (ChainPlanNode node : nodes) {
      if (node.nodeId() != null) {
        byId.put(node.nodeId(), node);
      }
    }
    return byId;
  }

  private static Set<String> types(ChainPlanGraph graph) {
    Set<String> types = new HashSet<>();
    for (ChainPlanNode node : nodes(graph)) {
      String type = trim(node.type());
      if (type != null && !type.isEmpty()) {
        types.add(type);
      }
    }
    return types;
  }

  private static List<ChainPlanNode> nodes(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null) {
        nodes.add(node);
      }
    }
    return nodes;
  }

  private static String trim(String value) {
    return value != null ? value.trim() : null;
  }
}
