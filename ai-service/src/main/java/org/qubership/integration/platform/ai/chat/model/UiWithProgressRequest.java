package org.qubership.integration.platform.ai.chat.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Request body from {@code qubership-integration-ui} (HTTP provider). Mapped to {@link ChatRequest}
 * for the core engine.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UiWithProgressRequest {

  private List<UiMessage> messages;
  private String conversationId;
  private String modelId;
  private Double temperature;
  private Integer maxTokens;
  private Object context;
  private List<String> attachmentUrls;

  /** S3/MinIO object keys from {@code POST /api/v1/storage/objects} (optional). */
  private List<String> attachmentObjectKeys;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class UiMessage {
    private String role;
    private String content;
  }
}
