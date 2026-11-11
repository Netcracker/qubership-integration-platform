package org.qubership.integration.platform.ai.chain.deploy;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.chain.deploy.MaasKafkaTopicsFollowUp.TopicPair;

/**
 * Remembers Kafka MaaS topics created during a deploy conversation so refresh/status turns do not
 * re-offer classifiers the reader already created while runtime errors are still stale.
 */
@ApplicationScoped
public class MaasTopicsCreatedStore {

  private final ConcurrentHashMap<String, List<TopicPair>> createdByConversation =
      new ConcurrentHashMap<>();

  public void remember(String conversationId, List<TopicPair> created) {
    Objects.requireNonNull(conversationId, "conversationId");
    if (created == null || created.isEmpty()) {
      return;
    }
    createdByConversation.merge(
        conversationId,
        List.copyOf(created),
        (left, right) -> {
          LinkedHashSet<TopicPair> merged = new LinkedHashSet<>(left);
          merged.addAll(right);
          return List.copyOf(merged);
        });
  }

  public List<TopicPair> find(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    List<TopicPair> listed = createdByConversation.get(conversationId);
    return listed == null ? List.of() : listed;
  }

  public void clear(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    createdByConversation.remove(conversationId);
  }
}
