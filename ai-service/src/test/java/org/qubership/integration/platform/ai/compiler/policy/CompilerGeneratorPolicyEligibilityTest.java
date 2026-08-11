package org.qubership.integration.platform.ai.compiler.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalogLoader;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

class CompilerGeneratorPolicyEligibilityTest {

  private final QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();

  @Test
  void excludedCatalogDispositionsCannotEnterPolicy(@TempDir Path tempDir) throws Exception {
    writeKnowledgeFiles(tempDir);
    writePrivateSkill(tempDir);
    writeBuildTimeSkill(tempDir);
    writeSpecificationOnlySkill(tempDir);
    writeAllowedGenerator(tempDir);

    var result = ingestionService.ingest(tempDir);
    CompilerGeneratorPolicy policy =
        CompilerGeneratorPolicyTestSupport.buildPolicy(tempDir, result, null);

    assertTrue(
        policy.generators().stream().anyMatch(descriptor -> "allowed-generator".equals(descriptor.skillId())),
        "Runnable bound generator must appear in policy");
    assertFalse(
        policy.generators().stream()
            .anyMatch(descriptor -> "excluded-private-generator".equals(descriptor.skillId())),
        "PRIVATE catalog skill must not enter policy");
    assertFalse(
        policy.generators().stream()
            .anyMatch(descriptor -> "excluded-build-generator".equals(descriptor.skillId())),
        "BUILD_TIME catalog skill must not enter policy");
    assertFalse(
        policy.generators().stream()
            .anyMatch(descriptor -> "excluded-spec-only-generator".equals(descriptor.skillId())),
        "SPECIFICATION_ONLY catalog skill must not enter policy");
  }

  @Test
  void catalogExclusionsRemoveCapabilitiesFromRegistry(@TempDir Path tempDir) throws Exception {
    writeKnowledgeFiles(tempDir);
    writePrivateSkill(tempDir);
    writeBuildTimeSkill(tempDir);
    writeAllowedGenerator(tempDir);

    CapabilityRegistry registry = ingestionService.ingest(tempDir).registry();

    assertFalse(findCapability(registry, "excluded-private-generator").supported());
    assertFalse(findCapability(registry, "excluded-build-generator").supported());
    assertTrue(findCapability(registry, "allowed-generator").supported());
  }

  @Test
  void boundSkillFolderAndSpecBecomePublicRuntime(@TempDir Path tempDir) throws Exception {
    writeKnowledgeFiles(tempDir);
    writeAllowedGenerator(tempDir);

    var ingestResult = ingestionService.ingest(tempDir);
    var catalog =
        new CompilerSkillCatalogLoader()
            .load(new QipKnowledgePackScanResult(tempDir, ingestResult.manifest().version(), ingestResult.files()));

    var descriptor = catalog.find("allowed-generator").orElseThrow();
    assertEquals(CompilerSkillDisposition.PUBLIC_RUNTIME, descriptor.disposition());
    assertTrue(descriptor.runnable());
  }

  private static CapabilityDescriptor findCapability(CapabilityRegistry registry, String skillId) {
    return registry.capabilities().stream()
        .filter(capability -> skillId.equals(capability.id()))
        .findFirst()
        .orElseThrow();
  }


  private static void writeKnowledgeFiles(Path root) throws Exception {
    Path knowledge = root.resolve("knowledge");
    Path fixture = Path.of("src/test/resources/qip-knowledge-fixture");
    copyTree(fixture, knowledge);
    Path knowledgeDir = knowledge.resolve("ai");
    Files.writeString(
        knowledgeDir.resolve("GENERATOR_CONTRACTS.md"),
        """
        ## GEN-90: Excluded Private Generator

        ## GEN-91: Excluded Build Time Generator

        ## GEN-92: Excluded Specification Only Generator

        ## GEN-93: Allowed Generator

        ## Generator Execution Order

        ```
        90. GEN-90 Excluded Private Generator
        91. GEN-91 Excluded Build Time Generator
        92. GEN-92 Excluded Specification Only Generator
        93. GEN-93 Allowed Generator
        ```
        """);
    Files.writeString(
        knowledgeDir.resolve("generator-rule-mapping.md"),
        """
        ## Generator Summary

        | Generator | Rules Owned | Rule IDs |
        |-----------|------------|----------|
        | GEN-90 Excluded Private | 1 | R-901 |
        | GEN-91 Excluded Build Time | 1 | R-902 |
        | GEN-92 Excluded Specification Only | 1 | R-903 |
        | GEN-93 Allowed Generator | 1 | R-904 |
        """);
    Files.writeString(knowledgeDir.resolve("validation-rules.yaml"), "rules: []\n");
  }

  private static void copyTree(Path source, Path target) throws Exception {
    try (var walk = Files.walk(source)) {
      for (Path path : walk.toList()) {
        Path relative = source.relativize(path);
        Path destination = target.resolve(relative.toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(destination.getParent());
          Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void writePrivateSkill(Path root) throws Exception {
    writeTopLevelSpec(root, "excluded-private-generator", "GEN-90");
    writeSkillFolder(
        root,
        "excluded-private-generator",
        """
        # excluded-private-generator

        ## Metadata

        ```yaml
        name: excluded-private-generator
        category: generation
        public-api: false
        runtime-skill: true
        ```
        """);
    Files.writeString(root.resolve("skills/excluded-private-generator/APM_PRIVATE"), "# Private\n");
  }

  private static void writeBuildTimeSkill(Path root) throws Exception {
    writeTopLevelSpec(root, "excluded-build-generator", "GEN-91");
    writeSkillFolder(
        root,
        "excluded-build-generator",
        """
        # excluded-build-generator

        ## Metadata

        ```yaml
        name: excluded-build-generator
        category: compiler
        substrate: prompt-library
        ```
        """);
  }

  private static void writeSpecificationOnlySkill(Path root) throws Exception {
    writeTopLevelSpec(root, "excluded-spec-only-generator", "GEN-92");
  }

  private static void writeAllowedGenerator(Path root) throws Exception {
    writeTopLevelSpec(root, "allowed-generator", "GEN-93");
    writeSkillFolder(
        root,
        "allowed-generator",
        """
        # allowed-generator

        ## Metadata

        ```yaml
        name: allowed-generator
        category: generation
        ```
        """);
  }

  private static void writeTopLevelSpec(Path root, String skillId, String generatorId) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.writeString(
        root.resolve("skills/" + skillId + ".md"),
        """
        # %s

        ## Metadata

        ```yaml
        name: %s
        category: generation
        compiler-stage: generation
        generator-id: %s
        ```
        """
            .formatted(skillId, skillId, generatorId));
  }

  private static void writeSkillFolder(Path root, String skillId, String content) throws Exception {
    Path skillDir = root.resolve("skills").resolve(skillId);
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), content);
  }
}
