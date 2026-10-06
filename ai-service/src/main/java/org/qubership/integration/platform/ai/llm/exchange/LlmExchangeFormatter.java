package org.qubership.integration.platform.ai.llm.exchange;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

/** Formats chat model request/response payloads for {@link LlmExchangeListener}. */
@ApplicationScoped
public class LlmExchangeFormatter {

  private final ObjectMapper mapper;

  @Inject
  public LlmExchangeFormatter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String requestCapture(ChatRequest request, String endpoint, String exchangeId, LlmExchangeMdcContext context) {
    Map<String, Object> capture = captureHeader("request", exchangeId, context);
    var parameters = request.parameters();
    String messages = formatMessages(request.messages(), 0);
    var tools = new ArrayList<Map<String, Object>>();
    if (parameters.toolSpecifications() != null) {
      for (var tool : parameters.toolSpecifications()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", tool.name());
        entry.put("description", tool.description());
        entry.put("parameters", tool.parameters() == null ? null : JsonSchemaElementUtils.toMap(tool.parameters()));
        tools.add(entry);
      }
    }
    capture.put("evidenceLayer", "sdk-chat-request");
    capture.put("endpoint", safeEndpoint(endpoint));
    capture.put("model", parameters.modelName());
    capture.put("temperature", parameters.temperature());
    capture.put("topP", parameters.topP());
    capture.put("maxOutputTokens", parameters.maxOutputTokens());
    if (parameters instanceof dev.langchain4j.model.openai.OpenAiChatRequestParameters openAi) {
      capture.put("reasoningEffort", openAi.reasoningEffort());
    } else if (parameters instanceof dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters responses) {
      capture.put("reasoningEffort", responses.reasoningEffort());
      capture.put("maxToolCalls", responses.maxToolCalls());
      capture.put("parallelToolCalls", responses.parallelToolCalls());
      capture.put("truncation", responses.truncation());
    }
    capture.put("messages", messages);
    capture.put("inputSha256", CanonicalPayloadHash.sha256Hex(messages));
    capture.put("tools", tools);
    capture.put("schemaSha256", CanonicalPayloadHash.sha256Hex(Map.of("tools", tools)));
    return json(capture);
  }

  String responseCapture(ChatResponse response, String exchangeId, LlmExchangeMdcContext context, long durationMs) {
    Map<String, Object> capture = captureHeader("response", exchangeId, context);
    capture.put("durationMs", durationMs);
    capture.put("responseId", response.metadata().id());
    capture.put("model", response.metadata().modelName());
    capture.put("finishReason", response.finishReason());
    capture.put("text", response.aiMessage().text());
    var tools = new ArrayList<Map<String, Object>>();
    for (var tool : response.aiMessage().toolExecutionRequests()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", tool.id());
      entry.put("name", tool.name());
      entry.put("arguments", tool.arguments());
      tools.add(entry);
    }
    capture.put("toolCalls", tools);
    if (response.metadata() instanceof dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata metadata) {
      capture.put("serviceTier", metadata.serviceTier());
      if (metadata.rawHttpResponse() != null) {
        capture.put("rawResponseBody", metadata.rawHttpResponse().body());
        Map<String, Object> headers = new LinkedHashMap<>();
        metadata.rawHttpResponse().headers().forEach((name, values) -> {
          if (List.of("x-request-id", "x-provider", "x-openrouter-provider", "x-openrouter-generation-id")
              .contains(name.toLowerCase(java.util.Locale.ROOT))) {
            headers.put(name, values);
          }
        });
        capture.put("responseHeaders", headers);
      }
      var events = new ArrayList<Map<String, Object>>();
      for (var event : metadata.rawServerSentEvents()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event.event());
        entry.put("data", event.data());
        events.add(entry);
      }
      capture.put("sdkSseEvents", events);
    }
    return json(capture);
  }

  String errorCapture(Throwable error, String exchangeId, LlmExchangeMdcContext context, long durationMs) {
    Map<String, Object> capture = captureHeader("error", exchangeId, context);
    capture.put("durationMs", durationMs);
    capture.put("errorType", error.getClass().getName());
    return json(capture);
  }

  private static Map<String, Object> captureHeader(String kind, String exchangeId, LlmExchangeMdcContext context) {
    Map<String, Object> capture = new LinkedHashMap<>();
    capture.put("kind", kind);
    capture.put("exchangeId", exchangeId);
    capture.put("conversationId", context.conversationId());
    capture.put("scenarioType", context.scenarioType());
    capture.put("capabilityId", context.capabilityId());
    return capture;
  }

  private String json(Map<String, Object> capture) {
    try {
      return mapper.writeValueAsString(capture);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Unable to serialize LLM exchange evidence", error);
    }
  }

  static String safeEndpoint(String endpoint) {
    try {
      URI uri = URI.create(endpoint);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
    } catch (IllegalArgumentException | java.net.URISyntaxException error) {
      return "(invalid)";
    }
  }

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
        + preview(text, maxChars)
        + formatToolRequests(aiMessage, maxChars);
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
              index, "assistant", aiMessage.text(), formatToolCallNames(aiMessage), maxChars)
          + formatToolRequests(aiMessage, maxChars);
    }
    if (message instanceof ToolExecutionResultMessage toolMessage) {
      String label = toolMessage.toolName() != null ? toolMessage.toolName() : toolMessage.id();
      return "  ["
          + index
          + "] tool name="
          + label
          + " id="
          + toolMessage.id()
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

  private static String formatToolRequests(AiMessage aiMessage, int maxChars) {
    if (aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
      return "";
    }
    var lines = new StringBuilder();
    aiMessage.toolExecutionRequests().forEach(request -> lines.append("\n    tool id=")
        .append(request.id())
        .append(" name=")
        .append(request.name())
        .append(" arguments=")
        .append(preview(request.arguments(), maxChars)));
    return lines.toString();
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
    return maxChars <= 0 ? (text != null ? text : "(null)") : AiTraceLog.preview(text, maxChars);
  }

  private static int messageCount(List<ChatMessage> messages) {
    return messages != null ? messages.size() : 0;
  }

  private static int textLength(String text) {
    return text != null ? text.length() : 0;
  }
}
