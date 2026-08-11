package org.qubership.integration.platform.ai.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;

class CompilerPipelineIndexBuilderTest {

  private final CompilerPipelineIndexBuilder builder = new CompilerPipelineIndexBuilder();

  @Test
  void buildsDependencyAwareSchemaV2() {
    CompilerPipelineIndex index = buildProductionIndex();

    assertEquals(2, index.schemaVersion());
    assertEdge(index, "cip-requirement-analyzer", "cip-naming-generator");
    assertEdge(index, "cip-naming-generator", "cip-trigger-generator");
    assertEdge(index, "cip-trigger-generator", "cip-structure-generator");
    assertEdge(index, "cip-structure-generator", "cip-routing-generator");
    assertTrue(node(index, "cip-structure-generator").topologicalLevel() > 0);
    assertEquals("GEN-03", node(index, "cip-structure-generator").generatorId());
    assertTrue(node(index, "cip-structure-generator").runtimeReady());
    assertEquals(
        "captureChainStructure", node(index, "cip-structure-generator").captureTool());
    assertTrue(node(index, "cip-structure-generator").runtimeReadinessFindings().isEmpty());
    assertFalse(node(index, "cip-routing-generator").applicabilitySignals().isEmpty());
    assertEquals(
        CompilerNodeExecutionMode.JAVA_ADAPTER,
        node(index, "cip-chain-assembler").executionMode());
    assertEquals("graph-assembly", node(index, "cip-chain-assembler").adapterId());
    assertEquals(
        CompilerNodeExecutionMode.JAVA_ADAPTER,
        node(index, "cip-structural-validator").executionMode());
    assertTrue(
        index
            .sourceDigests()
            .keySet()
            .containsAll(
                Set.of(
                    "runtime-dependency-model.yaml",
                    "skill-catalog.yaml",
                    "generator-packages.yaml",
                    "artifact-schemas.yaml")));
  }

  @Test
  void assemblerAndValidatorsUseTypedArtifactsNotCatalogProse() {
    CompilerPipelineIndex index = buildProductionIndex();

    assertFalse(
        node(index, "cip-chain-assembler").consumes().contains("All Generation Stage outputs"));
    assertTrue(
        node(index, "cip-chain-assembler")
            .consumes()
            .containsAll(List.of("CHAIN_STRUCTURE", "GRAPH_PATCH_ARTIFACT")));
    assertTrue(
        node(index, "cip-chain-assembler")
            .produces()
            .containsAll(List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH")));
    assertTrue(
        node(index, "cip-structural-validator").consumes().contains("GRAPH_ASSEMBLY_RESULT"));
    assertFalse(node(index, "cip-structural-validator").consumes().contains("Complete chain.yaml"));
  }

  @Test
  void prefersAddonTypedArtifactsOverCatalogHumanLabels() {
    CompilerPipelineIndex index = buildProductionIndex();

    assertEquals(List.of("REQUIREMENT_BRIEF"), node(index, "cip-requirement-analyzer").produces());
    assertEquals(
        List.of("RAW_USER_REQUEST", "REQUIREMENT_BRIEF"),
        node(index, "cip-pattern-selector").consumes());
    assertEquals(
        List.of("SELECTED_PATTERN", "ELEMENT_SKELETON"),
        node(index, "cip-pattern-selector").produces());
    assertFalse(node(index, "cip-requirement-analyzer").produces().contains("chain-requirements.yaml"));
    assertFalse(
        node(index, "cip-pattern-selector").consumes().contains("Chain Requirements Document"));
  }

  @Test
  void rejectsConflictingDependencyDeclarations() {
    CompilerPipelineSourceLoader.SourceSet sources = fixtureWithConflictingTriggerDependency();
    assertThrows(CompilerPipelineIndexParseException.class, () -> builder.build(sources));
  }

  @Test
  void rejectsDependencyCycle() {
    CompilerPipelineSourceLoader.SourceSet sources = fixtureWithCycle();
    assertThrows(CompilerPipelineIndexParseException.class, () -> builder.build(sources));
  }

  @Test
  void compiledOwnershipCannotWidenTheGeneratorEnvelope() {
    CompilerPipelineSourceLoader.SourceSet sources =
        withAddonOwnership(
            productionSources(),
            "cip-routing-generator",
            ownershipAllowingNodeType("service-call"));

    assertThrows(CompilerPipelineIndexParseException.class, () -> builder.build(sources));
  }

  private CompilerPipelineIndex buildProductionIndex() {
    try {
      QipKnowledgePackTestSupport.configureAddonPackRoot();
      var policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
      var packRoot = QipKnowledgePackFixturePaths.packRoot();
      QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
      var result = ingestionService.ingest(packRoot);
      QipKnowledgePackScanResult scanResult =
          new QipKnowledgePackScanResult(packRoot, result.manifest().version(), result.files());
      return builder.build(scanResult, policy, QipKnowledgePackFixturePaths.addonRoot());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build production pipeline index", e);
    }
  }

  private static CompilerPipelineSourceLoader.SourceSet fixtureWithConflictingTriggerDependency() {
    return new CompilerPipelineSourceLoader.SourceSet(
        """
        model:
          package: "compiler-v2"
          version: "1.0.0"
        skill-dependencies:
          - { skill: "cip-trigger-generator", depends-on: ["cip-pattern-selector"], consumed-by: [] }
          - { skill: "cip-pattern-selector", depends-on: [], consumed-by: ["cip-trigger-generator"] }
        """,
        """
        normalized-skills:
          - name: cip-pattern-selector
            category: Knowledge
            stage: Discovery
            dependencies: []
            generated-artifacts: []
            inputs: []
            outputs: []
          - name: cip-trigger-generator
            category: Generator
            stage: Planning
            dependencies: ["cip-naming-generator"]
            generated-artifacts: ["trigger.yaml"]
            inputs: []
            outputs: []
          - name: cip-naming-generator
            category: Language
            stage: Planning
            dependencies: []
            generated-artifacts: []
            inputs: []
            outputs: []
        """,
        """
        generator-packages:
          version: "1.0.0"
        baseline-generators: []
        v2-generators:
          generators: []
        """,
        """
        artifacts: []
        """,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(
            "runtime-dependency-model.yaml", "dep",
            "skill-catalog.yaml", "cat",
            "generator-packages.yaml", "gen",
            "artifact-schemas.yaml", "art"));
  }

  private static CompilerPipelineSourceLoader.SourceSet fixtureWithCycle() {
    return new CompilerPipelineSourceLoader.SourceSet(
        """
        model:
          package: "compiler-v2"
          version: "1.0.0"
        skill-dependencies:
          - { skill: "skill-a", depends-on: ["skill-b"], consumed-by: [] }
          - { skill: "skill-b", depends-on: ["skill-a"], consumed-by: [] }
        """,
        """
        normalized-skills:
          - name: skill-a
            category: Generator
            stage: Generation
            dependencies: ["skill-b"]
            generated-artifacts: []
            inputs: []
            outputs: []
          - name: skill-b
            category: Generator
            stage: Generation
            dependencies: ["skill-a"]
            generated-artifacts: []
            inputs: []
            outputs: []
        """,
        """
        generator-packages:
          version: "1.0.0"
        baseline-generators: []
        v2-generators:
          generators: []
        """,
        """
        artifacts: []
        """,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(
            "runtime-dependency-model.yaml", "dep",
            "skill-catalog.yaml", "cat",
            "generator-packages.yaml", "gen",
            "artifact-schemas.yaml", "art"));
  }

  private static CompilerPipelineSourceLoader.SourceSet productionSources() {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    return new CompilerPipelineSourceLoader()
        .load(QipKnowledgePackFixturePaths.packRoot(), QipKnowledgePackFixturePaths.addonRoot());
  }

  private static CompilerPipelineSourceLoader.SourceSet withAddonOwnership(
      CompilerPipelineSourceLoader.SourceSet base, String skillId, String ownershipYaml) {
    String addonContent = base.addonContentsById().get(skillId);
    if (addonContent == null) {
      throw new IllegalArgumentException("Missing addon content for " + skillId);
    }
    String updatedAddonContent =
        addonContent.replaceAll("(?s)  ownership:\\n.*?\\n  capture:", ownershipYaml + "\n  capture:");
    Map<String, String> addonContents = new java.util.LinkedHashMap<>(base.addonContentsById());
    addonContents.put(skillId, updatedAddonContent);
    Map<String, String> addonDigests = new java.util.LinkedHashMap<>(base.addonSha256ById());
    addonDigests.put(skillId, CompilerPipelineSourceLoader.sha256(updatedAddonContent));
    return new CompilerPipelineSourceLoader.SourceSet(
        base.runtimeDependencyModelYaml(),
        base.skillCatalogYaml(),
        base.generatorPackagesYaml(),
        base.artifactSchemasYaml(),
        base.skillContentsById(),
        addonContents,
        base.skillSha256ById(),
        addonDigests,
        base.sourceDigests());
  }

  private static String ownershipAllowingNodeType(String nodeType) {
    return """
  ownership:
    mayAddNodes: true
    mayAddEdges: false
    nodeTypes: [%s]
    chainFields: []
    properties: {}
"""
        .formatted(nodeType);
  }

  private static void assertEdge(CompilerPipelineIndex index, String producer, String consumer) {
    assertTrue(
        index.dependencies().stream()
            .anyMatch(
                edge ->
                    producer.equals(edge.producerSkillId())
                        && consumer.equals(edge.consumerSkillId())),
        () -> "Missing edge " + producer + " -> " + consumer);
  }

  private static CompilerPipelineNode node(CompilerPipelineIndex index, String skillId) {
    return index.nodes().stream()
        .filter(node -> skillId.equals(node.skillId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing node: " + skillId));
  }
}
