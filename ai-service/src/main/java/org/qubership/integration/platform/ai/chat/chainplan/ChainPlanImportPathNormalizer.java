package org.qubership.integration.platform.ai.chat.chainplan;

import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingKeys;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingProps;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Repairs planner mistakes where API Hub {@code operationId} values were written into catalog
 * {@code integrationOperationId} instead of the import-path element row.
 */
public final class ChainPlanImportPathNormalizer {

  private static final Set<String> CATALOG_BINDING_KEYS =
      Set.of(
          OperationBindingKeys.INTEGRATION_OPERATION_ID,
          OperationBindingKeys.INTEGRATION_SYSTEM_ID,
          OperationBindingKeys.INTEGRATION_SPECIFICATION_ID,
          OperationBindingKeys.INTEGRATION_SPECIFICATION_GROUP_ID,
          OperationBindingKeys.INTEGRATION_OPERATION_METHOD,
          OperationBindingKeys.INTEGRATION_OPERATION_PATH);

  private static final Set<String> PLANNER_IMPORT_KEYS =
      Set.of(
          "importRequired",
          "apiHubPackageId",
          "apiHubVersion",
          "apiHubOperationId",
          "apiHubSpecificationName",
          "apiHubDocumentId",
          "catalogSystemName",
          "catalogSystemType");

  private ChainPlanImportPathNormalizer() {}

  public static void normalize(ChainImplementationPlan plan) {
    if (plan == null || plan.getElements() == null) {
      return;
    }
    PlanTreeUtils.preOrder(plan.getElements(), ChainPlanImportPathNormalizer::normalizeNode);
  }

  private static void normalizeNode(ElementPlan node) {
    if (node == null || !"service-call".equalsIgnoreCase(trimType(node.getType()))) {
      return;
    }
    liftImportMetadataFromExpectedProperties(node);
    if (Boolean.TRUE.equals(node.getImportRequired())) {
      stripCatalogBindingKeys(node);
      stripPlannerImportKeysFromExpectedProperties(node);
      return;
    }

    String misplacedApiHubOpId = misplacedApiHubOperationId(node);
    if (misplacedApiHubOpId == null) {
      return;
    }

    if (!hasNonBlankString(node.getApiHubOperationId())) {
      node.setApiHubOperationId(misplacedApiHubOpId);
    }
    stripCatalogBindingKeys(node);
    stripPlannerImportKeysFromExpectedProperties(node);
    node.setImportRequired(true);
  }

  /** Planner-only import fields belong on the element row, not in {@code expectedProperties}. */
  private static void liftImportMetadataFromExpectedProperties(ElementPlan node) {
    Map<String, Object> props = node.getExpectedProperties();
    if (props == null || props.isEmpty()) {
      return;
    }
    if (!Boolean.TRUE.equals(node.getImportRequired()) && Boolean.TRUE.equals(booleanProp(props, "importRequired"))) {
      node.setImportRequired(true);
    }
    liftStringField(node, props, "apiHubPackageId", node.getApiHubPackageId(), node::setApiHubPackageId);
    liftStringField(node, props, "apiHubVersion", node.getApiHubVersion(), node::setApiHubVersion);
    liftStringField(node, props, "apiHubOperationId", node.getApiHubOperationId(), node::setApiHubOperationId);
    liftStringField(
        node,
        props,
        "apiHubSpecificationName",
        node.getApiHubSpecificationName(),
        node::setApiHubSpecificationName);
    liftStringField(
        node, props, "apiHubDocumentId", node.getApiHubDocumentId(), node::setApiHubDocumentId);
    liftStringField(
        node, props, "catalogSystemName", node.getCatalogSystemName(), node::setCatalogSystemName);
    liftStringField(
        node, props, "catalogSystemType", node.getCatalogSystemType(), node::setCatalogSystemType);
    stripPlannerImportKeysFromExpectedProperties(node);
  }

  private static void liftStringField(
      ElementPlan node,
      Map<String, Object> props,
      String key,
      String currentValue,
      java.util.function.Consumer<String> setter) {
    if (hasNonBlankString(currentValue)) {
      return;
    }
    String fromProps = OperationBindingProps.stringProp(props, key);
    if (fromProps != null) {
      setter.accept(fromProps);
    }
  }

  private static void stripPlannerImportKeysFromExpectedProperties(ElementPlan node) {
    Map<String, Object> props = node.getExpectedProperties();
    if (props == null || props.isEmpty()) {
      return;
    }
    Map<String, Object> cleaned = new LinkedHashMap<>(props);
    PLANNER_IMPORT_KEYS.forEach(cleaned::remove);
    if (cleaned.isEmpty()) {
      node.setExpectedProperties(null);
    } else {
      node.setExpectedProperties(cleaned);
    }
  }

  private static Boolean booleanProp(Map<String, Object> props, String key) {
    Object value = props.get(key);
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String text && !text.isBlank()) {
      return Boolean.parseBoolean(text.trim());
    }
    return null;
  }

  static String misplacedApiHubOperationId(ElementPlan node) {
    if (node == null || node.getExpectedProperties() == null) {
      return null;
    }
    String fromProps =
        OperationBindingProps.stringProp(
            node.getExpectedProperties(), OperationBindingKeys.INTEGRATION_OPERATION_ID);
    if (fromProps == null || !PlanServiceBindingRules.looksLikeApiHubOperationId(fromProps)) {
      return null;
    }
    return fromProps.trim();
  }

  private static void stripCatalogBindingKeys(ElementPlan node) {
    Map<String, Object> props = node.getExpectedProperties();
    if (props == null || props.isEmpty()) {
      return;
    }
    Map<String, Object> cleaned = new LinkedHashMap<>(props);
    CATALOG_BINDING_KEYS.forEach(cleaned::remove);
    if (cleaned.isEmpty()) {
      node.setExpectedProperties(null);
    } else {
      node.setExpectedProperties(cleaned);
    }
  }

  private static boolean hasNonBlankString(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimType(String type) {
    return type == null ? "" : type.trim();
  }
}
