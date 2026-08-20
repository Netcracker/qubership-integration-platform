package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;

/**
 * Catalog operations bound during requirement gathering, kept per conversation.
 *
 * <p>A chain that calls several services resolves each one on its own turn, while the draft holds a
 * single binding and cannot say which call it belongs to. Recording every resolution as it happens
 * keeps a later stage from asking the catalog — or APIHub — about an operation this conversation
 * has already identified.
 *
 * <p>A repeat resolution of the same operation replaces the earlier one, so the list holds the
 * newest answer for each operation and nothing else.
 */
@ApplicationScoped
public class ConversationCatalogBindings {

  private final Map<String, List<CatalogBindingMatcher.CatalogMatch>> byConversation =
      new ConcurrentHashMap<>();

  public void remember(String conversationId, CatalogBindingMatcher.CatalogMatch match) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null || match == null || CatalogStrings.blankToNull(match.integrationOperationId()) == null) {
      return;
    }
    List<CatalogBindingMatcher.CatalogMatch> resolved =
        byConversation.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>());
    resolved.removeIf(
        known -> Objects.equals(known.integrationOperationId(), match.integrationOperationId()));
    resolved.add(match);
  }

  public List<CatalogBindingMatcher.CatalogMatch> resolved(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return List.of();
    }
    return List.copyOf(byConversation.getOrDefault(id, List.of()));
  }

  public void clear(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id != null) {
      byConversation.remove(id);
    }
  }
}
