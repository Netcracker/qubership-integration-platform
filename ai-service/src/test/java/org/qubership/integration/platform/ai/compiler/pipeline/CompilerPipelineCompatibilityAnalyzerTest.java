package org.qubership.integration.platform.ai.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class CompilerPipelineCompatibilityAnalyzerTest {

  private final CompilerPipelineCompatibilityAnalyzer analyzer =
      new CompilerPipelineCompatibilityAnalyzer();

  private CompilerPipelineIndex previous;

  @BeforeEach
  void setUp() {
    previous = baselineIndex("digest-previous");
  }

  @Test
  void classifiesContentOnlyChangeWithoutProfileBump() {
    PipelineCompatibilityReport report = analyzer.compare(previous, withChangedSkillHash(previous));
    assertEquals(PipelineChangeClass.CONTENT_ONLY, report.changeClass());
    assertTrue(report.compatibleProfileVersions().contains("create-chain@1"));
    assertTrue(report.compatibleProfileVersions().contains("create-chain@2"));
    assertTrue(report.activationAllowed());
  }

  @Test
  void blocksRemovedMandatoryProducer() {
    PipelineCompatibilityReport report =
        analyzer.compare(previous, withoutStructureProducer(previous));
    assertEquals(PipelineChangeClass.BREAKING, report.changeClass());
    assertFalse(report.activationAllowed());
    assertTrue(report.blockingFindings().stream().anyMatch(v -> v.contains("CHAIN_STRUCTURE")));
  }

  private static CompilerPipelineIndex withChangedSkillHash(CompilerPipelineIndex base) {
    List<CompilerPipelineNode> nodes = new ArrayList<>();
    for (CompilerPipelineNode node : base.nodes()) {
      if ("cip-structure-generator".equals(node.skillId())) {
        nodes.add(
            new CompilerPipelineNode(
                node.skillId(),
                node.compilerPhase(),
                node.generatorId(),
                node.consumes(),
                node.produces(),
                node.dependsOn(),
                node.captureTool(),
                node.applicabilitySignals(),
                node.readinessSignals(),
                node.runtimeReady(),
                node.runtimeReadinessFindings(),
                "changed-skill-sha",
                node.addonSha256(),
                node.topologicalLevel(),
                node.stableTieBreaker(),
                node.mandatory(),
                node.executionMode(),
                node.adapterId(),
                node.ownership()));
      } else {
        nodes.add(node);
      }
    }
    return copyWithNodes(base, nodes, "digest-content-only");
  }

  private static CompilerPipelineIndex withoutStructureProducer(CompilerPipelineIndex base) {
    List<CompilerPipelineNode> nodes =
        base.nodes().stream()
            .filter(node -> !"cip-structure-generator".equals(node.skillId()))
            .toList();
    List<CompilerPipelineDependency> dependencies =
        base.dependencies().stream()
            .filter(
                edge ->
                    !"cip-structure-generator".equals(edge.producerSkillId())
                        && !"cip-structure-generator".equals(edge.consumerSkillId()))
            .toList();
    return new CompilerPipelineIndex(
        base.schemaVersion(),
        base.packVersion(),
        base.sources(),
        base.entries(),
        new CompilerPackageIdentity(
            base.packageIdentity().packageId(),
            base.packageIdentity().packageVersion(),
            "digest-without-structure"),
        base.sourceDigests(),
        nodes,
        dependencies);
  }

  private static CompilerPipelineIndex baselineIndex(String digest) {
    CompilerPipelineNode structure =
        new CompilerPipelineNode(
            "cip-structure-generator",
            "Planning",
            "GEN-03",
            List.of("ELEMENT_SKELETON"),
            List.of("CHAIN_STRUCTURE"),
            List.of("cip-pattern-selector"),
            null,
            List.of(),
            List.of(),
            false,
            List.of("MISSING_ADDON_RUNTIME_METADATA"),
            "skill-sha-structure",
            "addon-sha-structure",
            1,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null,
            GraphPatchOwnershipPolicy.denyAll());
    CompilerPipelineNode naming =
        new CompilerPipelineNode(
            "cip-naming-generator",
            "Planning",
            "GEN-02",
            List.of("REQUIREMENT_BRIEF"),
            List.of("NAMING_MANIFEST"),
            List.of(),
            null,
            List.of(),
            List.of(),
            true,
            List.of(),
            "skill-sha-naming",
            "addon-sha-naming",
            0,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null,
            GraphPatchOwnershipPolicy.denyAll());
    return new CompilerPipelineIndex(
        CompilerPipelineIndexBuilder.SCHEMA_VERSION,
        new QipKnowledgePackVersion("test", "test"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", digest),
        Map.of("skill-catalog.yaml", "abc"),
        List.of(naming, structure),
        List.of(
            new CompilerPipelineDependency(
                "cip-naming-generator", "cip-structure-generator", List.of("NAMING_MANIFEST"))));
  }

  private static CompilerPipelineIndex copyWithNodes(
      CompilerPipelineIndex base, List<CompilerPipelineNode> nodes, String digest) {
    return new CompilerPipelineIndex(
        base.schemaVersion(),
        base.packVersion(),
        base.sources(),
        base.entries(),
        new CompilerPackageIdentity(
            base.packageIdentity().packageId(), base.packageIdentity().packageVersion(), digest),
        base.sourceDigests(),
        nodes,
        base.dependencies());
  }
}
