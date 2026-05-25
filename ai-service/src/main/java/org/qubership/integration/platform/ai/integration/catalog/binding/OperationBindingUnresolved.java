package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;

/** User-visible reasons when catalog operation binding cannot be enriched. */
public final class OperationBindingUnresolved {

  private OperationBindingUnresolved() {}

  public static String reason(String operationId, String specificationId) {
    if (PlanServiceBindingRules.looksLikePlaceholderOperationId(operationId)) {
      return "integrationOperationId is a placeholder ("
          + operationId
          + "); fix binding in CREATE_CHAIN_PLAN (catalog tools)";
    }
    return "integrationOperationId \""
        + operationId
        + "\" not found in catalog; resolve via catalog tools in CREATE_CHAIN_PLAN or set"
        + " bindingStatus user_accepted_unbound";
  }
}
