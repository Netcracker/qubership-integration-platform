package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AsyncApiTriggerBindingApplicator implements OperationBindingApplicator {

  private static final Set<String> KAFKA_ONLY_KEYS = Set.of("reconnectDelay");
  private static final Set<String> AMQP_ONLY_KEYS = Set.of("integrationOperationAsyncProperties");

  @Override
  public boolean supports(String elementType) {
    return "async-api-trigger".equalsIgnoreCase(CatalogStrings.blankToNull(elementType));
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
    stripStaleProtocolKeys(props, resolved.protocol());
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
      return;
    }
    OperationBindingCore.stripGraphqlOnlyKeys(merged, protocol);
    stripStaleProtocolKeys(merged, protocol);
    validateAsyncBindingComplete(merged);
  }

  static void stripStaleProtocolKeys(Map<String, Object> props, String protocol) {
    String p = protocol != null ? protocol.toLowerCase() : "";
    if ("kafka".equals(p)) {
      for (String key : AMQP_ONLY_KEYS) {
        props.remove(key);
      }
    } else if ("amqp".equals(p)) {
      for (String key : KAFKA_ONLY_KEYS) {
        props.remove(key);
      }
    }
  }

  static void validateAsyncBindingComplete(Map<String, Object> merged) {
    String operationId =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.INTEGRATION_OPERATION_ID);
    if (operationId == null) {
      return;
    }
    String method =
        OperationBindingProps.stringProp(merged, OperationBindingKeys.INTEGRATION_OPERATION_METHOD);
    if (method == null) {
      throw new IllegalArgumentException(
          "Operation binding incomplete after merge: integrationOperationMethod is required for"
              + " async-api-trigger with integrationOperationId="
              + operationId);
    }
  }
}
