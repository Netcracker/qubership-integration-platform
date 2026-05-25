package org.qubership.integration.platform.ai.chat.guardrail;

import java.util.Locale;
import java.util.Set;

/** Redacted user text for guardrail classification (no attachment body). */
public final class GuardrailClassificationText {

  private static final Set<String> ATTACHMENT_BOILERPLATE =
      Set.of(
          "see attached files",
          "see attached file",
          "see attachment",
          "see attachments",
          "attached",
          "attachment attached",
          "file attached",
          "files attached");

  private GuardrailClassificationText() {}

  public static boolean isAttachmentBoilerplate(String message) {
    String normalized = normalize(message);
    return normalized.isEmpty() || ATTACHMENT_BOILERPLATE.contains(normalized);
  }

  public static String toClassifierMessage(
      String userMessage, AttachmentManifest manifest, boolean hasAttachments) {
    String text = userMessage != null ? userMessage.trim() : "";
    if (!hasAttachments || manifest.isEmpty()) {
      return text;
    }
    StringBuilder sb = new StringBuilder();
    if (!text.isBlank()) {
      sb.append(text).append("\n\n");
    }
    int n = manifest.count();
    if (n == 1) {
      sb.append("[Attachments: 1 file — ")
          .append(manifest.fileNames().getFirst())
          .append(" (content omitted)]");
    } else {
      String joined = String.join(", ", manifest.fileNames());
      sb.append("[Attachments: ")
          .append(n)
          .append(" files — ")
          .append(joined)
          .append(" (content omitted)]");
    }
    return sb.toString().strip();
  }

  static String normalize(String message) {
    if (message == null) {
      return "";
    }
    return message
        .trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .replaceAll("[.!?]+$", "");
  }
}
