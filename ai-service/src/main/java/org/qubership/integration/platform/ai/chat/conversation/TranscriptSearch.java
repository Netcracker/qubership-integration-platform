package org.qubership.integration.platform.ai.chat.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case-insensitive substring search over stored conversation text. No embeddings. */
public final class TranscriptSearch {

  private static final int HIT_CLIP_LIMIT = 300;

  private TranscriptSearch() {}

  public static List<String> find(
      List<ConversationMessage> messages, String query, int maxHits) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String needle = query.toLowerCase(Locale.ROOT);
    List<String> hits = new ArrayList<>();
    for (ConversationMessage message : messages) {
      if (hits.size() >= maxHits) {
        break;
      }
      String content = message.content();
      if (content == null || content.isBlank()) {
        continue;
      }
      if (content.trim().equalsIgnoreCase(query)) {
        continue;
      }
      if (content.toLowerCase(Locale.ROOT).contains(needle)) {
        hits.add(clip(content));
      }
    }
    return hits;
  }

  private static String clip(String content) {
    if (content.length() <= HIT_CLIP_LIMIT) {
      return content;
    }
    return content.substring(0, HIT_CLIP_LIMIT);
  }
}
