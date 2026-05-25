package org.qubership.integration.platform.ai.chat.chainplan;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;

import java.time.Instant;
import java.util.List;

/**
 * Active structured chain plan attached to a conversation (working state).
 *
 * @param planId stable id for this revision (new UUID each capture)
 * @param apiHubRequired when set from plan JSON, remembers APIHub policy for later turns
 * @param rejectionErrors validation errors when this revision was rejected at capture; empty for
 *     accepted plans
 * @param openItems unresolved plan / property / patch items for prompts, HITL, and verify-in-UI
 *     reporting
 */
public record ActiveChainPlanSnapshot(
    String planId,
    String chainName,
    Boolean apiHubRequired,
    String apiHubReason,
    ChainImplementationPlan plan,
    Instant updatedAt,
    List<String> rejectionErrors,
    List<PlanOpenItem> openItems) {

  public ActiveChainPlanSnapshot {
    rejectionErrors = rejectionErrors == null ? List.of() : List.copyOf(rejectionErrors);
    openItems = openItems == null ? List.of() : List.copyOf(openItems);
  }
}
