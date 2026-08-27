package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class ChainEditCompilerDagTest {

  private static final Set<String> EDIT_SEED_ARTIFACTS =
      Set.of(
          SkillArtifactType.RAW_USER_REQUEST.name(),
          SkillArtifactType.CHAIN_PLAN_GRAPH.name(),
          SkillArtifactType.CHAIN_STRUCTURE.name());

  @Test
  void cutKeepsRequestedGeneratorsAssemblerAndValidatorsOnly() {
    ResolvedCompilerDag cut =
        ChainEditCompilerDag.cut(
            fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS);

    assertEquals(
        List.of("cip-service-call-generator", "cip-chain-assembler", "cip-element-validator"),
        cut.nodes().stream().map(ResolvedCompilerNode::skillId).toList());
  }

  @Test
  void cutDropsCreateOnlyUpstreamWork() {
    ResolvedCompilerDag cut =
        ChainEditCompilerDag.cut(
            fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS);

    List<String> skillIds = cut.nodes().stream().map(ResolvedCompilerNode::skillId).toList();
    assertFalse(skillIds.contains("cip-naming-generator"));
    assertFalse(skillIds.contains("cip-structure-generator"));
  }

  @Test
  void cutNarrowsConsumesToWhatTheEditRunHolds() {
    ResolvedCompilerDag cut =
        ChainEditCompilerDag.cut(
            fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS);

    ResolvedCompilerNode generator = node(cut, "cip-service-call-generator");
    assertEquals(
        List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name(), SkillArtifactType.RAW_USER_REQUEST.name()),
        generator.consumes());
    assertEquals(List.of(), generator.dependsOn());

    ResolvedCompilerNode validator = node(cut, "cip-element-validator");
    assertFalse(validator.consumes().contains(SkillArtifactType.NAMING_MANIFEST.name()));
    assertTrue(validator.consumes().contains(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()));
    assertEquals(List.of("cip-chain-assembler"), validator.dependsOn());
  }

  @Test
  void cutRejectsAGeneratorTheCompilerPackageDoesNotHave() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ChainEditCompilerDag.cut(fullDag(), Set.of("cip-made-up"), EDIT_SEED_ARTIFACTS));
  }

  @Test
  void structurePrefixContainsNoAssemblerOrValidator() {
    ResolvedCompilerDag prefix =
        ChainEditCompilerDag.structureOnly(fullDag(), EDIT_SEED_ARTIFACTS);

    assertEquals(
        List.of("cip-structure-generator"),
        prefix.nodes().stream().map(ResolvedCompilerNode::skillId).toList());
    assertEquals(List.of(), node(prefix, "cip-structure-generator").consumes());
  }

  @Test
  void cutIsStableForTheSameSelection() {
    assertEquals(
        ChainEditCompilerDag.cut(fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS),
        ChainEditCompilerDag.cut(fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS));
  }

  @Test
  void pinnedManifestKeepsEveryRuntimePinAndSwapsTheDag() {
    RunManifest source = manifest();
    ResolvedCompilerDag cut =
        ChainEditCompilerDag.cut(
            fullDag(), Set.of("cip-service-call-generator"), EDIT_SEED_ARTIFACTS);

    RunManifest edit = ChainEditCompilerDag.pinnedManifest(source, "edit-run-1", cut);

    assertEquals("edit-run-1", edit.runId());
    assertEquals(source.runId(), edit.parentRunId());
    assertEquals(source.knowledgePackage(), edit.knowledgePackage());
    assertEquals(source.languageVersion(), edit.languageVersion());
    assertEquals(source.artifactSchemaVersions(), edit.artifactSchemaVersions());
    assertEquals(
        source.compilerRunPin().compilerPackageDigest(),
        edit.compilerRunPin().compilerPackageDigest());
    assertEquals(
        source.compilerRunPin().skillSha256ById(), edit.compilerRunPin().skillSha256ById());
    assertEquals(
        source.compilerRunPin().addonSha256ById(), edit.compilerRunPin().addonSha256ById());
    assertEquals(cut, edit.compilerRunPin().resolvedDag());
    assertEquals(
        List.of("cip-service-call-generator", "cip-chain-assembler", "cip-element-validator"),
        edit.compilerRunPin().capabilityClosure());
  }

  private static ResolvedCompilerNode node(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream().filter(n -> n.skillId().equals(skillId)).findFirst().orElseThrow();
  }

  private static ResolvedCompilerDag fullDag() {
    return new ResolvedCompilerDag(
        List.of(
            generation("cip-naming-generator", "Planning", List.of(), List.of("NAMING_MANIFEST")),
            generation(
                "cip-structure-generator",
                "Planning",
                List.of("NAMING_MANIFEST"),
                List.of("CHAIN_STRUCTURE", "CHAIN_PLAN_GRAPH")),
            generation(
                "cip-service-call-generator",
                "Generation",
                List.of("CHAIN_PLAN_GRAPH", "REQUIREMENT_BRIEF", "RAW_USER_REQUEST"),
                List.of("CHAIN_PLAN_GRAPH", "GRAPH_PATCH")),
            new ResolvedCompilerNode(
                "cip-chain-assembler",
                "Assembly",
                null,
                List.of("CHAIN_STRUCTURE", "GRAPH_PATCH_ARTIFACT"),
                List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH"),
                List.of("cip-service-call-generator", "cip-structure-generator"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                3,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            new ResolvedCompilerNode(
                "cip-element-validator",
                "Validation",
                null,
                List.of("GRAPH_ASSEMBLY_RESULT", "CHAIN_PLAN_GRAPH", "NAMING_MANIFEST"),
                List.of("PRE_BUILD_VALIDATION"),
                List.of("cip-chain-assembler"),
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                4,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "cip-element-validator")),
        List.of(),
        "full-digest");
  }

  private static ResolvedCompilerNode generation(
      String skillId, String phase, List<String> consumes, List<String> produces) {
    return new ResolvedCompilerNode(
        skillId,
        phase,
        null,
        consumes,
        produces,
        List.of(),
        "captureGraphPatch",
        List.of(),
        List.of(),
        true,
        List.of(),
        1,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }

  private static RunManifest manifest() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler-v2",
            "1.0.0",
            "package-digest",
            2,
            "v1",
            "index-digest",
            fullDag(),
            List.of("cip-service-call-generator"),
            Map.of("cip-service-call-generator", "skill-sha"),
            Map.of("cip-service-call-generator", "addon-sha"),
            List.of(new ArtifactTypeRef("chain-plan-graph", 1)),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        "create-run-1",
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "create-chain@2",
        "reference-baseline-v1",
        "reference-baseline-v1",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "artifact", "1.0.0", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "2026.1",
        List.of(new ArtifactTypeRef("chain-plan-graph", 1)),
        pin);
  }
}
