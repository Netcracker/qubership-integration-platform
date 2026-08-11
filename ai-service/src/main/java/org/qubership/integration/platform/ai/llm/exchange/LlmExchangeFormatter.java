package org.qubership.integration.platform.ai.llm.exchange;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

/** Formats chat model request/response payloads for {@link LlmExchangeListener}. */
@ApplicationScoped
public class LlmExchangeFormatter {

  public String formatRequest(
      List<ChatMessage> messages, LlmExchangeMdcContext mdc, long durationMs, int maxChars) {
    return formatHeader("LLM request", mdc, messageCount(messages), durationMs)
        + formatMessages(messages, maxChars);
  }

  public String formatResponse(
      ChatResponse response, LlmExchangeMdcContext mdc, long durationMs, int maxChars) {
    AiMessage aiMessage = response != null ? response.aiMessage() : null;
    String finishReason = response != null && response.finishReason() != null
        ? response.finishReason().name()
        : "-";
    String text = aiMessage != null ? aiMessage.text() : null;
    int chars = text != null ? text.length() : 0;
    return formatHeader("LLM response", mdc, 1, durationMs)
        + " finishReason="
        + finishReason
        + " toolCalls="
        + formatToolCallNames(aiMessage)
        + " chars="
        + chars
        + " preview="
        + AiTraceLog.preview(text, maxChars);
  }

  public String formatError(LlmExchangeMdcContext mdc, long durationMs, Throwable error) {
    String message = error != null ? error.getMessage() : "(null)";
    return formatHeader("LLM error", mdc, 0, durationMs)
        + " error="
        + AiTraceLog.previewOneLine(message, 500);
  }

  String formatMessages(List<ChatMessage> messages, int maxChars) {
    if (messages == null || messages.isEmpty()) {
      return "\n  (no messages)";
    }
    var lines = new StringBuilder();
    for (int i = 0; i < messages.size(); i++) {
      lines.append('\n').append(formatMessageLine(i, messages.get(i), maxChars));
    }
    return lines.toString();
  }

  private String formatMessageLine(int index, ChatMessage message, int maxChars) {
    if (message instanceof SystemMessage systemMessage) {
      return formatRoleLine(index, "system", systemMessage.text(), null, maxChars);
    }
    if (message instanceof UserMessage userMessage) {
      return formatRoleLine(index, "user", userMessage.singleText(), null, maxChars);
    }
    if (message instanceof AiMessage aiMessage) {
      return formatRoleLine(
          index, "assistant", aiMessage.text(), formatToolCallNames(aiMessage), maxChars);
    }
    if (message instanceof ToolExecutionResultMessage toolMessage) {
      String label = toolMessage.toolName() != null ? toolMessage.toolName() : toolMessage.id();
      return "  ["
          + index
          + "] tool name="
          + label
          + " chars="
          + textLength(toolMessage.text())
          + " preview="
          + preview(toolMessage.text(), maxChars);
    }
    String fallback = message != null ? message.toString() : "(null)";
    return "  ["
        + index
        + "] "
        + (message != null ? message.type() : "unknown")
        + " chars="
        + fallback.length()
        + " preview="
        + preview(fallback, maxChars);
  }

  private static String formatRoleLine(
      int index, String role, String text, String toolCalls, int maxChars) {
    var line =
        new StringBuilder("  [")
            .append(index)
            .append("] ")
            .append(role)
            .append(" chars=")
            .append(textLength(text));
    if (toolCalls != null && !toolCalls.isBlank()) {
      line.append(" toolCalls=").append(toolCalls);
    }
    line.append(" preview=").append(preview(text, maxChars));
    return line.toString();
  }

  private static String formatToolCallNames(AiMessage aiMessage) {
    if (aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
      return "[]";
    }
    List<String> names = new ArrayList<>();
    aiMessage.toolExecutionRequests().forEach(req -> names.add(req.name()));
    return names.toString();
  }

  private String formatHeader(
      String kind, LlmExchangeMdcContext mdc, int messageCount, long durationMs) {
    LlmExchangeMdcContext ctx = mdc != null ? mdc : LlmExchangeMdcContext.none();
    return kind
        + " conversationId="
        + ctx.conversationId()
        + " scenarioType="
        + ctx.scenarioType()
        + " capabilityId="
        + ctx.capabilityId()
        + " messages="
        + messageCount
        + " durationMs="
        + (durationMs >= 0 ? durationMs : "-");
  }

  private static String preview(String text, int maxChars) {
    return AiTraceLog.preview(text, maxChars);
  }

  private static int messageCount(List<ChatMessage> messages) {
    return messages != null ? messages.size() : 0;
  }

  private static int textLength(String text) {
    return text != null ? text.length() : 0;
  }
}
