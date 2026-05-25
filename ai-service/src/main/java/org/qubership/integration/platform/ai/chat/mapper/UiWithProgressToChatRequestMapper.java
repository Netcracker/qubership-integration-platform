package org.qubership.integration.platform.ai.chat.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.model.UiWithProgressRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Maps UI {@code /api/chat/with-progress} model to the internal {@link ChatRequest}. */
public final class UiWithProgressToChatRequestMapper {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private UiWithProgressToChatRequestMapper() {}

  public static ChatRequest toInternal(UiWithProgressRequest ui) {
    if (ui == null || ui.getMessages() == null) {
      return null;
    }
    Optional<String> lastUser = findLastUserMessage(ui.getMessages());
    if (lastUser.isEmpty() || lastUser.get().isBlank()) {
      return null;
    }
    ChatRequest r = new ChatRequest();
    r.setMessage(lastUser.get().trim());
    r.setConversationId(ui.getConversationId());
    if (ui.getAttachmentObjectKeys() != null && !ui.getAttachmentObjectKeys().isEmpty()) {
      r.setAttachmentObjectKeys(List.copyOf(ui.getAttachmentObjectKeys()));
    }
    if (ui.getAttachmentUrls() != null && !ui.getAttachmentUrls().isEmpty()) {
      String legacy =
          ui.getAttachmentUrls().stream()
              .filter(Objects::nonNull)
              .map(u -> "- " + u)
              .collect(Collectors.joining("\n"));
      r.setAttachment(legacy);
    }
    applyChainContext(ui.getContext(), r);
    // modelId / temperature / maxTokens: backend uses app LLM config
    return r;
  }

  private static void applyChainContext(Object context, ChatRequest request) {
    if (context == null) {
      return;
    }
    try {
      JsonNode node = MAPPER.valueToTree(context);
      if (!node.isObject() || !"chain".equalsIgnoreCase(node.path("type").asText(""))) {
        return;
      }
      String chainId = node.path("chainId").asText("");
      JsonNode compact = node.path("compactSchema");
      StringBuilder block = new StringBuilder();
      if (!chainId.isBlank()) {
        block.append("Active chain context: chainId=").append(chainId.trim()).append('\n');
      }
      if (compact != null && !compact.isMissingNode() && !compact.isNull()) {
        block.append("compactSchema:\n").append(MAPPER.writeValueAsString(compact)).append('\n');
      }
      if (block.length() == 0) {
        return;
      }
      String existing = request.getAttachment();
      if (existing == null || existing.isBlank()) {
        request.setAttachment(block.toString().trim());
      } else {
        request.setAttachment(existing + "\n\n---\n\n" + block.toString().trim());
      }
    } catch (Exception ignored) {
      // UI context is best-effort; ignore malformed payloads
    }
  }

  private static Optional<String> findLastUserMessage(
      List<UiWithProgressRequest.UiMessage> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      UiWithProgressRequest.UiMessage m = messages.get(i);
      if (m == null) {
        continue;
      }
      if ("user".equalsIgnoreCase(m.getRole()) && m.getContent() != null) {
        return Optional.of(m.getContent());
      }
    }
    return Optional.empty();
  }
}
