package org.qubership.integration.platform.ai.compiler.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanner;

class CompilerSkillCatalogLoaderTest {

  private final QipKnowledgePackScanner scanner = new QipKnowledgePackScanner();
  private final CompilerSkillCatalogLoader loader = new CompilerSkillCatalogLoader();

  @Test
  void privateMarkerAndPublicApiFalseBecomePrivate(@TempDir Path tempDir) throws Exception {
    writeSkill(
        tempDir,
        "knowledge-curator",
        """
        # knowledge-curator

        ## Metadata

        ```yaml
        name: knowledge-curator
        category: knowledge
        substrate: prompt-library
        public-api: false
        runtime-skill: true
        ```
        """);
    Files.writeString(tempDir.resolve("skills/knowledge-curator/APM_PRIVATE"), "# Private\n");

    CompilerSkillDescriptor descriptor = load(tempDir).find("knowledge-curator").orElseThrow();

    assertEquals(CompilerSkillDisposition.PRIVATE, descriptor.disposition());
    assertFalse(descriptor.runnable());
    assertTrue(descriptor.privateMarker());
    assertFalse(descriptor.publicApi());
  }

  @Test
  void topLevelSpecFileBecomesSpecificationOnly(@TempDir Path tempDir) throws Exception {
    Files.createDirectories(tempDir.resolve("skills"));
    Files.writeString(
        tempDir.resolve("skills/cip-trigger-generator.md"),
        """
        # CIP Trigger Generator

        ## Metadata

        ```yaml
        name: cip-trigger-generator
        category: generation
        compiler-stage: generation
        generator-id: GEN-02
        ```
        """);

    CompilerSkillDescriptor descriptor =
        load(tempDir).find("cip-trigger-generator").orElseThrow();

    assertEquals(CompilerSkillDisposition.SPECIFICATION_ONLY, descriptor.disposition());
    assertFalse(descriptor.runnable());
    assertTrue(descriptor.sourcePaths().contains("skills/cip-trigger-generator.md"));
  }

  @Test
  void runtimeIndexEntryBecomesPublicRuntime(@TempDir Path tempDir) throws Exception {
    writeRuntimeSkillIndex(
        tempDir,
        """
        library:
          name: test
        skills:
          - id: RS-03
            name: cip-chain-generator
            category: runtime
            path: skills/runtime/cip-chain-generator/SKILL.md
            substrate: compiler-runtime-package
            consumes: [compiler-runtime-package/]
            produces: [generated-chain.cip.yaml]
            depends-on: [compiler-build]
        """);

    CompilerSkillDescriptor descriptor = load(tempDir).find("cip-chain-generator").orElseThrow();

    assertEquals(CompilerSkillDisposition.PUBLIC_RUNTIME, descriptor.disposition());
    assertTrue(descriptor.runnable());
    assertEquals("compiler-runtime-package", descriptor.substrate());
    assertEquals("compiler-build", descriptor.dependsOn().get(0));
  }

  @Test
  void validatorCategoryBecomesValidator(@TempDir Path tempDir) throws Exception {
    writeSkillCatalog(
        tempDir,
        """
        normalized-skills:
          - id: SK-18
            name: cip-quality-validator
            category: Validation
            stage: Validation
            dependencies: [cip-chain-assembler]
        """);

    CompilerSkillDescriptor descriptor = load(tempDir).find("cip-quality-validator").orElseThrow();

    assertEquals(CompilerSkillDisposition.VALIDATOR, descriptor.disposition());
    assertTrue(descriptor.runnable());
    assertEquals("cip-chain-assembler", descriptor.dependsOn().get(0));
  }

  @Test
  void boundSkillFolderAndGeneratorSpecBecomePublicRuntime(@TempDir Path tempDir) throws Exception {
    writeTopLevelSpec(
        tempDir,
        "cip-retry-generator",
        """
        # CIP Retry Generator

        ## Metadata

        ```yaml
        name: cip-retry-generator
        category: generation
        compiler-stage: generation
        generator-id: GEN-09
        ```
        """);
    writeSkill(
        tempDir,
        "cip-retry-generator",
        """
        # cip-retry-generator

        ## Metadata

        ```yaml
        name: cip-retry-generator
        category: generation
        ```
        """);
    writeManifest(
        tempDir,
        """
        version: 2
        skills:
          cip-retry-generator:
            generatorId: GEN-09
            wired: true
        """);

    CompilerSkillDescriptor descriptor =
        load(tempDir).find("cip-retry-generator").orElseThrow();

    assertEquals(CompilerSkillDisposition.PUBLIC_RUNTIME, descriptor.disposition());
    assertTrue(descriptor.runnable());
  }

  @Test
  void loaderReturnsAllRuntimeIndexEntries(@TempDir Path tempDir) throws Exception {
    writeRuntimeSkillIndex(
        tempDir,
        """
        library:
          name: test
        skills:
          - id: RS-03
            name: cip-chain-generator
            category: runtime
            path: skills/runtime/cip-chain-generator/SKILL.md
            substrate: compiler-runtime-package
          - id: RS-04
            name: cip-chain-validator
            category: runtime
            path: skills/runtime/cip-chain-validator/SKILL.md
            substrate: compiler-runtime-package
        """);

    CompilerSkillCatalog catalog = load(tempDir);

    assertTrue(catalog.find("cip-chain-generator").isPresent());
    assertTrue(catalog.find("cip-chain-validator").isPresent());
    assertEquals(2, catalog.skills().size());
  }

  private CompilerSkillCatalog load(Path packRoot) {
    QipKnowledgePackScanResult scanResult = scanner.scan(packRoot);
    return loader.load(scanResult);
  }

  private static void writeTopLevelSpec(Path root, String skillId, String content) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(root.resolve("skills/" + skillId + ".md"), content);
  }

  private static void writeSkill(Path root, String skillId, String content) throws Exception {
    Path skillDir = root.resolve("skills").resolve(skillId);
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), content);
  }

  private static void writeRuntimeSkillIndex(Path root, String content) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(root.resolve("skills/RUNTIME_SKILL_INDEX.yaml"), content);
  }

  private static void writeSkillCatalog(Path root, String content) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(root.resolve("skills/skill-catalog.yaml"), content);
  }

  private static void writeManifest(Path root, String content) throws Exception {
    Path addons = root.resolve("addons");
    Files.createDirectories(addons);
    Files.writeString(addons.resolve("manifest.yaml"), content);
  }
}
