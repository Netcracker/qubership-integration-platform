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
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.support.ElementSupportStatus;

class CipKnowledgePackBuildGeneratorTest {

  @Test
  void generateResolvesRepositoryRootForCustomerLayout(@TempDir Path repositoryRoot)
      throws Exception {
    Path fixtureRoot = repositoryRoot.resolve("fixture-pack");
    PublicCipKnowledgePackFixture.create(fixtureRoot);
    Path capability = fixtureRoot.resolve("product-pipelines/capabilities/planning.yaml");
    Files.writeString(
        capability,
        Files.readString(capability)
            .replace("skills:\n  required: [cip-auth-generator]", "skills:\n  required: [plan-validator]"));
    Path rolePrompt =
        repositoryRoot.resolve("ai-service/src/main/resources/prompts/roles/plan-validator.md");
    Files.createDirectories(rolePrompt.getParent());
    Files.writeString(rolePrompt, "# Plan validator fixture\n");
    Path packRoot = repositoryRoot.resolve("integration-platform-skills/rocky-skills");
    Files.createDirectories(packRoot.getParent());
    Files.move(fixtureRoot, packRoot);
    Path outputDir = repositoryRoot.resolve("output");

    CipKnowledgePackBuildGenerator.generate(packRoot, outputDir, packRoot.resolve("addons"));

    Path versionDir = outputDir.resolve(CipKnowledgePackVersion.fromPath(packRoot).normalized());
    var index = new CipKnowledgePackIndexLoader().loadProductPipelinePackageIndex(versionDir);
    assertEquals(
        "ai-service/src/main/resources/prompts/roles/plan-validator.md",
        index.dependencyPins().stream()
            .filter(pin -> "plan-validator".equals(pin.dependencyId()))
            .findFirst()
            .orElseThrow()
            .path());
  }

  @Test
  void generateWritesVersionedIndexes(@TempDir Path outputDir) throws Exception {
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();
    CipKnowledgePackBuildGenerator.generate(
        packRoot, outputDir, CipKnowledgePackFixturePaths.addonRoot());

    boolean standalone = Files.isRegularFile(packRoot.resolve("runtime/generator-contracts.json"));
    Path versionDir = outputDir.resolve(CipKnowledgePackFixturePaths.PACK_DIR);
    assertTrue(Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.MANIFEST_FILE)));
        assertTrue(
        Files.isRegularFile(versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE)));
    assertTrue(
        Files.isRegularFile(
            versionDir.resolve(CipKnowledgePackIndexLoader.ELEMENT_SUPPORT_MATRIX_FILE)));
    CipKnowledgePackRepository repository =
        new FilesystemCipKnowledgePackRepository(outputDir, CipKnowledgePackFixturePaths.packVersion());
    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, repository.loadManifest().version().normalized());
    assertFalse(repository.loadCompilerSkillCatalog().skills().isEmpty());
    assertFalse(repository.loadCompilerGeneratorSpecIndex().specs().isEmpty());
    CompilerRuntimePackageIndex runtimePackageIndex = repository.loadCompilerRuntimePackageIndex();
    assertTrue(runtimePackageIndex.artifacts() != null);

    Path addonIndexFile =
        versionDir
            .resolve(standalone ? CompilerSkillAddonBuildSupport.RUNTIME_DIR : CompilerSkillAddonBuildSupport.ADDONS_DIR)
            .resolve(standalone ? CompilerSkillAddonBuildSupport.RUNTIME_INDEX_FILE : CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE);
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
    assertTrue(repository.loadRuntimePromotedSkillIds().contains("cip-chain-edit-planner"));

    assertTrue(repository.loadCapabilityRegistry().capabilities().stream()
        .anyMatch(capability -> "cip-design-planner".equals(capability.id())));
    if (standalone) {
      assertTrue(repository.loadRagIngestionManifest().chunks().stream()
          .noneMatch(chunk -> chunk.sourcePath().equals("skills/cip-design-planner/SKILL.md")));
    }
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
    assertTrue(
        repository.loadManifest().supportedCapabilityIds().contains("cip-chain-edit-planner"));

    var pipelineIndex = repository.loadCompilerPipelineIndex();
    assertEquals(2, pipelineIndex.schemaVersion());
    assertFalse(pipelineIndex.dependencies().isEmpty());
    assertFalse(pipelineIndex.nodes().isEmpty());

    var supportMatrix = repository.loadElementSupportMatrix();
    assertFalse(supportMatrix.elements().isEmpty());

    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    assertEquals(contract.contractVersion(), repository.loadManifest().compilerContractVersion());
    assertEquals(contract.sha256(), repository.loadManifest().compilerContractSha256());
    assertTrue(repository.loadManifest().addonSha256().containsKey("cip-design-executor"));
    assertTrue(repository.loadManifest().addonSha256().containsKey("cip-structure-generator"));
  }
  @Test
  @org.junit.jupiter.api.Tag("certified-pack")
  void certifiedPackSupportsCatalogElements() throws Exception {
    var supportMatrix = CipKnowledgePackTestSupport.installPack().repository().loadElementSupportMatrix();
    assertEquals(ElementSupportStatus.UNSUPPORTED, supportMatrix.require("scs-sender").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("reuse-reference").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("condition").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("else").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("chain-call-2").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("context-storage").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("file-write").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("log-record").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("checkpoint").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("http-trigger").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("service-call").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("split-async-2").status());
    assertEquals(
        ElementSupportStatus.SUPPORTED,
        supportMatrix.require("try-catch-finally-2").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("split-element-2").status());
    assertEquals(
        ElementSupportStatus.SUPPORTED,
        supportMatrix.require("async-split-element-2").status());
    assertEquals(
        ElementSupportStatus.SUPPORTED,
        supportMatrix.require("header-modification").status());
    assertEquals(ElementSupportStatus.SUPPORTED, supportMatrix.require("reuse").status());

  }

}
