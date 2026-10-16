package org.qubership.integration.platform.ai.compiler.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanner;

class CompilerGeneratorSpecIndexBuilderTest {

  private final QipKnowledgePackScanner scanner = new QipKnowledgePackScanner();
  private final CompilerGeneratorSpecIndexBuilder builder = new CompilerGeneratorSpecIndexBuilder();

  @Test
  void mapsTopLevelGeneratorSpecToGeneratorId(@TempDir Path tempDir) throws Exception {
    writeTopLevelSpec(
        tempDir,
        "cip-trigger-generator",
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

    CompilerGeneratorSpec spec =
        build(tempDir).findBySkillName("cip-trigger-generator").orElseThrow();

    assertEquals("GEN-02", spec.generatorId());
    assertEquals("generation", spec.compilerStage());
    assertTrue(spec.hasGeneratorId());
  }

  @Test
  void mergesNormalizedCatalogDependencies(@TempDir Path tempDir) throws Exception {
    writeTopLevelSpec(
        tempDir,
        "cip-routing-generator",
        """
        # CIP Routing Generator

        ## Metadata

        ```yaml
        name: cip-routing-generator
        category: generation
        compiler-stage: generation
        generator-id: GEN-07
        ```
        """);
    writeSkillCatalog(
        tempDir,
        """
        normalized-skills:
          - id: SK-07
            name: cip-routing-generator
            category: Generator
            stage: Generation
            inputs: [Chain Structure]
            outputs: [Routing Configuration]
            dependencies: [cip-structure-generator]
            generated-artifacts: [routing.yaml]
            supported-elements: [condition, if, else]
        """);

    CompilerGeneratorSpec spec =
        build(tempDir).findByGeneratorId("GEN-07").orElseThrow();

    assertEquals("cip-routing-generator", spec.skillName());
    assertEquals("cip-structure-generator", spec.dependencies().get(0));
    assertEquals("routing.yaml", spec.generatedArtifacts().get(0));
    assertEquals("condition", spec.supportedElements().get(0));
  }

  @Test
  void missingGeneratorIdIsReportedBySpecState(@TempDir Path tempDir) throws Exception {
    writeTopLevelSpec(
        tempDir,
        "cip-quality-validator",
        """
        # CIP Quality Validator

        ## Metadata

        ```yaml
        name: cip-quality-validator
        category: validation
        compiler-stage: validation
        ```
        """);

    CompilerGeneratorSpec spec =
        build(tempDir).findBySkillName("cip-quality-validator").orElseThrow();

    assertFalse(spec.hasGeneratorId());
    assertEquals("validation", spec.category());
  }

  @Test
  void mapsSkillFolderGeneratorIdFromFrontmatter(@TempDir Path tempDir) throws Exception {
    writeSkillFolder(
        tempDir,
        "cip-mcp-trigger-generator",
        """
        ---
        generator-id: GEN-18
        category: generation
        ---

        # cip-mcp-trigger-generator
        """);

    CompilerGeneratorSpec spec =
        build(tempDir).findByGeneratorId("GEN-18").orElseThrow();

    assertEquals("cip-mcp-trigger-generator", spec.skillName());
    assertTrue(spec.hasGeneratorId());
  }

  private CompilerGeneratorSpecIndex build(Path packRoot) {
    QipKnowledgePackScanResult scanResult = scanner.scan(packRoot);
    return builder.build(scanResult);
  }

  private static void writeSkillFolder(Path root, String skillId, String content) throws Exception {
    Path skillDir = root.resolve("skills").resolve(skillId);
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), content);
  }

  private static void writeTopLevelSpec(Path root, String skillId, String content)
      throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(root.resolve("skills").resolve(skillId + ".md"), content);
  }

  private static void writeSkillCatalog(Path root, String content) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(root.resolve("skills/skill-catalog.yaml"), content);
  }
}
