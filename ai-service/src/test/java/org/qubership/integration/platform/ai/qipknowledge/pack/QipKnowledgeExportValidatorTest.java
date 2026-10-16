package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QipKnowledgeExportValidatorTest {

  @Test
  void acceptsCertifiedCompatibleExport(@TempDir Path root) throws Exception {
    Path export = PublicQipKnowledgePackFixture.createExport(root);

    var metadata = QipKnowledgeExportValidator.validate(export);

    assertEquals("CIP@1.2.3", metadata.packageKey());
    assertEquals("1.2.3", metadata.knowledgeVersion());
    assertEquals(1, metadata.totalObjects());
  }

  @Test
  void rejectsChecksumMismatch(@TempDir Path root) throws Exception {
    Path export = PublicQipKnowledgePackFixture.createExport(root);
    Files.writeString(export.resolve("objects.jsonl"), "{\"id\":\"changed\"}\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> QipKnowledgeExportValidator.validate(export));

    assertEquals(
        "Invalid certified knowledge export: Checksum mismatch for objects.jsonl",
        error.getMessage());
  }
}
