package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

class CipKnowledgePackArtifactWriterTest {

  private final CipKnowledgePackIngestionService ingestionService = new CipKnowledgePackIngestionService();

  @Test
  void writeArtifactsCreatesAllBuildOutputs(@TempDir Path outputDir) throws Exception {
    CipKnowledgePackIngestionResult result =
        ingestionService.ingest(CipKnowledgePackFixturePaths.packRoot());

    ingestionService.writeArtifacts(
        result, outputDir, CipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        CipKnowledgePackIndexLoader.resolveVersionDir(outputDir, result.manifest().version());
    assertTrue(Files.isDirectory(versionDir));
    assertTrue(Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.MANIFEST_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE)));
    assertTrue(
        Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE)));
    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, versionDir.getFileName().toString());
  }
}
