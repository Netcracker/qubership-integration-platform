package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Map;

/** Implemented Service Endpoint branch only (not Custom Endpoint). */
@ApplicationScoped
public class HttpTriggerBindingApplicator implements OperationBindingApplicator {

  @Override
  public boolean supports(String elementType) {
    return "http-trigger".equalsIgnoreCase(CatalogStrings.blankToNull(elementType));
  }

  @Override
  public boolean shouldEnrich(ElementPlan node) {
    return node != null
        && supports(node.getType())
        && PlanServiceBindingRules.looksLikeImplementedHttpEndpoint(node.getExpectedProperties());
  }

  @Override
  public void applyResolved(
      ElementPlan node,
      OperationBindingResolved resolved,
      String specIdFromPlan,
      Map<String, Object> props) {
    OperationBindingCore.applyCanonicalFields(props, resolved, specIdFromPlan);
    String method =
        OperationBindingProps.stringProp(props, OperationBindingKeys.INTEGRATION_OPERATION_METHOD);
    if (method != null) {
      props.put(OperationBindingKeys.HTTP_METHOD_RESTRICT, method);
    }
    stripHttpTriggerUnsupportedKeys(props, resolved.protocol());
  }

  @Override
  public void normalizeMergedProperties(String elementType, Map<String, Object> merged) {
    if (!supports(elementType)) {
      return;
    }
    if (!PlanServiceBindingRules.looksLikeImplementedHttpEndpoint(merged)) {
      return;
    }
    String protocol =
        OperationBindingProps.stringProp(
            merged, OperationBindingKeys.INTEGRATION_OPERATION_PROTOCOL_TYPE);
    if (protocol == null) {
      protocol = "http";
    }
    stripHttpTriggerUnsupportedKeys(merged, protocol);
    validateImplementedHttpTriggerComplete(merged);
  }

  /** http-trigger schema has no integrationOperationProtocolType; method maps to httpMethodRestrict. */
  private static void stripHttpTriggerUnsupportedKeys(Map<String, Object> props, String protocol) {
    OperationBindingCore.stripGraphqlOnlyKeys(props, protocol);
    props.remove(OperationBindingKeys.INTEGRATION_OPERATION_PROTOCOL_TYPE);
    String method =
        OperationBindingProps.stringProp(props, OperationBindingKeys.INTEGRATION_OPERATION_METHOD);
    if (method != null) {
      props.put(OperationBindingKeys.HTTP_METHOD_RESTRICT, method);
    }
    props.remove(OperationBindingKeys.INTEGRATION_OPERATION_METHOD);
    for (String key : OperationBindingKeys.HTTP_TRIGGER_CUSTOM_ENDPOINT_KEYS) {
      props.remove(key);
    }
  }

  static void validateImplementedHttpTriggerComplete(Map<String, Object> merged) {
    String operationId =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.INTEGRATION_OPERATION_ID);
    if (operationId == null) {
      return;
    }
    String httpMethod =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.HTTP_METHOD_RESTRICT);
    String path =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.INTEGRATION_OPERATION_PATH);
    if (httpMethod == null && path == null) {
      throw new IllegalArgumentException(
          "Operation binding incomplete after merge: http-trigger implemented endpoint requires"
              + " httpMethodRestrict and/or integrationOperationPath for integrationOperationId="
              + operationId);
    }
  }
}
