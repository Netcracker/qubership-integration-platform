package org.qubership.integration.platform.ai.chat;

import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

/** Last 12 conversation messages, each content clipped to 500 characters. */
public final class TranscriptWindow {

  static final int MAX_MESSAGES = 12;
  static final int MAX_CONTENT_CHARS = 500;

  private TranscriptWindow() {}

  public static String format(List<ConversationMessage> messages) {
    if (messages.isEmpty()) {
      return "";
    }
    int from = Math.max(0, messages.size() - MAX_MESSAGES);
    StringBuilder sb = new StringBuilder();
    for (ConversationMessage message : messages.subList(from, messages.size())) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append(message.role().name().toLowerCase(Locale.ROOT)).append(": ");
      String content = message.content() == null ? "" : message.content();
      if (content.length() > MAX_CONTENT_CHARS) {
        content = content.substring(0, MAX_CONTENT_CHARS);
      }
      sb.append(content);
    }
    return sb.toString();
  }
}
