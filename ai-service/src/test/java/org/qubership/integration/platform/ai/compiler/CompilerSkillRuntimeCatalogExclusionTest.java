package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineEntry;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemQipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class CompilerSkillRuntimeCatalogExclusionTest {

  private static final String PRIVATE_SKILL = "excluded-private-generator";
  private static final String BUILD_TIME_SKILL = "excluded-build-generator";
  private static final String SPEC_ONLY_SKILL = "excluded-spec-only-generator";
  private static final String ALLOWED_SKILL = "allowed-generator";

  private final QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private QipKnowledgePackRepository repository;
  private QipKnowledgePackVersion version;
  private Path outputDir;

  @BeforeEach
  void setUp(@TempDir Path packRoot, @TempDir Path outputDir) throws Exception {
    this.outputDir = outputDir;
    writeKnowledgeFiles(packRoot);
    writePrivateSkill(packRoot);
    writeBuildTimeSkill(packRoot);
    writeSpecificationOnlySkill(packRoot);
    writeAllowedGenerator(packRoot);

    QipKnowledgePackIngestionResult result = ingestionService.ingest(packRoot);
    version = result.manifest().version();
    ingestionService.writeArtifacts(result, outputDir);
    injectStalePipelineEntries(outputDir, version);

    repository = new FilesystemQipKnowledgePackRepository(outputDir, version);
  }

  @Test
  void repositoryLoadsCompilerSkillCatalogAndIndexes() {
    var catalog = repository.loadCompilerSkillCatalog();
    assertEquals(
        CompilerSkillDisposition.PRIVATE,
        catalog.find(PRIVATE_SKILL).orElseThrow().disposition());
    assertEquals(
        CompilerSkillDisposition.BUILD_TIME,
        catalog.find(BUILD_TIME_SKILL).orElseThrow().disposition());
    assertEquals(
        CompilerSkillDisposition.SPECIFICATION_ONLY,
        catalog.find(SPEC_ONLY_SKILL).orElseThrow().disposition());
    assertEquals(
        CompilerSkillDisposition.PUBLIC_RUNTIME,
        catalog.find(ALLOWED_SKILL).orElseThrow().disposition());

    assertTrue(repository.loadCompilerGeneratorSpecIndex().findBySkillName(ALLOWED_SKILL).isPresent());
    assertTrue(repository.loadCompilerRuntimePackageIndex().artifacts().isEmpty());
  }

  @Test
  void excludedSkillsAreFilteredFromGenerationTraversal() {
    CompilerSkillRuntimeEligibility eligibility =
        new CompilerSkillRuntimeEligibility(repository);
    List<String> generationSkillIds =
        CompilerPipelineIndexSupport.generationSkillIds(repository.loadCompilerPipelineIndex())
            .stream()
            .filter(eligibility::allowsRuntimeAccess)
            .toList();

    assertTrue(generationSkillIds.contains(ALLOWED_SKILL));
    assertFalse(generationSkillIds.contains(PRIVATE_SKILL));
    assertFalse(generationSkillIds.contains(BUILD_TIME_SKILL));
    assertFalse(generationSkillIds.contains(SPEC_ONLY_SKILL));
  }

  @Test
  void excludedSkillsCannotEnterPromptContext() throws Exception {
    CompilerSkillContextBuilder contextBuilder = contextBuilder();
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("request", "brief", null, null, null);

    assertPromptExcluded(contextBuilder, PRIVATE_SKILL, snapshot);
    assertPromptExcluded(contextBuilder, BUILD_TIME_SKILL, snapshot);
    assertPromptExcluded(contextBuilder, SPEC_ONLY_SKILL, snapshot);
  }

  @Test
  void capabilityGateRejectsCatalogExcludedSkills() {
    CompilerSkillCapabilityGate gate = new CompilerSkillCapabilityGate(repository);

    assertFalse(gate.allowsGenericExecution(PRIVATE_SKILL));
    assertFalse(gate.allowsGenericExecution(BUILD_TIME_SKILL));
    assertFalse(gate.allowsGenericExecution(SPEC_ONLY_SKILL));
    assertTrue(gate.phaseFor(PRIVATE_SKILL).isEmpty());
    assertTrue(gate.rejectReason(PRIVATE_SKILL).contains("PRIVATE"));
    assertTrue(gate.rejectReason(BUILD_TIME_SKILL).contains("BUILD_TIME"));
    assertTrue(gate.rejectReason(SPEC_ONLY_SKILL).contains("SPECIFICATION_ONLY"));
  }

  private void assertPromptExcluded(
      CompilerSkillContextBuilder contextBuilder, String skillId, CompilerSkillInputSnapshot snapshot) {
    CompilerSkillDocument document =
        new CompilerSkillDocument(
            skillId,
            skillId,
            "skills/" + skillId + ".md",
            skillId,
            QipKnowledgeCapabilityPhase.GENERATOR,
            false,
            version,
            "# " + skillId);

    CompilerSkillRuntimeExcludedException error =
        assertThrows(
            CompilerSkillRuntimeExcludedException.class,
            () -> contextBuilder.buildUserMessage(document, snapshot));

    assertEquals(skillId, error.skillId());
    assertTrue(error.getMessage().contains("Excluded by compiler skill catalog"));
  }

  private CompilerSkillContextBuilder contextBuilder() throws Exception {
    return new CompilerSkillContextBuilder(
        new ObjectMapper(),
        repository,
        CompilerSkillAddonRepository.forFilesystem(
            outputDir, version, getClass().getClassLoader()),
        new CompilerSkillRuntimeEligibility(repository),
        testKnowledgeClient(), testKnowledgeClient());
  }

  private static FakeKnowledgeClient testKnowledgeClient() {
    return FakeKnowledgeClient.defaultFixture();
  }

  private void injectStalePipelineEntries(Path outputDir, QipKnowledgePackVersion packVersion)
      throws Exception {
    Path versionDir = outputDir.resolve(packVersion.normalized());
    Path pipelineFile = versionDir.resolve("compiler-pipeline-index.json");
    CompilerPipelineIndex index =
        objectMapper.readValue(pipelineFile.toFile(), CompilerPipelineIndex.class);

    List<CompilerPipelineEntry> entries = new ArrayList<>(index.entries());
    entries.add(pipelineEntry(PRIVATE_SKILL, "GEN-90", 90));
    entries.add(pipelineEntry(BUILD_TIME_SKILL, "GEN-91", 91));
    entries.add(pipelineEntry(SPEC_ONLY_SKILL, "GEN-92", 92));

    CompilerPipelineIndex patched =
        new CompilerPipelineIndex(
            index.schemaVersion(), index.packVersion(), index.sources(), List.copyOf(entries));
    objectMapper.writeValue(pipelineFile.toFile(), patched);
  }

  private static CompilerPipelineEntry pipelineEntry(String skillId, String generatorId, int order) {
    return new CompilerPipelineEntry(
        skillId,
        "generation",
        "generation",
        order,
        generatorId,
        true,
        "skills/" + skillId + ".md",
        "stale",
        "high",
        List.of(),
        List.of(),
        List.of());
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
    writeTopLevelSpec(root, PRIVATE_SKILL, "GEN-90");
    writeSkillFolder(
        root,
        PRIVATE_SKILL,
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
    writeTopLevelSpec(root, BUILD_TIME_SKILL, "GEN-91");
    writeSkillFolder(
        root,
        BUILD_TIME_SKILL,
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
    writeTopLevelSpec(root, SPEC_ONLY_SKILL, "GEN-92");
  }

  private static void writeAllowedGenerator(Path root) throws Exception {
    writeTopLevelSpec(root, ALLOWED_SKILL, "GEN-93");
    writeSkillFolder(
        root,
        ALLOWED_SKILL,
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
