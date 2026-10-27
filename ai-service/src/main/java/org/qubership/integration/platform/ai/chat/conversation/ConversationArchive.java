package org.qubership.integration.platform.ai.chat.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

/** Durable source turns; resetting a chat changes authority without deleting evidence. */
@ApplicationScoped
public class ConversationArchive {
  public record Turn(String id, ConversationMessage message, String rawText, JsonNode metadata,
      String status, String consumedDraftReference) {
    Turn effective(String text) {
      return new Turn(id, new ConversationMessage(message.role(), text, message.createdAt()),
          rawText, metadata, "READY", consumedDraftReference);
    }
  }

  public record Document(int version, String generation, List<Turn> turns, List<String> activeTurnIds,
      List<String> allowedAttachmentKeys, List<String> materializedAttachmentKeys) {
    public Document {
      if (version != 1) {
        throw new IllegalArgumentException("Unsupported conversation archive version: " + version);
      }
      turns = List.copyOf(turns);
      activeTurnIds = List.copyOf(activeTurnIds);
      allowedAttachmentKeys = List.copyOf(allowedAttachmentKeys);
      materializedAttachmentKeys = List.copyOf(materializedAttachmentKeys);
    }
    public List<Turn> activeTurns() {
      return turns.stream().filter(turn -> activeTurnIds.contains(turn.id())).toList();
    }
  }

  public record Input(String turnId, String generation, int messageIndex) {}

  private final ArtifactBlobStore blobs;
  private final ObjectMapper mapper;

  @Inject
  public ConversationArchive(ArtifactBlobStore blobs, ObjectMapper mapper) {
    this.blobs = blobs;
    this.mapper = mapper;
  }

  public Document read(String conversationId) {
    return blobs.get(key(conversationId)).map(this::decode).orElseGet(ConversationArchive::empty);
  }

  // ponytail: one local archive lock; use per-conversation locks if simultaneous chat writes need more throughput.
  synchronized Document update(String conversationId, UnaryOperator<Document> change) {
    String key = key(conversationId);
    // ponytail: one snapshot per chat; use segmented turns if archive rewrite cost becomes material.
    for (int attempt = 0; attempt < 8; attempt++) {
      var prior = blobs.getVersioned(key);
      Document updated = change.apply(prior.map(blob -> decode(blob.content())).orElseGet(ConversationArchive::empty));
      try {
        blobs.putIfVersion(key, mapper.writeValueAsBytes(updated),
            prior.map(blob -> blob.version()).orElse(null));
        return updated;
      } catch (StaleBlobVersionException conflict) {
        if (attempt == 7) {
          throw conflict;
        }
      } catch (IOException error) {
        throw new IllegalStateException("Cannot save conversation source before processing it.", error);
      }
    }
    throw new IllegalStateException("Conversation archive write did not complete.");
  }

  public Input beginInput(String conversationId, String rawText, JsonNode metadata) {
    return beginInput(conversationId, null, rawText, metadata);
  }

  /**
   * Archives one author turn.
   *
   * <p>A blank {@code deterministicTurnId} appends a new turn. The same id, canonical answer, and
   * offer metadata return the existing turn and leave the source generation unchanged. The same id
   * with a different answer or offer is rejected.
   */
  public Input beginInput(
      String conversationId, String deterministicTurnId, String rawText, JsonNode metadata) {
    if (deterministicTurnId == null || deterministicTurnId.isBlank()) {
      return appendReceived(conversationId, UUID.randomUUID().toString(), rawText, metadata);
    }
    String id = deterministicTurnId;
    Document updated = update(conversationId, doc -> {
      for (Turn existing : doc.turns()) {
        if (!existing.id().equals(id)) {
          continue;
        }
        if (sameClarificationAnswer(existing, rawText, metadata)) {
          return doc;
        }
        throw new IllegalStateException("Clarification answer id collides with a different payload.");
      }
      return append(doc, receivedTurn(id, rawText, metadata));
    });
    return new Input(id, updated.generation(), updated.activeTurnIds().indexOf(id));
  }

  /**
   * Turn id for one clarification answer. The digest covers the offer and the canonical answer
   * text, and no run revision.
   */
  public static String deterministicAnswerTurnId(String offerId, String rawText) {
    return CanonicalPayloadHash.sha256Hex(Map.of(
        "clarificationOfferId", offerId == null ? "" : offerId,
        "text", canonicalAnswerText(rawText)));
  }

  private Input appendReceived(String conversationId, String id, String rawText, JsonNode metadata) {
    Turn turn = receivedTurn(id, rawText, metadata);
    Document updated = update(conversationId, doc -> append(doc, turn));
    return new Input(id, updated.generation(), updated.activeTurnIds().indexOf(id));
  }

  private static Turn receivedTurn(String id, String rawText, JsonNode metadata) {
    return new Turn(id, ConversationMessage.user(rawText), rawText, metadata, "RECEIVED", null);
  }

  private static boolean sameClarificationAnswer(Turn existing, String rawText, JsonNode metadata) {
    return canonicalAnswerText(existing.rawText()).equals(canonicalAnswerText(rawText))
        && clarificationOfferId(existing.metadata()).equals(clarificationOfferId(metadata));
  }

  private static String canonicalAnswerText(String rawText) {
    return rawText == null ? "" : rawText.strip();
  }

  private static String clarificationOfferId(JsonNode metadata) {
    if (metadata == null || !metadata.isObject()) {
      return "";
    }
    JsonNode value = metadata.get("clarificationOfferId");
    if (value == null || value.isNull()) {
      return "";
    }
    return value.asText("");
  }

  public void resolveInput(String conversationId, Input input, String effectiveText) {
    resolveInput(conversationId, input, effectiveText, null);
  }

  public void resolveInput(String conversationId, Input input, String effectiveText, JsonNode resolvedDecision) {
    update(conversationId, doc -> {
      requireGeneration(doc, input.generation());
      List<Turn> turns = doc.turns().stream()
          .map(turn -> turn.id().equals(input.turnId()) ? new Turn(turn.id(), turn.effective(effectiveText).message(), turn.rawText(),
              resolvedDecision == null ? turn.metadata() : mapper.valueToTree(java.util.Map.of(
                  "submitted", turn.metadata() == null ? mapper.nullNode() : turn.metadata(),
                  "resolvedDecision", resolvedDecision)), "READY", turn.consumedDraftReference()) : turn).toList();
      return copy(doc, turns, doc.activeTurnIds());
    });
  }

  public boolean appendAssistant(String conversationId, String generation, String text,
      JsonNode metadata, String status) {
    return saveAssistant(conversationId, generation, UUID.randomUUID().toString(), text, metadata, status);
  }

  public boolean saveAssistant(String conversationId, String generation, String turnId, String text,
      JsonNode metadata, String status) {
    return saveAssistant(conversationId, generation, turnId, text, metadata, status, null);
  }

  public boolean saveAssistant(String conversationId, String generation, String turnId, String text,
      JsonNode metadata, String status, String sourceTurnId) {
    Turn turn = new Turn(turnId, ConversationMessage.assistant(text),
        null, metadata, status, null);
    Document result = update(conversationId, doc -> {
      if (!doc.generation().equals(generation)
          || sourceTurnId != null && !sourceTurnId.equals(latestInputId(doc))) {
        return doc;
      }
      if (!doc.activeTurnIds().contains(turnId)) {
        return append(doc, turn);
      }
      List<Turn> turns = doc.turns().stream().map(existing -> existing.id().equals(turnId)
          ? new Turn(turnId, text != null && !text.isBlank() ? turn.message() : existing.message(),
              null, metadata == null ? existing.metadata() : metadata,
              status, null) : existing).toList();
      return copy(doc, turns, doc.activeTurnIds());
    });
    return result.generation().equals(generation)
        && (sourceTurnId == null || sourceTurnId.equals(latestInputId(result)))
        && result.activeTurnIds().contains(turn.id());
  }

  /** Pins an admitted answer to the questions and draft that were actually presented. */
  public void associateClarification(String conversationId, String generation, String turnId,
      org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference draftReference) {
    update(conversationId, doc -> {
      requireGeneration(doc, generation);
      return copy(doc, doc.turns().stream().map(turn -> {
        if (!turn.id().equals(turnId) || turn.metadata() != null && turn.metadata().has("clarificationDraft")) return turn;
        var metadata = mapper.createObjectNode();
        if (turn.metadata() != null) metadata.set("submitted", turn.metadata());
        metadata.set("clarificationDraft", mapper.valueToTree(draftReference));
        return new Turn(turn.id(), turn.message(), turn.rawText(), metadata, turn.status(), turn.consumedDraftReference());
      }).toList(), doc.activeTurnIds());
    });
  }

  public List<Turn> pendingInputs(String conversationId) {
    return read(conversationId).activeTurns().stream()
        .filter(turn -> turn.message().role() == ConversationMessage.Role.USER
            && "READY".equals(turn.status()) && turn.consumedDraftReference() == null).toList();
  }

  public void consume(String conversationId, String generation, List<String> turnIds, String draftReference) {
    update(conversationId, doc -> {
      requireGeneration(doc, generation);
      List<Turn> turns = doc.turns().stream().map(turn -> turnIds.contains(turn.id())
          && turn.message().role() == ConversationMessage.Role.USER
          && "READY".equals(turn.status()) && turn.consumedDraftReference() == null
          ? new Turn(turn.id(), turn.message(), turn.rawText(), turn.metadata(), turn.status(), draftReference)
          : turn).toList();
      return copy(doc, turns, doc.activeTurnIds());
    });
  }

  public synchronized <T> T withAuthority(String conversationId, String generation,
      String latestInputId, java.util.function.Supplier<T> action) {
    Document current = read(conversationId);
    if (!current.generation().equals(generation)) {
      throw new org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException(
          "Tool call belongs to an obsolete reset generation.");
    }
    String actual = latestInputId(current);
    if (!java.util.Objects.equals(actual, latestInputId)) {
      throw new org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException(
          "Tool call belongs to a superseded author turn.");
    }
    return action.get();
  }

  public static String latestInputId(Document document) {
    var inputs = document.activeTurns().stream()
        .filter(turn -> turn.message().role() == ConversationMessage.Role.USER).toList();
    return inputs.isEmpty() ? null : inputs.getLast().id();
  }

  public String readSource(String conversationId, String turnId, int offset, int maxChars) {
    Turn turn = read(conversationId).turns().stream().filter(candidate -> candidate.id().equals(turnId))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("Source turn is not in this conversation."));
    String text;
    try {
      text = mapper.writeValueAsString(turn);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read complete conversation source.", error);
    }
    if (offset < 0 || offset > text.length() || maxChars < 1 || maxChars > 16000) {
      throw new IllegalArgumentException("Use a valid source offset and 1 to 16000 characters.");
    }
    return "Source turn=" + turnId + " offset=" + offset + " totalChars=" + text.length()
        + "\n" + text.substring(offset, Math.min(text.length(), offset + maxChars));
  }

  public String saveInvocation(String conversationId, String stageId, String attemptId,
      Object selectedReferences, String packet) {
    String hash = org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash.sha256Hex(packet);
    String prefix = key(conversationId);
    String reference = prefix.substring(0, prefix.lastIndexOf('/') + 1) + "invocations/"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(attemptId.getBytes(StandardCharsets.UTF_8))
        + "/" + hash + ".json";
    try {
      blobs.put(reference, mapper.writeValueAsBytes(java.util.Map.of(
          "stage", stageId, "attempt", attemptId, "selectedReferences", selectedReferences,
          "hash", hash, "packet", packet)));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot retain the complete model input.", error);
    }
    return reference;
  }

  static Document append(Document doc, Turn turn) {
    List<Turn> turns = new ArrayList<>(doc.turns());
    turns.add(turn);
    List<String> active = new ArrayList<>(doc.activeTurnIds());
    active.add(turn.id());
    return copy(doc, turns, active);
  }

  static Document copy(Document doc, List<Turn> turns, List<String> active) {
    return new Document(1, doc.generation(), turns, active, doc.allowedAttachmentKeys(), doc.materializedAttachmentKeys());
  }

  private static void requireGeneration(Document doc, String expected) {
    if (!doc.generation().equals(expected)) {
      throw new IllegalStateException("Conversation source belongs to an obsolete reset generation.");
    }
  }

  private Document decode(byte[] content) {
    try {
      return mapper.readValue(content, Document.class);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot restore conversation source. Processing stopped.", error);
    }
  }

  private static Document empty() {
    return new Document(1, UUID.randomUUID().toString(), List.of(), List.of(), List.of(), List.of());
  }

  private static String key(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("Conversation identity is required for source storage.");
    }
    return "conversation-archive/" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8)) + "/source.json";
  }
}
