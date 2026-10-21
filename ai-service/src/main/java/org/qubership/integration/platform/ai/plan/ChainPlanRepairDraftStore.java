package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** In-memory store for invalid plan drafts that are being repaired. */
@ApplicationScoped
public class ChainPlanRepairDraftStore {

  private final ConcurrentHashMap<String, ChainPlanGraph> drafts = new ConcurrentHashMap<>();

  public void put(String conversationId, ChainPlanGraph graph) {
    if (conversationId == null || conversationId.isBlank() || graph == null) {
      return;
    }
    drafts.put(conversationId, graph);
  }

  public Optional<ChainPlanGraph> get(String conversationId) {
    return Optional.ofNullable(drafts.get(conversationId));
  }

  public void remove(String conversationId) {
    if (conversationId != null) {
      drafts.remove(conversationId);
    }
  }
}
