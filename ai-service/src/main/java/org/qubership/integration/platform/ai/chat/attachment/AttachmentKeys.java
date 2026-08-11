package org.qubership.integration.platform.ai.chat.attachment;

/** Shared validation for S3 object keys used as chat attachments. */
public final class AttachmentKeys {

  private AttachmentKeys() {}

  /** Returns true when {@code key} is non-null, non-blank, and contains no path-traversal segment. */
  public static boolean isSafe(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    for (String segment : key.split("/")) {
      if ("..".equals(segment)) {
        return false;
      }
    }
    return true;
  }
}
