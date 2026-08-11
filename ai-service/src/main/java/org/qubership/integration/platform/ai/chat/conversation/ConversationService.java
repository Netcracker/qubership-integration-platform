package org.qubership.integration.platform.ai.chat.conversation;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.attachment.AttachmentKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConversationService {

  private final ConcurrentHashMap<String, ConversationState> conversations =
      new ConcurrentHashMap<>();

  private static final class ConversationState {
    final List<ConversationMessage> messages = new ArrayList<>();
    final Set<String> allowedAttachmentKeys = ConcurrentHashMap.newKeySet();
    final Set<String> materializedAttachmentKeys = ConcurrentHashMap.newKeySet();
  }

  public void getOrCreate(String conversationId) {
    conversations.computeIfAbsent(conversationId, __ -> new ConversationState());
  }

  public void addMessage(String conversationId, ConversationMessage message) {
    ConversationState state =
        conversations.computeIfAbsent(conversationId, __ -> new ConversationState());
    synchronized (state.messages) {
      state.messages.add(message);
    }
  }

  public List<ConversationMessage> getMessages(String conversationId) {
    ConversationState state = conversations.get(conversationId);
    if (state == null) {
      return List.of();
    }
    synchronized (state.messages) {
      return new ArrayList<>(state.messages);
    }
  }

  /**
   * Keeps messages at indices {@code 0..afterMessageIndex} inclusive and drops the rest. When
   * {@code afterMessageIndex} is negative, clears all messages while keeping the conversation
   * entry.
   */
  public void truncateAfter(String conversationId, int afterMessageIndex) {
    ConversationState state = conversations.get(conversationId);
    if (state == null) {
      return;
    }
    synchronized (state.messages) {
      if (afterMessageIndex < 0) {
        state.messages.clear();
        return;
      }
      int keepCount = Math.min(afterMessageIndex + 1, state.messages.size());
      while (state.messages.size() > keepCount) {
        state.messages.remove(state.messages.size() - 1);
      }
    }
  }

  /** Clears the message list while keeping the conversation entry. */
  public void clearMessages(String conversationId) {
    ConversationState state = conversations.get(conversationId);
    if (state == null) {
      return;
    }
    synchronized (state.messages) {
      state.messages.clear();
    }
  }

  /** Registers S3 object keys referenced in this conversation (for future tooling / auditing). */
  public void registerAllowedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    ConversationState state =
        conversations.computeIfAbsent(conversationId, __ -> new ConversationState());
    for (String k : keys) {
      if (AttachmentKeys.isSafe(k)) {
        state.allowedAttachmentKeys.add(k);
      }
    }
  }

  public boolean isAttachmentKeyMaterialized(String conversationId, String key) {
    ConversationState state = conversations.get(conversationId);
    return state != null && key != null && state.materializedAttachmentKeys.contains(key);
  }

  public void addMaterializedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    ConversationState state = conversations.get(conversationId);
    if (state == null) {
      return;
    }
    for (String k : keys) {
      if (AttachmentKeys.isSafe(k)) {
        state.materializedAttachmentKeys.add(k);
      }
    }
  }

  /** S3 object keys registered for this conversation (uploads / prior turns). */
  public List<String> getAllowedAttachmentKeys(String conversationId) {
    ConversationState state = conversations.get(conversationId);
    if (state == null || state.allowedAttachmentKeys.isEmpty()) {
      return List.of();
    }
    return state.allowedAttachmentKeys.stream()
        .sorted()
        .collect(Collectors.toList());
  }
}
