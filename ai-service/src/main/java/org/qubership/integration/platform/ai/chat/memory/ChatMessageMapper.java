package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

import java.util.ArrayList;
import java.util.List;

/** Maps between {@link ConversationMessage} storage and LangChain4j {@link ChatMessage} types. */
public final class ChatMessageMapper {

  private static final String TOOL_PREFIX = "[Tool] ";
  private static final int MAX_TOOL_RESULT_CHARS = 4_000;

  private ChatMessageMapper() {}

  public static List<StoredMessageDraft> toStoredDrafts(List<ChatMessage> messages) {
    List<StoredMessageDraft> out = new ArrayList<>();
    for (ChatMessage message : messages) {
      out.add(new StoredMessageDraft(toStoredRole(message), toStoredContent(message)));
    }
    return out;
  }

  public static ChatMessage toChatMessage(ConversationMessage.Role role, String content) {
    return switch (role) {
      case USER -> UserMessage.from(content);
      case ASSISTANT -> AiMessage.from(content);
      case SYSTEM -> SystemMessage.from(content);
    };
  }

  private static ConversationMessage.Role toStoredRole(ChatMessage message) {
    return switch (message.type()) {
      case USER -> ConversationMessage.Role.USER;
      case AI -> ConversationMessage.Role.ASSISTANT;
      case SYSTEM, TOOL_EXECUTION_RESULT -> ConversationMessage.Role.SYSTEM;
      default -> ConversationMessage.Role.SYSTEM;
    };
  }

  private static String toStoredContent(ChatMessage message) {
    return switch (message.type()) {
      case USER -> ((UserMessage) message).singleText();
      case AI -> aiMessageText((AiMessage) message);
      case SYSTEM -> ((SystemMessage) message).text();
      case TOOL_EXECUTION_RESULT -> formatToolResult((ToolExecutionResultMessage) message);
      default -> message.toString();
    };
  }

  private static String aiMessageText(AiMessage message) {
    String text = message.text();
    if (text != null && !text.isBlank()) {
      return text;
    }
    if (message.hasToolExecutionRequests()) {
      return message.toolExecutionRequests().toString();
    }
    return "";
  }

  private static String formatToolResult(ToolExecutionResultMessage message) {
    StringBuilder sb = new StringBuilder(TOOL_PREFIX);
    if (message.toolName() != null && !message.toolName().isBlank()) {
      sb.append(message.toolName()).append(": ");
    }
    String body = message.text() != null ? message.text() : "";
    if (body.length() > MAX_TOOL_RESULT_CHARS) {
      body = body.substring(0, MAX_TOOL_RESULT_CHARS) + "…";
    }
    sb.append(body);
    return sb.toString();
  }

  /** Draft row before ids/timestamps are assigned in {@link ConversationService}. */
  public record StoredMessageDraft(ConversationMessage.Role role, String content) {}
}
