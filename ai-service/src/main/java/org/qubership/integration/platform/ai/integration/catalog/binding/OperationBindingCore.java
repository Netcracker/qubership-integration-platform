package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Map;

/**
 * Fills canonical operation binding fields from a resolved catalog operation (UI
 * SystemOperationField).
 */
public final class OperationBindingCore {

  private OperationBindingCore() {}

  /**
   * Merges resolved operation metadata into {@code props} (mutates copy semantics — caller owns
   * map).
   */
  public static void applyCanonicalFields(
      Map<String, Object> props, OperationBindingResolved resolved, String specIdFromPlan) {
    CatalogRestClient.OperationDto op = resolved.operation();
    if (resolved.systemId() != null) {
      props.put(OperationBindingKeys.INTEGRATION_SYSTEM_ID, resolved.systemId());
    }
    String catalogSpecId = CatalogStrings.blankToNull(op.modelId());
    String planSpecId = CatalogStrings.blankToNull(specIdFromPlan);
    String specificationId = catalogSpecId != null ? catalogSpecId : planSpecId;
    if (specificationId != null) {
      props.put(OperationBindingKeys.INTEGRATION_SPECIFICATION_ID, specificationId);
    }
    if (resolved.specificationGroupId() != null && !resolved.specificationGroupId().isBlank()) {
      props.put(
          OperationBindingKeys.INTEGRATION_SPECIFICATION_GROUP_ID,
          resolved.specificationGroupId());
    }
    props.put(OperationBindingKeys.INTEGRATION_OPERATION_ID, op.id());
    if (op.path() != null && !op.path().isBlank()) {
      props.put(OperationBindingKeys.INTEGRATION_OPERATION_PATH, op.path());
    }
    if (op.method() != null && !op.method().isBlank()) {
      props.put(OperationBindingKeys.INTEGRATION_OPERATION_METHOD, op.method());
    }
    props.put(OperationBindingKeys.INTEGRATION_OPERATION_PROTOCOL_TYPE, resolved.protocol());
    resolved
        .system()
        .map(CatalogRestClient.SystemDto::type)
        .filter(t -> !t.isBlank())
        .ifPresent(t -> props.put(OperationBindingKeys.SYSTEM_TYPE, t));
  }

  public static void stripGraphqlOnlyKeys(Map<String, Object> props, String protocol) {
    if ("graphql".equalsIgnoreCase(protocol)) {
      return;
    }
    for (String key : OperationBindingKeys.GRAPHQL_ONLY_KEYS) {
      props.remove(key);
    }
  }
}
