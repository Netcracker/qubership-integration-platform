package org.qubership.integration.platform.ai.chat.chainplan;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.List;
import java.util.Map;

/** Derives which plan rows require catalog operation binding and whether binding is satisfied. */
public final class PlanServiceBindingRules {

  private PlanServiceBindingRules() {}

  public static boolean requiresOperationBinding(ElementPlan node) {
    if (node == null || node.getType() == null) {
      return false;
    }
    String type = node.getType().trim();
    if ("service-call".equalsIgnoreCase(type)) {
      return true;
    }
    if ("async-api-trigger".equalsIgnoreCase(type)) {
      return hasAnyIntegrationBindingKey(node.getExpectedProperties());
    }
    if ("http-trigger".equalsIgnoreCase(type)) {
      return looksLikeImplementedHttpEndpoint(node.getExpectedProperties());
    }
    return false;
  }

  public static boolean isBindingSatisfied(ElementPlan node) {
    if (node == null) {
      return true;
    }
    if (isUserAcceptedUnbound(node.getBindingStatus())) {
      return true;
    }
    if ("service-call".equalsIgnoreCase(trimType(node.getType()))) {
      return hasNonBlankIntegrationOperationId(node.getExpectedProperties());
    }
    if ("async-api-trigger".equalsIgnoreCase(trimType(node.getType()))) {
      return hasNonBlankIntegrationOperationId(node.getExpectedProperties())
          || hasNonBlankIntegrationGqlQuery(node.getExpectedProperties());
    }
    if ("http-trigger".equalsIgnoreCase(trimType(node.getType()))) {
      if (!looksLikeImplementedHttpEndpoint(node.getExpectedProperties())) {
        return true;
      }
      return hasNonBlankIntegrationOperationId(node.getExpectedProperties());
    }
    return true;
  }

  public static void walkForUnresolvedBinding(List<ElementPlan> roots, List<PlanOpenItem> out) {
    if (roots == null) {
      return;
    }
    for (ElementPlan node : roots) {
      if (node == null) {
        continue;
      }
      if (requiresOperationBinding(node) && !isBindingSatisfied(node)) {
        String cid = node.getClientId() != null ? node.getClientId() : "";
        String et = node.getType() != null ? node.getType().trim() : "";
        out.add(
            new PlanOpenItem(
                PlanOpenItem.idServiceBindingUnresolved(cid),
                PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED,
                cid,
                null,
                et,
                bindingDebtMessage(et),
                List.of(),
                false));
      }
      walkForUnresolvedBinding(node.getChildren(), out);
    }
  }

  private static String bindingDebtMessage(String elementType) {
    return elementType
        + " is missing required operation binding in expectedProperties (integrationOperationId"
        + " and/or integrationGqlQuery per element schema). Resolve in CREATE_CHAIN_PLAN (catalog,"
        + " then APIHub if needed), or set bindingStatus to user_accepted_unbound where applicable"
        + " and omit operation fields.";
  }

  private static String trimType(String type) {
    return type == null ? "" : type.trim();
  }

  private static boolean isUserAcceptedUnbound(String bindingStatus) {
    return bindingStatus != null && "user_accepted_unbound".equalsIgnoreCase(bindingStatus.trim());
  }

  private static boolean hasAnyIntegrationBindingKey(Map<String, Object> props) {
    if (props == null) {
      return false;
    }
    return hasNonBlankString(props, "integrationSystemId")
        || hasNonBlankString(props, "integrationOperationId")
        || hasNonBlankString(props, "integrationGqlQuery");
  }

  /**
   * Implemented HTTP endpoint branch: user intends catalog-backed operation (not custom
   * contextPath-only).
   */
  public static boolean looksLikeImplementedHttpEndpoint(Map<String, Object> props) {
    if (props == null) {
      return false;
    }
    if (hasNonBlankString(props, "integrationOperationId")
        || hasNonBlankString(props, "integrationSystemId")) {
      return true;
    }
    return hasNonBlankString(props, "integrationSpecificationId");
  }

  /** True when plan carries a real catalog operation id (not placeholder). */
  public static boolean hasNonBlankIntegrationOperationId(Map<String, Object> props) {
    if (props == null) {
      return false;
    }
    Object v = props.get("integrationOperationId");
    if (v == null) {
      return false;
    }
    String s = String.valueOf(v).trim();
    return !s.isEmpty() && !looksLikePlaceholderOperationId(s);
  }

  /** Catalog operation ids are stable strings; model placeholders must not count as bound. */
  public static boolean looksLikePlaceholderOperationId(String operationId) {
    if (operationId == null || operationId.isBlank()) {
      return true;
    }
    String lower = operationId.toLowerCase();
    return lower.contains("placeholder")
        || lower.contains("todo")
        || lower.contains("tbd")
        || lower.contains("replace with")
        || lower.contains("actual operation")
        || "operation-id".equals(lower)
        || "operation-id-placeholder".equals(lower);
  }

  private static boolean hasNonBlankIntegrationGqlQuery(Map<String, Object> props) {
    return hasNonBlankString(props, "integrationGqlQuery");
  }

  private static boolean hasNonBlankString(Map<String, Object> props, String key) {
    Object v = props.get(key);
    if (v == null) {
      return false;
    }
    String s = String.valueOf(v).trim();
    return !s.isEmpty();
  }
}
