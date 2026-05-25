package org.qubership.integration.platform.ai.chat.guardrail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Attachment metadata for guardrail (filenames only; no storage reads). */
public record AttachmentManifest(List<String> fileNames) {

  public AttachmentManifest {
    fileNames = fileNames == null ? List.of() : List.copyOf(fileNames);
  }

  public static AttachmentManifest empty() {
    return new AttachmentManifest(List.of());
  }

  public static AttachmentManifest fromObjectKeys(List<String> objectKeys) {
    if (objectKeys == null || objectKeys.isEmpty()) {
      return empty();
    }
    List<String> names = new ArrayList<>();
    for (String key : objectKeys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      String trimmed = key.trim();
      int slash = trimmed.lastIndexOf('/');
      names.add(slash >= 0 ? trimmed.substring(slash + 1) : trimmed);
    }
    return new AttachmentManifest(Collections.unmodifiableList(names));
  }

  public boolean isEmpty() {
    return fileNames.isEmpty();
  }

  public int count() {
    return fileNames.size();
  }
}
