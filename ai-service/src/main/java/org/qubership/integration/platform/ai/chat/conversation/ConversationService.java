package org.qubership.integration.platform.ai.chat.conversation;

import dev.langchain4j.data.message.ChatMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.memory.ChatMessageMapper;
import org.qubership.integration.platform.ai.chat.memory.ChatMessageMapper.StoredMessageDraft;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages conversation lifecycle: creation, message persistence, and history loading. History is
 * stored in memory and is lost when the process restarts.
 */
@ApplicationScoped
public class ConversationService {

  @Inject AppConfig config;

  private final ConcurrentHashMap<String, ConversationState> conversations =
      new ConcurrentHashMap<>();
  private final AtomicLong messageIdSeq = new AtomicLong();

  private static final class ConversationState {
    ScenarioType scenarioType;
    Instant createdAt;
    Instant updatedAt;
    final List<StoredMessage> messages = new ArrayList<>();

    /** S3 object keys the client referenced in this conversation (uploads / legacy URLs). */
    final Set<String> allowedAttachmentKeys = ConcurrentHashMap.newKeySet();

    /**
     * Keys already inlined in a previous user turn (deduplication for follow-up with same file).
     */
    final Set<String> materializedAttachmentKeys = ConcurrentHashMap.newKeySet();
  }

  private static final class StoredMessage {
    long id;
    ConversationMessage.Role role;
    String content;
    Instant createdAt;
  }

  public void getOrCreate(String conversationId, ScenarioType scenarioType) {
    conversations.compute(
        conversationId,
        (id, existing) -> {
          if (existing != null) {
            return existing;
          }
          ConversationState s = new ConversationState();
          s.scenarioType = scenarioType;
          s.createdAt = Instant.now();
          s.updatedAt = Instant.now();
          return s;
        });
  }

  /** Registers S3 object keys referenced in this conversation (for future tooling / auditing). */
  public void registerAllowedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    ConversationState conv = conversations.get(conversationId);
    if (conv == null) {
      return;
    }
    for (String k : keys) {
      if (k != null && !k.isBlank() && isSafeObjectKeyForStorage(k)) {
        conv.allowedAttachmentKeys.add(k);
      }
    }
  }

  public boolean isAttachmentKeyMaterialized(String conversationId, String key) {
    ConversationState conv = conversations.get(conversationId);
    return conv != null && key != null && conv.materializedAttachmentKeys.contains(key);
  }

  public void addMaterializedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    ConversationState conv = conversations.get(conversationId);
    if (conv == null) {
      return;
    }
    for (String k : keys) {
      if (k != null && isSafeObjectKeyForStorage(k)) {
        conv.materializedAttachmentKeys.add(k);
      }
    }
  }

  /**
   * S3 object keys registered for this conversation (uploads / prior turns). Safe keys only, stable
   * order.
   */
  public List<String> getAllowedAttachmentKeys(String conversationId) {
    ConversationState conv = conversations.get(conversationId);
    if (conv == null || conv.allowedAttachmentKeys.isEmpty()) {
      return List.of();
    }
    return conv.allowedAttachmentKeys.stream()
        .filter(ConversationService::isSafeObjectKeyForStorage)
        .sorted()
        .collect(Collectors.toList());
  }

  private static boolean isSafeObjectKeyForStorage(String key) {
    for (String segment : key.split("/")) {
      if ("..".equals(segment)) {
        return false;
      }
    }
    return true;
  }

  public void addMessage(String conversationId, ConversationMessage.Role role, String content) {
    ConversationState conversation = conversations.get(conversationId);
    if (conversation == null) {
      throw new IllegalArgumentException("Conversation not found: " + conversationId);
    }
    synchronized (conversation.messages) {
      if (isDuplicateTail(conversation, role, content)) {
        return;
      }
      appendStored(conversation, role, content);
    }
  }

  /** Replaces stored messages from LangChain4j memory updates (authoritative for agent turns). */
  public void replaceFromChatMessages(String conversationId, List<ChatMessage> messages) {
    ConversationState conversation = conversations.get(conversationId);
    if (conversation == null) {
      throw new IllegalArgumentException("Conversation not found: " + conversationId);
    }
    List<StoredMessageDraft> drafts = ChatMessageMapper.toStoredDrafts(messages);
    synchronized (conversation.messages) {
      conversation.messages.clear();
      int max = config.conversation().maxMessages();
      int start = Math.max(0, drafts.size() - max);
      for (int i = start; i < drafts.size(); i++) {
        StoredMessageDraft draft = drafts.get(i);
        appendStored(conversation, draft.role(), draft.content());
      }
    }
  }

  public void clearMessages(String conversationId) {
    ConversationState conversation = conversations.get(conversationId);
    if (conversation == null) {
      return;
    }
    synchronized (conversation.messages) {
      conversation.messages.clear();
      conversation.updatedAt = Instant.now();
    }
  }

  private void appendStored(
      ConversationState conversation, ConversationMessage.Role role, String content) {
    int max = config.conversation().maxMessages();
    while (conversation.messages.size() >= max && !conversation.messages.isEmpty()) {
      conversation.messages.remove(0);
    }
    StoredMessage msg = new StoredMessage();
    msg.id = messageIdSeq.incrementAndGet();
    msg.role = role;
    msg.content = content;
    msg.createdAt = Instant.now();
    conversation.messages.add(msg);
    conversation.updatedAt = Instant.now();
  }

  private static boolean isDuplicateTail(
      ConversationState conversation, ConversationMessage.Role role, String content) {
    if (conversation.messages.isEmpty()) {
      return false;
    }
    StoredMessage last = conversation.messages.get(conversation.messages.size() - 1);
    return last.role == role && Objects.equals(last.content, content);
  }

  /** Returns conversation history as LangChain4j ChatMessage list for use in AI service calls. */
  public List<ChatMessage> getHistory(String conversationId) {
    ConversationState conv = conversations.get(conversationId);
    if (conv == null) {
      return List.of();
    }
    List<StoredMessage> snapshot;
    synchronized (conv.messages) {
      snapshot = new ArrayList<>(conv.messages);
    }
    return snapshot.stream().map(this::toChatMessage).collect(Collectors.toList());
  }

  /** Returns conversation history as lightweight DTOs. */
  public List<ConversationMessage> getMessages(String conversationId) {
    ConversationState conv = conversations.get(conversationId);
    if (conv == null) {
      return List.of();
    }
    List<StoredMessage> snapshot;
    synchronized (conv.messages) {
      snapshot = new ArrayList<>(conv.messages);
    }
    return snapshot.stream()
        .map(
            e -> {
              ConversationMessage msg = new ConversationMessage();
              msg.setId(e.id);
              msg.setConversationId(conversationId);
              msg.setRole(e.role);
              msg.setContent(e.content);
              msg.setCreatedAt(e.createdAt);
              return msg;
            })
        .collect(Collectors.toList());
  }

  /**
   * Builds a compact transcript for LLM prompts (guardrail, router): labeled lines "User:",
   * "Assistant:", "System:" with per-message truncation.
   *
   * @param whenEmpty returned when there is no conversation id or no messages
   */
  public String formatRecentTranscript(
      String conversationId, int maxMessages, int maxCharsPerMessage, String whenEmpty) {
    return formatRecentTranscriptBalanced(
        conversationId, maxMessages, maxCharsPerMessage, Integer.MAX_VALUE, 1, whenEmpty);
  }

  /**
   * Like {@link #formatRecentTranscript} but keeps both head and tail of long messages and may drop
   * older labeled turns to satisfy {@code maxTotalChars}.
   */
  public String formatRecentTranscriptBalanced(
      String conversationId,
      int maxMessages,
      int maxCharsPerMessage,
      int maxTotalChars,
      int minimumTailMessages,
      String whenEmpty) {
    if (conversationId == null || conversationId.isBlank()) {
      return whenEmpty;
    }
    List<ConversationMessage> messages = getMessages(conversationId);
    if (messages == null || messages.isEmpty()) {
      return whenEmpty;
    }
    int windowStart = Math.max(0, messages.size() - maxMessages);
    List<String> labeledChunks = new ArrayList<>();
    for (int i = windowStart; i < messages.size(); i++) {
      ConversationMessage m = messages.get(i);
      String label =
          switch (m.getRole()) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
          };
      String body = TranscriptBalancing.truncateBalanced(m.getContent(), maxCharsPerMessage);
      labeledChunks.add(label + ": " + body + "\n\n");
    }
    int start =
        TranscriptBalancing.trimStartIndexForBudget(
            labeledChunks, maxTotalChars, Math.max(1, minimumTailMessages));
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < labeledChunks.size(); i++) {
      sb.append(labeledChunks.get(i));
    }
    String out = sb.toString().trim();
    if (out.isEmpty()) {
      return whenEmpty;
    }
    if (out.length() > maxTotalChars) {
      out = TranscriptBalancing.truncateBalanced(out, maxTotalChars);
    }
    return out;
  }

  private ChatMessage toChatMessage(StoredMessage entity) {
    return ChatMessageMapper.toChatMessage(entity.role, entity.content);
  }
}
