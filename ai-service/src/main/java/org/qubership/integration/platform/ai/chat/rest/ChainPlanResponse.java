package org.qubership.integration.platform.ai.chat.rest;

import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItem;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;

import java.util.List;

/** REST payload for the active {@link ChainImplementationPlan} snapshot (read-only). */
public record ChainPlanResponse(
    String planId,
    boolean approved,
    String chainName,
    Boolean apiHubRequired,
    String apiHubReason,
    ChainImplementationPlan plan,
    List<PlanOpenItem> openItems) {

  public ChainPlanResponse {
    openItems = openItems == null ? List.of() : List.copyOf(openItems);
  }
}
