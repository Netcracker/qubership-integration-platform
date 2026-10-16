package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class QipKnowledgePackArtifactWriterTest {

  private final QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();

  @Test
  void writeArtifactsCreatesAllBuildOutputs(@TempDir Path outputDir) throws Exception {
    QipKnowledgePackIngestionResult result =
        ingestionService.ingest(QipKnowledgePackFixturePaths.packRoot());

    ingestionService.writeArtifacts(
        result, outputDir, QipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        QipKnowledgePackIndexLoader.resolveVersionDir(outputDir, result.manifest().version());
    assertTrue(Files.isDirectory(versionDir));
    assertTrue(Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.MANIFEST_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE)));
    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, versionDir.getFileName().toString());
  }
}
