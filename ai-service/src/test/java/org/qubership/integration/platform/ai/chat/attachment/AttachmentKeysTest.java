package org.qubership.integration.platform.ai.chat.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentKeysTest {

  @Test
  void splitsNewlineSeparatedKeysAndMarkdownUrls() {
    List<String> raw =
        List.of(
            "sessions/conv/a.json\n"
                + "- http://localhost:8080/api/v1/storage/objects?key=sessions/conv/b.json");

    assertEquals(
        List.of("sessions/conv/a.json", "sessions/conv/b.json"),
        AttachmentKeys.normalize(raw));
  }

  @Test
  void extractsEncodedKeyFromUrl() {
    List<String> raw =
        List.of(
            "http://localhost:8080/api/v1/storage/objects?key=sessions%2Fconv%2Fspace%20file.json");

    assertEquals(List.of("sessions/conv/space file.json"), AttachmentKeys.normalize(raw));
  }

  @Test
  void deduplicatesAndPreservesOrder() {
    List<String> raw = List.of("a.json", "a.json", "b.json");

    assertEquals(List.of("a.json", "b.json"), AttachmentKeys.normalize(raw));
  }

  @Test
  void rejectsUnsafeKeysAndKeepsSafeOnes() {
    List<String> raw = List.of("a/../etc.json", "safe.json", "../escape.json");

    assertEquals(List.of("safe.json"), AttachmentKeys.normalize(raw));
  }

  @Test
  void returnsEmptyListForNullOrEmptyInput() {
    assertEquals(List.of(), AttachmentKeys.normalize(null));
    assertEquals(List.of(), AttachmentKeys.normalize(List.of()));
  }
}
