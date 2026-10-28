package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

class CipKnowledgePackIngestionServiceTest {

  private final CipKnowledgePackIngestionService ingestionService = new CipKnowledgePackIngestionService();

  @ParameterizedTest
  @ValueSource(strings = {"product-pipelines", "runtime/product-pipelines"})
  void incompatiblePackProfileIsExcludedFromCompatibilityReport(
      String pipelineDirectory, @TempDir Path directory) throws Exception {
    Path packRoot = directory.resolve("integration-platform-skills");
    PublicCipKnowledgePackFixture.create(packRoot);
    Path productPipelines = packRoot.resolve(pipelineDirectory);
    if (pipelineDirectory.startsWith("runtime/")) {
      Files.createDirectories(productPipelines.getParent());
      Files.move(packRoot.resolve("product-pipelines"), productPipelines);
      Files.writeString(packRoot.resolve("manifest.json"), "{\"version\":\"1.0.0\"}");
    }
    Files.createDirectories(productPipelines.resolve("profiles"));
    Files.writeString(productPipelines.resolve("profiles/create-chain-v2.yaml"), """
        schemaVersion: 1
        profileId: create-chain
        profileVersion: "2"
        compilerPipeline:
          supportedIndexSchemas: [2]
          allowedPhases: [Discovery]
          requiredTerminalArtifacts:
            - { type: unavailable-terminal, schemaVersion: 1 }
        """);
    Path output = directory.resolve("output");

    ingestionService.writeArtifacts(
        ingestionService.ingest(packRoot), output, packRoot.resolve("addons"));

    Path versionDir = output.resolve(CipKnowledgePackVersion.fromPath(packRoot).normalized());
    var report = new CipKnowledgePackIndexLoader().loadPipelineCompatibilityReport(versionDir);
    assertFalse(report.compatibleProfileVersions().contains("create-chain@2"),
        () -> "Compatible profiles: " + report.compatibleProfileVersions());
  }

  @Test
  void ingestsFixturePack() {
    CipKnowledgePackIngestionResult result =
        ingestionService.ingest(CipKnowledgePackFixturePaths.packRoot());

    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, result.manifest().version().normalized());
    assertTrue(result.manifest().skillIds().contains("cip-error-handling-generator"));
    assertTrue(
        result.unsupportedItems().stream()
            .anyMatch(
                item ->
                    item.id().equals("cip-folder-organizer")
                        || item.id().equals("cip-deployment-packager")));
    String report = result.compatibilityReportMarkdown();
    assertTrue(report.contains(CipKnowledgePackFixturePaths.PACK_DIR));
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
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("pipeline-index-build-test");
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();

    CipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        CipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        outputDir.resolve(CipKnowledgePackVersion.fromPath(packRoot).normalized());
    Path indexFile = versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE);
    assertTrue(Files.isRegularFile(indexFile));

    CompilerPipelineIndex index =
        new CipKnowledgePackIndexLoader().loadCompilerPipelineIndex(versionDir);
    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, index.packVersion().normalized());
    assertTrue(
        index.entries().stream()
            .anyMatch(entry -> "cip-error-handling-generator".equals(entry.skillId())));

    String report =
        Files.readString(versionDir.resolve(CipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE));
    assertFalse(report.contains("## Knowledge source"));
    assertFalse(report.contains("## Compiler pipeline index"));
  }

  @Test
  void writesProductionCompilerIndexesDuringPackBuild() throws Exception {
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("production-index-build-test");
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();

    CipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        CipKnowledgePackFixturePaths.addonRoot());

    Path versionDir =
        outputDir.resolve(CipKnowledgePackVersion.fromPath(packRoot).normalized());
    CipKnowledgePackIndexLoader loader = new CipKnowledgePackIndexLoader();
    CompilerSkillCatalog skillCatalog = loader.loadCompilerSkillCatalog(versionDir);
    CompilerGeneratorSpecIndex specIndex = loader.loadCompilerGeneratorSpecIndex(versionDir);
    CompilerRuntimePackageIndex runtimePackageIndex = loader.loadCompilerRuntimePackageIndex(versionDir);

    assertTrue(Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE)));
    assertFalse(skillCatalog.skills().isEmpty());
    assertFalse(specIndex.specs().isEmpty());
    assertTrue(runtimePackageIndex.artifacts().isEmpty());
  }
}
