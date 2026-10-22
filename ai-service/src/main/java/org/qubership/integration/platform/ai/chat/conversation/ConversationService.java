package org.qubership.integration.platform.ai.chat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.qubership.integration.platform.ai.chat.attachment.AttachmentKeys;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Turn;

@ApplicationScoped
public class ConversationService {
  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(1);
  private final Cache<String, Document> conversations;
  private final ConversationArchive archive;

  public ConversationService() {
    this(DEFAULT_IDLE_TIMEOUT, Ticker.systemTicker());
  }

  @Inject
  public ConversationService(AppConfig appConfig, ConversationArchive archive) {
    this(appConfig.conversation().idleTimeout(), Ticker.systemTicker(), archive);
  }

  ConversationService(Duration idleTimeout, Ticker ticker) {
    this(idleTimeout, ticker, new ConversationArchive(new InMemoryArtifactBlobStore(),
        new ObjectMapper().findAndRegisterModules()));
  }

  ConversationService(Duration idleTimeout, Ticker ticker, ConversationArchive archive) {
    this.archive = archive;
    this.conversations = Caffeine.newBuilder().expireAfterAccess(idleTimeout).ticker(ticker).build();
  }

  public void getOrCreate(String conversationId) {
    conversations.get(conversationId, id -> archive.update(id, doc -> doc));
  }

  public ConversationArchive archive() {
    return archive;
  }

  public void addMessage(String conversationId, ConversationMessage message) {
    archive.update(conversationId, doc -> ConversationArchive.append(doc,
        new Turn(UUID.randomUUID().toString(), message,
            message.role() == ConversationMessage.Role.USER ? message.content() : null,
            null, "READY", null)));
    conversations.invalidate(conversationId);
  }

  public List<ConversationMessage> getMessages(String conversationId) {
    // Reload durable authority so another writer's committed turns are visible.
    Document document = archive.read(conversationId);
    conversations.put(conversationId, document);
    return document.activeTurns().stream().map(Turn::message).toList();
  }

  public record UserMessagePosition(int messageIndex, String content) {}

  public List<UserMessagePosition> getUserMessagePositions(String conversationId) {
    List<Turn> turns = archive.read(conversationId).activeTurns();
    List<UserMessagePosition> positions = new ArrayList<>();
    for (int i = 0; i < turns.size(); i++) {
      Turn turn = turns.get(i);
      if (turn.message().role() != ConversationMessage.Role.USER) {
        continue;
      }
      var submitted = turn.metadata();
      if (submitted != null && submitted.has("submitted")) {
        submitted = submitted.get("submitted");
      }
      if (submitted != null && submitted.hasNonNull("decision")) {
        continue;
      }
      positions.add(new UserMessagePosition(i,
          turn.rawText() == null ? turn.message().content() : turn.rawText()));
    }
    return positions;
  }

  /** Keeps the inclusive prefix and fences callbacks from the prior reset generation. */
  public void truncateAfter(String conversationId, int afterMessageIndex) {
    archive.update(conversationId, doc -> {
      List<String> active = doc.activeTurnIds().subList(0,
          Math.min(Math.max(0, afterMessageIndex + 1), doc.activeTurnIds().size()));
      List<String> keys = new ArrayList<>();
      for (Turn turn : doc.turns()) {
        if (!active.contains(turn.id()) || turn.metadata() == null) continue;
        var submitted = turn.metadata().has("submitted") ? turn.metadata().get("submitted") : turn.metadata();
        submitted.path("attachmentObjectKeys").forEach(key -> keys.add(key.asText()));
        keys.add(submitted.path("attachment").asText(""));
      }
      List<String> allowed = AttachmentKeys.normalize(keys).stream()
          .filter(doc.allowedAttachmentKeys()::contains).toList();
      return new Document(1, UUID.randomUUID().toString(), doc.turns(), active, allowed,
          doc.materializedAttachmentKeys().stream().filter(allowed::contains).toList());
    });
    conversations.invalidate(conversationId);
  }

  public void clearMessages(String conversationId) {
    truncateAfter(conversationId, -1);
  }

  public void registerAllowedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    archive.update(conversationId, doc -> new Document(1, doc.generation(), doc.turns(),
        doc.activeTurnIds(), mergeKeys(doc.allowedAttachmentKeys(), keys), doc.materializedAttachmentKeys()));
    conversations.invalidate(conversationId);
  }

  public boolean isAttachmentKeyMaterialized(String conversationId, String key) {
    return key != null && archive.read(conversationId).materializedAttachmentKeys().contains(key);
  }

  public void addMaterializedAttachmentKeys(String conversationId, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    archive.update(conversationId, doc -> new Document(1, doc.generation(), doc.turns(),
        doc.activeTurnIds(), doc.allowedAttachmentKeys(), mergeKeys(doc.materializedAttachmentKeys(), keys)));
    conversations.invalidate(conversationId);
  }

  public List<String> getAllowedAttachmentKeys(String conversationId) {
    return archive.read(conversationId).allowedAttachmentKeys().stream().sorted().toList();
  }

  private static List<String> mergeKeys(List<String> prior, Collection<String> incoming) {
    List<String> keys = new ArrayList<>(prior);
    incoming.stream().filter(AttachmentKeys::isSafe).filter(key -> !keys.contains(key)).forEach(keys::add);
    return keys;
  }

  void cleanUp() {
    conversations.cleanUp();
  }
}
