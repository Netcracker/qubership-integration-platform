package org.qubership.integration.platform.ai.chat.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;

/** Incoming chat request from the frontend. */
@Getter
@Setter
public class ChatRequest {

  /** Existing conversation ID; null to start a new conversation. */
  private String conversationId;

  /** The user's message text. */
  @NotBlank(message = "message must not be blank")
  @Size(max = 32_000, message = "message too long")
  private String message;

  /**
   * Optional file/document content pasted inline (design doc, chain YAML, etc.). Kept separate from
   * the message so prompts can reference it explicitly.
   */
  private String attachment;

  /**
   * S3/MinIO object keys (from {@link
   * org.qubership.integration.platform.ai.storage.StorageUploadResponse#objectKey}) for this turn.
   * The server inlines UTF-8 bodies into the effective user text when possible.
   */
  private List<String> attachmentObjectKeys;

  /** Hint to override automatic scenario routing. If null, auto-detect. */
  private ScenarioType scenarioHint;

  /**
   * Full prompt after server-side resolution (e.g. storage keys inlined). Set internally; not sent
   * by clients.
   */
  @JsonIgnore private String resolvedEffectiveUserText;

  /**
   * True when the client sent an attachment (S3 keys and/or legacy inline/URL attachment string).
   */
  public boolean hasAttachmentPayload() {
    if (attachmentObjectKeys != null && !attachmentObjectKeys.isEmpty()) {
      return true;
    }
    return attachment != null && !attachment.isBlank();
  }

  /**
   * Effective user prompt for persistence and LLM. Uses server-resolved text when set; otherwise
   * {@code message} plus optional {@code attachment} with a {@code ---} separator.
   */
  public String getEffectiveUserText() {
    if (resolvedEffectiveUserText != null) {
      return resolvedEffectiveUserText;
    }
    if (attachment == null || attachment.isBlank()) {
      return message;
    }
    return message + "\n\n---\n\n" + attachment;
  }
}
