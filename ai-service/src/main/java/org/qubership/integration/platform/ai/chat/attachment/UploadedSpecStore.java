package org.qubership.integration.platform.ai.chat.attachment;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UploadedSpecStore {

  private final ConcurrentHashMap<String, List<UploadedSpecEntry>> byConversation =
      new ConcurrentHashMap<>();

  public void register(String conversationId, UploadedSpecEntry entry) {
    if (conversationId == null || conversationId.isBlank() || entry == null) {
      return;
    }
    byConversation
        .computeIfAbsent(conversationId, ignored -> Collections.synchronizedList(new ArrayList<>()))
        .add(entry);
  }

  public List<UploadedSpecEntry> getAll(String conversationId) {
    List<UploadedSpecEntry> entries = byConversation.get(conversationId);
    if (entries == null) {
      return List.of();
    }
    synchronized (entries) {
      return List.copyOf(entries);
    }
  }

  public UploadedSpecEntry findByKey(String conversationId, String s3Key) {
    if (s3Key == null) {
      return null;
    }
    return getAll(conversationId).stream()
        .filter(entry -> s3Key.equals(entry.s3Key()))
        .findFirst()
        .orElse(null);
  }
}
