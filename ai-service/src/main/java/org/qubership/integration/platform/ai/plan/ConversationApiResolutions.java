package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;

/**
 * API-resolution assessments produced during requirement gathering, kept per conversation.
 *
 * <p>A chain that calls several services resolves each call on its own turn. Keying by source fact
 * lets a later stage tell which call an answer belongs to, so an unresolved call cannot borrow the
 * binding of a resolved one, and a resolved call is never searched for again.
 *
 * <p>Re-resolving the same fact replaces its assessment: the newest answer is the only one that
 * counts.
 */
@ApplicationScoped
public class ConversationApiResolutions {

  private final Map<String, Map<String, ServiceCallAssessment>> byConversation =
      new ConcurrentHashMap<>();

  public void remember(String conversationId, ServiceCallAssessment assessment) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null || assessment == null) {
      return;
    }
    byConversation
        .computeIfAbsent(id, ignored -> Collections.synchronizedMap(new LinkedHashMap<>()))
        .put(assessment.sourceFactId(), assessment);
  }

  /** Every assessment this conversation produced, in insertion order of the source facts. */
  public List<ServiceCallAssessment> assessments(String conversationId) {
    return List.copyOf(byFact(conversationId).values());
  }

  public Optional<ServiceCallAssessment> forFact(String conversationId, String sourceFactId) {
    String factId = CatalogStrings.blankToNull(sourceFactId);
    if (factId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byFact(conversationId).get(factId));
  }

  /** Catalog matches this conversation resolved, newest per operation. */
  public List<CatalogBindingMatcher.CatalogMatch> resolved(String conversationId) {
    return byFact(conversationId).values().stream()
        .filter(ServiceCallAssessment::isResolved)
        .map(ServiceCallAssessment::binding)
        .toList();
  }

  public void clear(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id != null) {
      byConversation.remove(id);
    }
  }

  private Map<String, ServiceCallAssessment> byFact(String conversationId) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return Map.of();
    }
    Map<String, ServiceCallAssessment> assessments = byConversation.get(id);
    if (assessments == null) {
      return Map.of();
    }
    synchronized (assessments) {
      return new LinkedHashMap<>(assessments);
    }
  }
}
