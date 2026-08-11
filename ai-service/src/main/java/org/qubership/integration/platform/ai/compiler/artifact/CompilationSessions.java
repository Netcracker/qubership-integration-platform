package org.qubership.integration.platform.ai.compiler.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maintains the active compilation identity for each conversation without deleting history. */
@ApplicationScoped
public class CompilationSessions {

  private static final String ROOT_PREFIX = "compiler-artifacts/conversations/";
  private static final Comparator<CompilationSession> SESSION_ORDER =
      Comparator.comparing(CompilationSession::createdAt)
          .thenComparing(CompilationSession::sessionId);

  private final ArtifactBlobStore blobStore;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Inject
  CompilationSessions(S3ArtifactBlobStore blobStore, ObjectMapper objectMapper) {
    this(blobStore, objectMapper, Clock.systemUTC());
  }

  public CompilationSessions(
      ArtifactBlobStore blobStore, ObjectMapper objectMapper, Clock clock) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the active compilation, creating one when the conversation has none. */
  public synchronized String active(String conversationId) {
    // ponytail: Chat turns are single-writer today. Use an S3 conditional write if active
    // compilation creation becomes multi-writer.
    return current(conversationId).map(ActiveCompilation::compilationId).orElseGet(
        () -> startNew(conversationId));
  }

  /** Starts another compilation while retaining every artifact from the previous one. */
  public synchronized String startNew(String conversationId) {
    requireConversationId(conversationId);
    Instant now = clock.instant();
    CompilationSession session =
        new CompilationSession(
            UUID.randomUUID().toString(),
            conversationId,
            UUID.randomUUID().toString(),
            now);
    ActiveCompilation link =
        new ActiveCompilation(conversationId, session.compilationId(), now);
    blobStore.put(sessionKey(session), write(session));
    blobStore.put(linkKey(conversationId), write(link));
    return link.compilationId();
  }

  /** Reactivates a known compilation without changing its artifact history. */
  public synchronized void activate(String conversationId, String compilationId) {
    requireConversationId(conversationId);
    if (compilationId == null || compilationId.isBlank()) {
      throw new IllegalArgumentException("compilationId is required");
    }
    boolean known =
        history(conversationId).stream()
            .anyMatch(session -> session.compilationId().equals(compilationId));
    if (!known) {
      throw new IllegalArgumentException("compilation does not belong to the conversation");
    }
    blobStore.put(
        linkKey(conversationId),
        write(new ActiveCompilation(conversationId, compilationId, clock.instant())));
  }

  /** Returns the active compilation without creating one. */
  public Optional<ActiveCompilation> current(String conversationId) {
    requireConversationId(conversationId);
    return blobStore.get(linkKey(conversationId)).map(this::read);
  }

  /** Returns every compilation created for the conversation. */
  public List<CompilationSession> history(String conversationId) {
    requireConversationId(conversationId);
    return blobStore.list(historyPrefix(conversationId)).stream()
        .filter(key -> key.endsWith(".json"))
        .map(
            key ->
                blobStore
                    .get(key)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "compilation session disappeared: " + key)))
        .map(content -> read(content, CompilationSession.class))
        .sorted(SESSION_ORDER)
        .toList();
  }

  private byte[] write(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize active compilation", e);
    }
  }

  private ActiveCompilation read(byte[] content) {
    return read(content, ActiveCompilation.class);
  }

  private <T> T read(byte[] content, Class<T> type) {
    try {
      return objectMapper.readValue(content, type);
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize active compilation", e);
    }
  }

  private static String linkKey(String conversationId) {
    return conversationPrefix(conversationId) + "active.json";
  }

  private static String sessionKey(CompilationSession session) {
    return historyPrefix(session.conversationId())
        + String.format("%019d", session.createdAt().toEpochMilli())
        + "-"
        + session.sessionId()
        + ".json";
  }

  private static String historyPrefix(String conversationId) {
    return conversationPrefix(conversationId) + "history/";
  }

  private static String conversationPrefix(String conversationId) {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8));
    return ROOT_PREFIX + encoded + "/";
  }

  private static void requireConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
  }

  public record ActiveCompilation(
      String conversationId, String compilationId, Instant updatedAt) {}

  public record CompilationSession(
      String sessionId, String conversationId, String compilationId, Instant createdAt) {}
}
