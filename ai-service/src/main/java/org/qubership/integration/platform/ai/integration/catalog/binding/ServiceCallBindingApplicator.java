package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Map;

@ApplicationScoped
public class ServiceCallBindingApplicator implements OperationBindingApplicator {

  @Override
  public boolean supports(String elementType) {
    return "service-call".equalsIgnoreCase(CatalogStrings.blankToNull(elementType));
  }

  @Override
  public boolean shouldEnrich(ElementPlan node) {
    return node != null
        && supports(node.getType())
        && PlanServiceBindingRules.requiresOperationBinding(node);
  }

  @Override
  public void applyResolved(
      ElementPlan node,
      OperationBindingResolved resolved,
      String specIdFromPlan,
      Map<String, Object> props) {
    OperationBindingCore.applyCanonicalFields(props, resolved, specIdFromPlan);
    OperationBindingCore.stripGraphqlOnlyKeys(props, resolved.protocol());
  }

  @Override
  public void normalizeMergedProperties(String elementType, Map<String, Object> merged) {
    if (!supports(elementType)) {
      return;
    }
    String protocol =
        OperationBindingProps.stringProp(
            merged, OperationBindingKeys.INTEGRATION_OPERATION_PROTOCOL_TYPE);
    if (protocol == null) {
      protocol = "http";
    }
    OperationBindingCore.stripGraphqlOnlyKeys(merged, protocol);
    validateServiceCallBindingComplete(merged);
  }

  static void validateServiceCallBindingComplete(Map<String, Object> merged) {
    String operationId =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.INTEGRATION_OPERATION_ID);
    if (operationId == null) {
      return;
    }
    String protocol =
        OperationBindingProps.stringProp(
            merged, OperationBindingKeys.INTEGRATION_OPERATION_PROTOCOL_TYPE);
    if (protocol != null && !"graphql".equalsIgnoreCase(protocol)) {
      String method =
          OperationBindingProps.stringProp(
              merged, OperationBindingKeys.INTEGRATION_OPERATION_METHOD);
      if (method == null) {
        throw new IllegalArgumentException(
            "Operation binding incomplete after merge: integrationOperationMethod is required for"
                + " service-call with integrationOperationId="
                + operationId
                + "; ensure listCatalogOperations ran during CREATE_CHAIN_PLAN");
      }
    }
  }
}
