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
 * <p>A chain that calls several services resolves each call on its own turn. Keying by {@code
 * serviceCallId} lets a later stage tell which call an answer belongs to, so an unresolved call
 * cannot borrow the binding of a resolved one, and a resolved call is never searched for again.
 *
 * <p>Re-resolving the same call replaces its assessment: the newest answer is the only one that
 * counts. This map is process memory only; the requirement draft is the durable source of truth.
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
        .put(assessment.serviceCallId(), assessment);
  }

  /** Every assessment this conversation produced, in insertion order of the service calls. */
  public List<ServiceCallAssessment> assessments(String conversationId) {
    return List.copyOf(byServiceCall(conversationId).values());
  }

  public Optional<ServiceCallAssessment> forServiceCall(
      String conversationId, String serviceCallId) {
    String callId = CatalogStrings.blankToNull(serviceCallId);
    if (callId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byServiceCall(conversationId).get(callId));
  }

  /** Drops assessments whose service-call ids are no longer in the captured draft. */
  public void retainServiceCalls(String conversationId, Set<String> serviceCallIds) {
    String id = CatalogStrings.blankToNull(conversationId);
    if (id == null) {
      return;
    }
    Map<String, ServiceCallAssessment> assessments = byConversation.get(id);
    if (assessments == null) {
      return;
    }
    Set<String> keep = serviceCallIds == null ? Set.of() : serviceCallIds;
    synchronized (assessments) {
      assessments.keySet().removeIf(key -> !keep.contains(key));
      if (assessments.isEmpty()) {
        byConversation.remove(id, assessments);
      }
    }
  }

  /** Catalog matches this conversation resolved, newest per operation. */
  public List<CatalogMatch> resolved(String conversationId) {
    return byServiceCall(conversationId).values().stream()
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

  private Map<String, ServiceCallAssessment> byServiceCall(String conversationId) {
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
