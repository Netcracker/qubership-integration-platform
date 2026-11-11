package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CipKnowledgeExportValidatorTest {

  @Test
  void acceptsCertifiedCompatibleExport(@TempDir Path root) throws Exception {
    Path export = PublicCipKnowledgePackFixture.createExport(root);

    var metadata = CipKnowledgeExportValidator.validate(export);

    assertEquals("CIP@1.2.3", metadata.packageKey());
    assertEquals("1.2.3", metadata.knowledgeVersion());
    assertEquals(1, metadata.totalObjects());
  }

  @Test
  void rejectsChecksumMismatch(@TempDir Path root) throws Exception {
    Path export = PublicCipKnowledgePackFixture.createExport(root);
    Files.writeString(export.resolve("objects.jsonl"), "{\"id\":\"changed\"}\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> CipKnowledgeExportValidator.validate(export));

    assertEquals(
        "Invalid certified knowledge export: Checksum mismatch for objects.jsonl",
        error.getMessage());
  }

  @Test
  void rejectsRehashedContentWithAnOldExportCertificate(@TempDir Path root) throws Exception {
    Path export = PublicCipKnowledgePackFixture.createExport(root);
    List<String> payload = List.of("capabilities/capability-catalog.yaml", "capabilities/capability-index.yaml",
        "capabilities/capability-relations.yaml", "objects.jsonl");
    Path manifest = export.resolve("manifest.yaml");
    Path certificate = export.resolve("runtime-certification.yaml");
    assertEquals(1, CipKnowledgeExportValidator.validate(export).totalObjects());
    String certifiedManifest = Files.readString(manifest);
    String certifiedCertificate = Files.readString(certificate);
    Files.writeString(manifest, certifiedManifest.replace("  checksum_algorithm: rocky-export-v1\n", ""));
    Files.writeString(certificate, certifiedCertificate.replace("  profile: rocky-export-v1\n", ""));
    rewriteChecksums(export, payload);
    assertEquals("Invalid certified knowledge export: Unsupported knowledge certification profile",
        assertThrows(IllegalArgumentException.class, () -> CipKnowledgeExportValidator.validate(export)).getMessage());
    Files.writeString(manifest, certifiedManifest);
    Files.writeString(certificate, certifiedCertificate);
    rewriteChecksums(export, payload);

    Path store = Files.createDirectory(export.resolve("lancedb"));
    Files.writeString(store.resolve("knowledge_objects.jsonl"), "{\"id\":\"OBJ-1\"}\n");
    assertEquals("Invalid certified knowledge export: Certified export must not contain an unbound LanceDB store",
        assertThrows(IllegalArgumentException.class, () -> CipKnowledgeExportValidator.validate(export)).getMessage());
    Files.delete(store.resolve("knowledge_objects.jsonl"));
    Files.delete(store);

    Files.writeString(manifest, Files.readString(manifest).replace("1.2.3", "1.2.4"));
    rewriteChecksums(export, payload);
    assertEquals("Invalid certified knowledge export: Certification package identity does not match the manifest",
        assertThrows(IllegalArgumentException.class, () -> CipKnowledgeExportValidator.validate(export)).getMessage());
    Files.writeString(manifest, Files.readString(manifest).replace("1.2.4", "1.2.3"));
    rewriteChecksums(export, payload);

    Files.writeString(manifest, certifiedManifest + "embedding_model: {dim: 128}\n");
    rewriteChecksums(export, payload);
    assertEquals("Invalid certified knowledge export: Certified manifest checksum does not match the contents",
        assertThrows(IllegalArgumentException.class, () -> CipKnowledgeExportValidator.validate(export)).getMessage());
    Files.writeString(manifest, certifiedManifest);
    rewriteChecksums(export, payload);

    Files.writeString(certificate, Files.readString(certificate).replace("IR: PASS", "IR: FAIL"));
    assertEquals("Invalid certified knowledge export: Knowledge certification gate did not pass: IR",
        assertThrows(IllegalArgumentException.class, () -> CipKnowledgeExportValidator.validate(export)).getMessage());
    Files.writeString(certificate, Files.readString(certificate).replace("IR: FAIL", "IR: PASS"));

    Files.writeString(export.resolve("objects.jsonl"), "{\"id\":\"OBJ-1\",\"title\":\"changed\"}\n");
    rewriteChecksums(export, payload);
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> CipKnowledgeExportValidator.validate(export));
    assertEquals("Invalid certified knowledge export: Certified package checksum does not match the contents",
        error.getMessage());
  }

  private static void rewriteChecksums(Path root, List<String> payload) throws Exception {
    var entries = new java.util.ArrayList<>(payload);
    entries.add("manifest.yaml");
    StringBuilder text = new StringBuilder();
    for (String name : entries) text.append(fileHash(root.resolve(name))).append("  ").append(name).append('\n');
    Files.writeString(root.resolve("CHECKSUMS.sha256"), text);
  }

  private static String fileHash(Path path) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

}
