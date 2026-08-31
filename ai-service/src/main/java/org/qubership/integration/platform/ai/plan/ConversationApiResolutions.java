package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * API-resolution assessments produced during requirement gathering, kept per conversation.
 *
 * <p>A chain that resolves several interactions does so on its own turns. Keying by {@code
 * interactionId} lets a later stage tell which interaction an answer belongs to, so an unresolved
 * interaction cannot borrow the binding of a resolved one, and a resolved interaction is never
 * searched for again.
 *
 * <p>Re-resolving the same interaction replaces its assessment: the newest answer is the only one
 * that counts. This map is process memory only; the requirement draft is the durable source of
 * truth.
 */
@ApplicationScoped
public class ConversationApiResolutions {

  private final Map<String, Map<String, InteractionAssessment>> byConversation =
      new ConcurrentHashMap<>();

  public void remember(String conversationId, InteractionAssessment assessment) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null || assessment == null) {
      return;
    }
    byConversation
        .computeIfAbsent(id, ignored -> Collections.synchronizedMap(new LinkedHashMap<>()))
        .put(assessment.interactionId(), assessment);
  }

  /** Every assessment this conversation produced, in insertion order of the interactions. */
  public List<InteractionAssessment> assessments(String conversationId) {
    return List.copyOf(byInteraction(conversationId).values());
  }

  public Optional<InteractionAssessment> forInteraction(
      String conversationId, String interactionId) {
    String id = CatalogStrings.blankToNull(interactionId);
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byInteraction(conversationId).get(id));
  }

  /** Drops assessments whose interaction ids are no longer in the captured draft. */
  public void retainInteractions(String conversationId, Set<String> interactionIds) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return;
    }
    Map<String, InteractionAssessment> assessments = byConversation.get(id);
    if (assessments == null) {
      return;
    }
    Set<String> keep = interactionIds == null ? Set.of() : interactionIds;
    synchronized (assessments) {
      assessments.keySet().removeIf(key -> !keep.contains(key));
      if (assessments.isEmpty()) {
        byConversation.remove(id, assessments);
      }
    }
  }

  /** Catalog matches this conversation resolved, newest per operation. */
  public List<CatalogMatch> resolved(String conversationId) {
    return byInteraction(conversationId).values().stream()
        .filter(InteractionAssessment::isResolved)
        .map(InteractionAssessment::binding)
        .toList();
  }

  public void clear(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id != null) {
      byConversation.remove(id);
    }
  }

  private Map<String, InteractionAssessment> byInteraction(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return Map.of();
    }
    Map<String, InteractionAssessment> assessments = byConversation.get(id);
    if (assessments == null) {
      return Map.of();
    }
    synchronized (assessments) {
      return new LinkedHashMap<>(assessments);
    }
  }
}
