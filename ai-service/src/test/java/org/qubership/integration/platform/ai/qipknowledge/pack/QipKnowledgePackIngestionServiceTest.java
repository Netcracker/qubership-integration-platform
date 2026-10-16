package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class QipKnowledgePackIngestionServiceTest {

  private final QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();

  @Test
  void ingestsFixturePack() {
    QipKnowledgePackIngestionResult result =
        ingestionService.ingest(QipKnowledgePackFixturePaths.packRoot());

    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, result.manifest().version().normalized());
    assertTrue(result.manifest().skillIds().contains("cip-error-handling-generator"));
    assertTrue(
        result.unsupportedItems().stream()
            .anyMatch(
                item ->
                    item.id().equals("cip-folder-organizer")
                        || item.id().equals("cip-deployment-packager")));
    String report = result.compatibilityReportMarkdown();
    assertTrue(report.contains(QipKnowledgePackFixturePaths.PACK_DIR));
    assertTrue(report.contains("Skills:"));
    assertTrue(report.contains("Unsupported:"));
    assertTrue(report.contains("## Compiler skill catalog"));
    assertTrue(report.contains("PUBLIC_RUNTIME:"));
    assertFalse(result.registry().capabilities().isEmpty());
    assertTrue(
        result.files().stream()
            .anyMatch(file -> file.relativePath().equals("skills/cip-security-generator/SKILL.md")));
  }

  @Test
  void writesCompilerPipelineIndexDuringPackBuild() throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("pipeline-index-build-test");
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();

    QipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        QipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        outputDir.resolve(QipKnowledgePackVersion.fromPath(packRoot).normalized());
    Path indexFile = versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE);
    assertTrue(Files.isRegularFile(indexFile));

    CompilerPipelineIndex index =
        new QipKnowledgePackIndexLoader().loadCompilerPipelineIndex(versionDir);
    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, index.packVersion().normalized());
    assertTrue(
        index.entries().stream()
            .anyMatch(entry -> "cip-error-handling-generator".equals(entry.skillId())));

    String report =
        Files.readString(versionDir.resolve(QipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE));
    assertFalse(report.contains("## Knowledge source"));
    assertFalse(report.contains("## Compiler pipeline index"));
  }

  @Test
  void writesProductionCompilerIndexesDuringPackBuild() throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("production-index-build-test");
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();

    QipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        QipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        outputDir.resolve(QipKnowledgePackVersion.fromPath(packRoot).normalized());
    QipKnowledgePackIndexLoader loader = new QipKnowledgePackIndexLoader();
    CompilerSkillCatalog skillCatalog = loader.loadCompilerSkillCatalog(versionDir);
    CompilerGeneratorSpecIndex specIndex = loader.loadCompilerGeneratorSpecIndex(versionDir);
    CompilerRuntimePackageIndex runtimePackageIndex = loader.loadCompilerRuntimePackageIndex(versionDir);

    assertTrue(Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE)));
    assertFalse(skillCatalog.skills().isEmpty());
    assertFalse(specIndex.specs().isEmpty());
    assertTrue(runtimePackageIndex.artifacts().isEmpty());
  }
}
