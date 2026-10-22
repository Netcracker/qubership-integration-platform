package org.qubership.integration.platform.ai.plan.workdocument.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.qubership.integration.platform.ai.plan.workdocument.SourcePassage;

/** Splits one stored source into immutable passages. Heading lines stay context, not passages. */
public final class SourcePassageIndexer {

  private SourcePassageIndexer() {}

  public static List<SourcePassage> index(String sourceId, String content) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    List<Line> lines = lines(content);
    List<SourcePassage> passages = new ArrayList<>();
    String heading = "";
    int sequence = 1;
    int index = 0;
    while (index < lines.size()) {
      Line line = lines.get(index);
      if (line.blank()) {
        index++;
        continue;
      }
      if (headingLine(line.text())) {
        heading = headingText(line.text());
        index++;
        continue;
      }
      int start = index;
      index++;
      while (index < lines.size() && !lines.get(index).blank() && !headingLine(lines.get(index).text())) {
        index++;
      }
      String text = content.substring(lines.get(start).start(), lines.get(index - 1).end());
      passages.add(new SourcePassage("passage-" + sourceId + "-" + sequence, sourceId, sha256(text), text, heading));
      sequence++;
    }
    return List.copyOf(passages);
  }

  private static List<Line> lines(String content) {
    List<Line> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= content.length(); i++) {
      if (i != content.length() && content.charAt(i) != '\n') {
        continue;
      }
      int end = i;
      if (end > start && content.charAt(end - 1) == '\r') {
        end--;
      }
      String text = content.substring(start, end);
      lines.add(new Line(text, start, end, blank(text)));
      start = i + 1;
    }
    return lines;
  }

  private static boolean blank(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != ' ' && text.charAt(i) != '\t') {
        return false;
      }
    }
    return true;
  }

  private static boolean headingLine(String text) {
    int index = 0;
    while (index < text.length() && text.charAt(index) == ' ') {
      index++;
    }
    return index < text.length() && text.charAt(index) == '#';
  }

  private static String headingText(String text) {
    int index = 0;
    while (index < text.length() && text.charAt(index) == ' ') {
      index++;
    }
    while (index < text.length() && text.charAt(index) == '#') {
      index++;
    }
    if (index < text.length() && text.charAt(index) == ' ') {
      index++;
    }
    int end = text.length();
    while (end > index && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(index, end);
  }

  private static String sha256(String content) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private record Line(String text, int start, int end, boolean blank) {}
}
