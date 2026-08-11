package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Per-conversation cache of the latest importable API Hub hit from gather tools. Backfill only:
 * used when the agent finds an operation via {@code searchApiOperations} /
 * {@code getApiOperationSpecification} but forgets to pass {@code apiHubCandidate} on
 * {@code captureRequirementDraft}. Not the source of truth for chat import (ADR 0001 decision 1);
 * {@link org.qubership.integration.platform.ai.plan.RequirementDraft#apiHubCandidate()} is.
 */
@ApplicationScoped
public class ConversationApiHubCache {

  private final ConcurrentHashMap<String, ApiHubRequirementRefs> byConversation =
      new ConcurrentHashMap<>();

  public void rememberCandidate(String conversationId, ApiHubRequirementRefs refs) {
    String cid = CatalogStrings.blankToNull(conversationId);
    if (cid == null || refs == null || !refs.hasImportableRefs()) {
      return;
    }
    byConversation.put(cid, refs);
  }

  public Optional<ApiHubRequirementRefs> latestCandidate(String conversationId) {
    String cid = CatalogStrings.blankToNull(conversationId);
    if (cid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byConversation.get(cid)).filter(ApiHubRequirementRefs::hasImportableRefs);
  }

  public void clear(String conversationId) {
    String cid = CatalogStrings.blankToNull(conversationId);
    if (cid != null) {
      byConversation.remove(cid);
    }
  }
}
