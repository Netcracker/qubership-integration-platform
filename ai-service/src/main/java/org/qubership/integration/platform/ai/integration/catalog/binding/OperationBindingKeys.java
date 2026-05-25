package org.qubership.integration.platform.ai.integration.catalog.binding;

import java.util.Set;

/** Property keys for catalog operation binding (aligned with UI SystemOperationField). */
public final class OperationBindingKeys {

  public static final String INTEGRATION_SYSTEM_ID = "integrationSystemId";
  public static final String INTEGRATION_SPECIFICATION_GROUP_ID =
      "integrationSpecificationGroupId";
  public static final String INTEGRATION_SPECIFICATION_ID = "integrationSpecificationId";
  public static final String INTEGRATION_OPERATION_ID = "integrationOperationId";
  public static final String INTEGRATION_OPERATION_PATH = "integrationOperationPath";
  public static final String INTEGRATION_OPERATION_METHOD = "integrationOperationMethod";
  public static final String INTEGRATION_OPERATION_PROTOCOL_TYPE =
      "integrationOperationProtocolType";
  public static final String SYSTEM_TYPE = "systemType";
  public static final String HTTP_METHOD_RESTRICT = "httpMethodRestrict";
  public static final String CONTEXT_PATH = "contextPath";

  public static final Set<String> GRAPHQL_ONLY_KEYS =
      Set.of(
          "integrationGqlQueryHeader",
          "integrationGqlVariablesHeader",
          "integrationGqlQuery",
          "integrationGqlOperationName",
          "integrationGqlVariablesJSON");

  /** http-trigger custom endpoint keys cleared when switching to implemented service. */
  public static final Set<String> HTTP_TRIGGER_CUSTOM_ENDPOINT_KEYS = Set.of(CONTEXT_PATH);

  private OperationBindingKeys() {}
}
