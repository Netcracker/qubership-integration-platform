package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QipKnowledgeExportValidatorTest {

  @Test
  void acceptsCertifiedCompatibleExport(@TempDir Path root) throws Exception {
    Path export = createExport(root);

    var metadata = QipKnowledgeExportValidator.validate(export);

    assertEquals("CIP@1.2.3", metadata.packageKey());
    assertEquals("1.2.3", metadata.knowledgeVersion());
    assertEquals(1, metadata.totalObjects());
  }

  @Test
  void rejectsChecksumMismatch(@TempDir Path root) throws Exception {
    Path export = createExport(root);
    Files.writeString(export.resolve("objects.jsonl"), "{\"id\":\"changed\"}\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> QipKnowledgeExportValidator.validate(export));

    assertEquals(
        "Invalid certified knowledge export: Checksum mismatch for objects.jsonl",
        error.getMessage());
  }

  private static Path createExport(Path root) throws Exception {
    Path export = root.resolve("knowledge-export");
    Path capabilities = export.resolve("capabilities");
    Files.createDirectories(capabilities);
    write(
        export.resolve("manifest.yaml"),
        """
        manifest_schema: "1.0"
        product: CIP
        knowledge_version: "1.2.3"
        compiler_version: "2.0.0"
        runtime_sdk_version: ">=1.0.0 <2.0"
        schema_version: "1.0.0"
        relation_schema_version: "1.0.0"
        total_objects: 1
        integrity:
          package_checksum: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        """);
    write(export.resolve("objects.jsonl"), "{\"id\":\"OBJ-1\"}\n");
    write(capabilities.resolve("capability-catalog.yaml"), "capabilities:\n  - id: auth\n");
    write(
        capabilities.resolve("capability-index.yaml"),
        "capability_index:\n  token_to_capability:\n    auth: auth\n");
    write(
        capabilities.resolve("capability-relations.yaml"),
        "capability_relations:\n  auth:\n    contains:\n      - OBJ-1\n");
    write(
        export.resolve("runtime-certification.yaml"),
        """
        certification:
          status: CERTIFIED
          package:
            key: CIP@1.2.3
            manifest_hash: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
          total_objects: 1
        """);

    List<String> checkedFiles =
        List.of(
            "manifest.yaml",
            "objects.jsonl",
            "capabilities/capability-catalog.yaml",
            "capabilities/capability-index.yaml",
            "capabilities/capability-relations.yaml");
    StringBuilder checksums = new StringBuilder();
    for (String name : checkedFiles) {
      checksums.append(sha256(export.resolve(name))).append("  ").append(name).append('\n');
    }
    write(export.resolve("CHECKSUMS.sha256"), checksums.toString());
    return export;
  }

  private static void write(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
