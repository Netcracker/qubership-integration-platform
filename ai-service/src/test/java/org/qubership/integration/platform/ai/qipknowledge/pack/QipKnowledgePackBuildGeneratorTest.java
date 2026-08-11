package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonIndex;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class QipKnowledgePackBuildGeneratorTest {

  @Test
  void generateWritesVersionedIndexes(@TempDir Path outputDir) throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    QipKnowledgePackBuildGenerator.generate(
        packRoot, outputDir, QipKnowledgePackFixturePaths.addonRoot());

    Path versionDir = outputDir.resolve(QipKnowledgePackFixturePaths.PACK_DIR);
    assertTrue(Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.MANIFEST_FILE)));
        assertTrue(
        Files.isRegularFile(versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(QipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE)));
    QipKnowledgePackRepository repository =
        new FilesystemQipKnowledgePackRepository(outputDir, QipKnowledgePackFixturePaths.packVersion());
    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, repository.loadManifest().version().normalized());
    assertFalse(repository.loadCompilerSkillCatalog().skills().isEmpty());
    assertFalse(repository.loadCompilerGeneratorSpecIndex().specs().isEmpty());
    CompilerRuntimePackageIndex runtimePackageIndex = repository.loadCompilerRuntimePackageIndex();
    assertTrue(runtimePackageIndex.artifacts() != null);

    Path addonIndexFile =
        versionDir
            .resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR)
            .resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE);
    assertTrue(Files.isRegularFile(addonIndexFile));
    CompilerSkillAddonIndex addonIndex =
        new ObjectMapper()
            .readValue(Files.readString(addonIndexFile), CompilerSkillAddonIndex.class);
    assertTrue(addonIndex.skills().containsKey("cip-requirement-analyzer"));
    assertFalse(addonIndex.skills().containsKey("plan-validator"));

    var requirementAnalyzer =
        repository.loadCapabilityRegistry().capabilities().stream()
            .filter(capability -> "cip-requirement-analyzer".equals(capability.id()))
            .findFirst()
            .orElseThrow();
    assertTrue(requirementAnalyzer.supported());
    var catalogEntry =
        repository.loadCompilerSkillCatalog().find("cip-requirement-analyzer").orElseThrow();
    assertEquals("PUBLIC_RUNTIME", catalogEntry.disposition().name());
    assertEquals("runtime", catalogEntry.category());
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-requirement-analyzer"));
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-pattern-selector"));
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-structure-generator"));
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-design-planner"));
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-design-executor"));

    var designPlanner =
        repository.loadCapabilityRegistry().capabilities().stream()
            .filter(capability -> "cip-design-planner".equals(capability.id()))
            .findFirst()
            .orElseThrow();
    assertTrue(designPlanner.supported());
    assertEquals(
        "PUBLIC_RUNTIME",
        repository.loadCompilerSkillCatalog().find("cip-design-planner").orElseThrow().disposition()
            .name());
    var designExecutor =
        repository.loadCapabilityRegistry().capabilities().stream()
            .filter(capability -> "cip-design-executor".equals(capability.id()))
            .findFirst()
            .orElseThrow();
    assertTrue(designExecutor.supported());
    assertEquals(
        "PUBLIC_RUNTIME",
        repository
            .loadCompilerSkillCatalog()
            .find("cip-design-executor")
            .orElseThrow()
            .disposition()
            .name());
    assertTrue(
        repository.loadManifest().supportedCapabilityIds().contains("cip-design-planner"));
    assertTrue(
        repository.loadManifest().supportedCapabilityIds().contains("cip-design-executor"));

    var pipelineIndex = repository.loadCompilerPipelineIndex();
    assertEquals(2, pipelineIndex.schemaVersion());
    assertFalse(pipelineIndex.dependencies().isEmpty());
    assertFalse(pipelineIndex.nodes().isEmpty());
  }
}
