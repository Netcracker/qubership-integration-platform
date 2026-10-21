package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Compiler metadata and configuration keys on a mapper-2 or script execution site. */
public final class MappingExecutionSite {

  public static final String ELEMENT_TYPE = "mapper-2";
  public static final String SCRIPT_ELEMENT_TYPE = "script";
  public static final String MAPPING_INTENT_ID_PROPERTY = "mappingIntentId";
  public static final String SEMANTIC_EDGE_ID_PROPERTY = "semanticEdgeId";
  public static final String MAPPING_ID_PROPERTY = "mappingId";
  public static final String MAPPING_SOURCE_PORT_PROPERTY = "mappingSourcePort";
  public static final String MAPPING_TARGET_PORT_PROPERTY = "mappingTargetPort";
  public static final String MAPPING_DESCRIPTION_PROPERTY = "mappingDescription";
  public static final String SCRIPT_PROPERTY = "script";
  public static final String MAPPING_COVERAGE_PROPERTY = "mappingCoverage";

  private MappingExecutionSite() {}

  public static boolean isCompilerMetadataKey(String key) {
    return MAPPING_INTENT_ID_PROPERTY.equals(key)
        || SEMANTIC_EDGE_ID_PROPERTY.equals(key)
        || MAPPING_ID_PROPERTY.equals(key)
        || MAPPING_SOURCE_PORT_PROPERTY.equals(key)
        || MAPPING_TARGET_PORT_PROPERTY.equals(key)
        || MAPPING_COVERAGE_PROPERTY.equals(key);
  }

  public static boolean isTransformShell(ChainPlanNode node) {
    if (node == null) {
      return false;
    }
    String type = trim(node.type());
    return ELEMENT_TYPE.equals(type) || SCRIPT_ELEMENT_TYPE.equals(type);
  }

  public static boolean isMapper2(ChainPlanNode node) {
    return node != null && ELEMENT_TYPE.equals(trim(node.type()));
  }

  public static boolean isScript(ChainPlanNode node) {
    return node != null && SCRIPT_ELEMENT_TYPE.equals(trim(node.type()));
  }

  public static String mappingIntentId(ChainPlanNode node) {
    return propertyValue(node, MAPPING_INTENT_ID_PROPERTY);
  }

  public static String semanticEdgeId(ChainPlanNode node) {
    return propertyValue(node, SEMANTIC_EDGE_ID_PROPERTY);
  }

  public static String mappingId(ChainPlanNode node) {
    return propertyValue(node, MAPPING_ID_PROPERTY);
  }

  public static String mappingSourcePort(ChainPlanNode node) {
    return propertyValue(node, MAPPING_SOURCE_PORT_PROPERTY);
  }

  public static String mappingTargetPort(ChainPlanNode node) {
    return propertyValue(node, MAPPING_TARGET_PORT_PROPERTY);
  }

  public static String mappingDescription(ChainPlanNode node) {
    return propertyValue(node, MAPPING_DESCRIPTION_PROPERTY);
  }

  public static String scriptBody(ChainPlanNode node) {
    return propertyValue(node, SCRIPT_PROPERTY);
  }

  public static String mappingCoverage(ChainPlanNode node) {
    return propertyValue(node, MAPPING_COVERAGE_PROPERTY);
  }

  public static boolean isConfigured(ChainPlanNode node) {
    if (isScript(node)) {
      String script = scriptBody(node);
      return script != null && !script.isBlank();
    }
    String description = mappingDescription(node);
    return description != null && !description.isBlank();
  }

  public static ChainPlanNode withMappingIntentId(ChainPlanNode node, String mappingIntentId) {
    return withProperty(node, MAPPING_INTENT_ID_PROPERTY, mappingIntentId);
  }

  public static ChainPlanNode withBoundary(
      ChainPlanNode node, String mappingIntentId, String sourcePort, String targetPort) {
    return withProperty(
        withProperty(
            withMappingIntentId(node, mappingIntentId),
            MAPPING_SOURCE_PORT_PROPERTY,
            sourcePort),
        MAPPING_TARGET_PORT_PROPERTY,
        targetPort);
  }

  /** Drops generated script, coverage, and mapper description so generation starts from the approved rules. */
  public static ChainPlanNode withoutGeneratedArtifact(ChainPlanNode node) {
    if (node == null || node.properties() == null) {
      return node;
    }
    List<PlanProperty> properties = new ArrayList<>();
    for (PlanProperty property : node.properties()) {
      if (SCRIPT_PROPERTY.equals(property.key())
          || MAPPING_COVERAGE_PROPERTY.equals(property.key())
          || MAPPING_DESCRIPTION_PROPERTY.equals(property.key())) {
        continue;
      }
      properties.add(property);
    }
    if (properties.size() == node.properties().size()) {
      return node;
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  static ChainPlanNode withProperty(ChainPlanNode node, String key, String value) {
    List<PlanProperty> properties = new ArrayList<>();
    boolean replaced = false;
    if (node.properties() != null) {
      for (PlanProperty property : node.properties()) {
        if (key.equals(property.key())) {
          properties.add(new PlanProperty(key, value));
          replaced = true;
        } else {
          properties.add(property);
        }
      }
    }
    if (!replaced) {
      properties.add(new PlanProperty(key, value));
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  private static String propertyValue(ChainPlanNode node, String key) {
    if (node == null || node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
