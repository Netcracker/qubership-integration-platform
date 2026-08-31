package org.qubership.integration.platform.ai.chain.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.util.HttpMethodRestrictCatalogShape;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

/** Pure compare logic between plan, materialization map, and catalog snapshot. */
@ApplicationScoped
public class ChainReconcileService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ReconcileResult compare(
      ChainPlanGraph plan, MaterializationMap map, ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");

    List<String> missingElementIds = new ArrayList<>();
    List<String> missingConnections = new ArrayList<>();
    List<String> parentMismatches = new ArrayList<>();
    List<String> labelMismatches = new ArrayList<>();
    List<String> propertyMismatches = new ArrayList<>();
    List<String> chainMismatches = new ArrayList<>();

    Map<String, String> nodeMap =
        map != null && map.nodeIdToElementId() != null ? map.nodeIdToElementId() : Map.of();
    Set<String> catalogElementIds = catalogElementIds(facts);

    if (plan != null && plan.chain() != null) {
      String expectedName = blankToNull(plan.chain().name());
      String actualName = blankToNull(facts.chainName());
      if (expectedName != null && !Objects.equals(expectedName, actualName)) {
        chainMismatches.add("chain.name");
      }
    }

    if (plan != null && plan.nodes() != null) {
      for (ChainPlanNode node : plan.nodes()) {
        String nodeId = node.nodeId();
        if (nodeId == null) {
          continue;
        }
        String elementId = nodeMap.get(nodeId);
        if (elementId == null) {
          missingElementIds.add(nodeId);
          continue;
        }
        if (!catalogElementIds.contains(elementId)) {
          missingElementIds.add(nodeId + "->" + elementId);
          continue;
        }
        ChainCatalogElement element = findElement(facts, elementId);
        if (element == null) {
          missingElementIds.add(nodeId + "->" + elementId);
          continue;
        }
        if (!Objects.equals(normalize(node.type()), normalize(element.type()))) {
          propertyMismatches.add(nodeId + ".type");
        }
        if (!Objects.equals(normalize(node.label()), normalize(element.name()))) {
          labelMismatches.add(nodeId);
        }
        checkParentMismatch(node, elementId, nodeMap, facts, parentMismatches);
        checkPropertyMismatches(node, element, propertyMismatches);
      }
    }

    if (plan != null && plan.edges() != null) {
      Map<String, ChainPlanNode> nodesById = nodesById(plan);
      Set<String> depKeys = dependencyKeys(facts);
      for (ChainPlanEdge edge : plan.edges()) {
        if (edge.fromNodeId() == null || edge.toNodeId() == null) {
          continue;
        }
        // Parent→child edges are placement/containment only; ConnectionsMaterializer skips them.
        if (isStructuralBranchEntry(edge, nodesById)) {
          continue;
        }
        String fromId = nodeMap.get(edge.fromNodeId());
        String toId = nodeMap.get(edge.toNodeId());
        if (fromId == null || toId == null) {
          missingConnections.add(edge.fromNodeId() + "->" + edge.toNodeId());
          continue;
        }
        if (!depKeys.contains(depKey(fromId, toId))) {
          missingConnections.add(edge.fromNodeId() + "->" + edge.toNodeId());
        }
      }
    }

    boolean matches =
        missingElementIds.isEmpty()
            && missingConnections.isEmpty()
            && parentMismatches.isEmpty()
            && labelMismatches.isEmpty()
            && propertyMismatches.isEmpty()
            && chainMismatches.isEmpty();

    String summary =
        buildSummary(
            facts.chainId(),
            matches,
            missingElementIds,
            missingConnections,
            labelMismatches,
            propertyMismatches,
            chainMismatches);

    return new ReconcileResult(
        matches,
        List.copyOf(missingElementIds),
        List.copyOf(missingConnections),
        List.copyOf(parentMismatches),
        List.copyOf(labelMismatches),
        List.copyOf(propertyMismatches),
        List.copyOf(chainMismatches),
        summary);
  }

  private static void checkPropertyMismatches(
      ChainPlanNode node, ChainCatalogElement element, List<String> propertyMismatches) {
    List<PlanProperty> planProperties = node.properties() == null ? List.of() : node.properties();
    Map<String, Object> catalogProperties =
        element.properties() == null ? Map.of() : element.properties();
    for (PlanProperty property : planProperties) {
      if (property == null
          || property.key() == null
          || property.key().isBlank()
          || MappingExecutionSite.isCompilerMetadataKey(property.key().trim())) {
        continue;
      }
      Object catalogValue = catalogProperties.get(property.key());
      if (!canonicalEquals(property.key(), property.value(), catalogValue)) {
        propertyMismatches.add(node.nodeId() + "." + property.key());
      }
    }
  }

  private static boolean canonicalEquals(String propertyKey, Object planValue, Object catalogValue) {
    if (HttpMethodRestrictCatalogShape.PROPERTY_KEY.equals(propertyKey)) {
      return valuesEqual(
          HttpMethodRestrictCatalogShape.toCatalogValue(planValue),
          HttpMethodRestrictCatalogShape.toCatalogValue(catalogValue));
    }
    return valuesEqual(planValue, catalogValue);
  }

  private static boolean valuesEqual(Object planValue, Object catalogValue) {
    try {
      JsonNode planNode = toCanonicalNode(planValue);
      JsonNode catalogNode = toCanonicalNode(catalogValue);
      if (Objects.equals(planNode, catalogNode)) {
        return true;
      }
      // PlanProperty values are always strings; catalog may return booleans/numbers after
      // materialization coercion. Compare scalar asText forms so "false" matches false.
      if (planNode != null
          && catalogNode != null
          && planNode.isValueNode()
          && catalogNode.isValueNode()
          && !planNode.isNull()
          && !catalogNode.isNull()) {
        return Objects.equals(planNode.asText(), catalogNode.asText());
      }
      return false;
    } catch (Exception ignored) {
      return Objects.equals(String.valueOf(planValue), String.valueOf(catalogValue));
    }
  }

  private static JsonNode toCanonicalNode(Object value) throws Exception {
    if (value == null) {
      return MAPPER.nullNode();
    }
    if (value instanceof String text) {
      String trimmed = text.trim();
      if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
          || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
        try {
          return MAPPER.readTree(trimmed);
        } catch (Exception ignored) {
          // Fall through to textual node.
        }
      }
      return MAPPER.getNodeFactory().textNode(text);
    }
    return MAPPER.valueToTree(value);
  }

  private static void checkParentMismatch(
      ChainPlanNode node,
      String elementId,
      Map<String, String> nodeMap,
      ChainCatalogFacts facts,
      List<String> parentMismatches) {
    String planParentNodeId = node.parentNodeId();
    String expectedParentElementId =
        planParentNodeId != null ? nodeMap.get(planParentNodeId) : null;
    String actualParentElementId = findParentElementId(facts, elementId);
    if (Objects.equals(blankToNull(expectedParentElementId), blankToNull(actualParentElementId))) {
      return;
    }
    parentMismatches.add(
        node.nodeId()
            + ": expected parent "
            + (expectedParentElementId != null ? expectedParentElementId : "root")
            + " but catalog has "
            + (actualParentElementId != null ? actualParentElementId : "root"));
  }

  private static Map<String, ChainPlanNode> nodesById(ChainPlanGraph plan) {
    Map<String, ChainPlanNode> index = new LinkedHashMap<>();
    if (plan.nodes() == null) {
      return index;
    }
    for (ChainPlanNode node : plan.nodes()) {
      if (node.nodeId() != null) {
        index.put(node.nodeId(), node);
      }
    }
    return index;
  }

  /**
   * Parent-to-direct-child edges express containment in the plan graph; the catalog represents them
   * through element placement, not runtime dependencies (see ChainPlanConnectionsMaterializer).
   */
  private static boolean isStructuralBranchEntry(
      ChainPlanEdge edge, Map<String, ChainPlanNode> nodesById) {
    ChainPlanNode to = nodesById.get(edge.toNodeId());
    if (to == null) {
      return false;
    }
    String toParent = to.parentNodeId();
    return toParent != null && !toParent.isBlank() && toParent.equals(edge.fromNodeId());
  }

  private static ChainCatalogElement findElement(ChainCatalogFacts facts, String elementId) {
    if (facts.elements() == null) {
      return null;
    }
    for (ChainCatalogElement element : facts.elements()) {
      if (elementId.equals(element.elementId())) {
        return element;
      }
    }
    return null;
  }

  private static String findParentElementId(ChainCatalogFacts facts, String elementId) {
    ChainCatalogElement element = findElement(facts, elementId);
    return element == null ? null : element.parentElementId();
  }

  private static Set<String> catalogElementIds(ChainCatalogFacts facts) {
    Set<String> ids = new HashSet<>();
    if (facts.elements() != null) {
      for (ChainCatalogElement element : facts.elements()) {
        if (element.elementId() != null) {
          ids.add(element.elementId());
        }
      }
    }
    return ids;
  }

  private static Set<String> dependencyKeys(ChainCatalogFacts facts) {
    Set<String> keys = new HashSet<>();
    if (facts.dependencies() != null) {
      for (ChainCatalogDependency dep : facts.dependencies()) {
        if (dep.fromElementId() != null && dep.toElementId() != null) {
          keys.add(depKey(dep.fromElementId(), dep.toElementId()));
        }
      }
    }
    return keys;
  }

  private static String depKey(String from, String to) {
    return from + "->" + to;
  }

  private static String buildSummary(
      String chainId,
      boolean matches,
      List<String> missingElementIds,
      List<String> missingConnections,
      List<String> labelMismatches,
      List<String> propertyMismatches,
      List<String> chainMismatches) {
    if (matches) {
      return "Catalog matches plan for chain " + chainId;
    }
    return "Reconcile found "
        + missingElementIds.size()
        + " element issue(s), "
        + missingConnections.size()
        + " connection issue(s), "
        + labelMismatches.size()
        + " label issue(s), "
        + propertyMismatches.size()
        + " property issue(s), "
        + chainMismatches.size()
        + " chain issue(s)";
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String normalize(String value) {
    return blankToNull(value);
  }
}
