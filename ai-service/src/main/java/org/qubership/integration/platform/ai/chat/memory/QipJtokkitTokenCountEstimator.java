package org.qubership.integration.platform.ai.chat.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.Locale;

/** Local token counting via Jtokkit (no HTTP during streaming). */
public final class QipJtokkitTokenCountEstimator implements TokenCountEstimator {

  private static final int TOOL_REQUEST_OVERHEAD = 12;

  private final Encoding encoding;

  public QipJtokkitTokenCountEstimator(String modelName) {
    this.encoding =
        Encodings.newDefaultEncodingRegistry().getEncoding(resolveEncodingType(modelName));
  }

  @Override
  public int estimateTokenCountInText(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return encoding.countTokens(text);
  }

  @Override
  public int estimateTokenCountInMessage(ChatMessage message) {
    if (message == null) {
      return 0;
    }
    return switch (message.type()) {
      case USER -> estimateTokenCountInText(((UserMessage) message).singleText());
      case AI -> estimateAiMessage((AiMessage) message);
      case SYSTEM -> estimateTokenCountInText(((SystemMessage) message).text());
      case TOOL_EXECUTION_RESULT -> estimateToolResult((ToolExecutionResultMessage) message);
      default -> estimateTokenCountInText(message.toString());
    };
  }

  @Override
  public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
    if (messages == null) {
      return 0;
    }
    int total = 0;
    for (ChatMessage message : messages) {
      total += estimateTokenCountInMessage(message);
    }
    return total;
  }

  private int estimateAiMessage(AiMessage message) {
    int total = 0;
    String text = message.text();
    if (text != null && !text.isBlank()) {
      total += estimateTokenCountInText(text);
    }
    if (message.hasToolExecutionRequests()) {
      for (var req : message.toolExecutionRequests()) {
        total += TOOL_REQUEST_OVERHEAD;
        if (req.name() != null) {
          total += estimateTokenCountInText(req.name());
        }
        if (req.arguments() != null) {
          total += estimateTokenCountInText(req.arguments());
        }
      }
    }
    return total;
  }

  private int estimateToolResult(ToolExecutionResultMessage message) {
    int total = TOOL_REQUEST_OVERHEAD;
    if (message.toolName() != null) {
      total += estimateTokenCountInText(message.toolName());
    }
    if (message.text() != null) {
      total += estimateTokenCountInText(message.text());
    }
    return total;
  }

  static EncodingType resolveEncodingType(String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return EncodingType.CL100K_BASE;
    }
    String lower = modelName.toLowerCase(Locale.ROOT);
    if (lower.contains("gpt-4o")
        || lower.contains("gpt-4.1")
        || lower.contains("o1")
        || lower.contains("o3")
        || lower.contains("o4")) {
      return EncodingType.O200K_BASE;
    }
    return EncodingType.CL100K_BASE;
  }
}
