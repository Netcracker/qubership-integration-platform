package org.qubership.integration.platform.ai.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

class PipelineCompatibilityReportAugmenterTest {

  @Test
  void mergesPackShippedProfileWhenActivationAllowedAndCompilerSupportsTerminals() throws Exception {
    ProductPipelineProfile createChainV3 = createChainV3FromBundledV2();
    CompilerPipelineIndex index = indexProducingCreateChainTerminals();
    PipelineCompatibilityReport base =
        new PipelineCompatibilityReport(
            1,
            "prev",
            "candidate",
            PipelineChangeClass.CONTENT_ONLY,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("create-chain@1", "create-chain@2"),
            List.of(),
            true,
            List.of());

    PipelineCompatibilityReport augmented =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            base,
            List.of("create-chain@2", "create-chain@3"),
            Map.of("create-chain@3", createChainV3),
            index);

    assertTrue(augmented.compatibleProfileVersions().contains("create-chain@3"));
    assertEquals(
        List.of("create-chain@1", "create-chain@2", "create-chain@3"),
        augmented.compatibleProfileVersions());
  }

  @Test
  void skipsPackShippedProfileWhenRequiredTerminalsAreNotProducible() {
    ProductPipelineProfile unsupported =
        new ProductPipelineProfile(
            1,
            "create-chain",
            "3",
            List.of(),
            List.of(),
            new TerminalPolicy("finish", "PLAN_APPROVED"),
            List.of(),
            new CompilerPipelinePolicy(
                List.of(2),
                List.of("Discovery"),
                List.of(),
                List.of(new ArtifactTypeRef("nonexistent-terminal-artifact", 1))));
    CompilerPipelineIndex index = indexProducingCreateChainTerminals();
    PipelineCompatibilityReport base =
        new PipelineCompatibilityReport(
            1,
            "prev",
            "candidate",
            PipelineChangeClass.CONTENT_ONLY,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("create-chain@1", "create-chain@2"),
            List.of(),
            true,
            List.of());

    PipelineCompatibilityReport augmented =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            base, List.of("create-chain@3"), Map.of("create-chain@3", unsupported), index);

    assertFalse(augmented.compatibleProfileVersions().contains("create-chain@3"));
    assertEquals(List.of("create-chain@1", "create-chain@2"), augmented.compatibleProfileVersions());
  }

  @Test
  void leavesReportUnchangedWhenActivationDenied() {
    PipelineCompatibilityReport base =
        new PipelineCompatibilityReport(
            1,
            "prev",
            "candidate",
            PipelineChangeClass.BREAKING,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("create-chain@1"),
            List.of(),
            false,
            List.of("blocked"));

    PipelineCompatibilityReport augmented =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            base,
            List.of("create-chain@3"),
            Map.of(),
            indexProducingCreateChainTerminals());

    assertEquals(base, augmented);
    assertFalse(augmented.compatibleProfileVersions().contains("create-chain@3"));
  }

  private static ProductPipelineProfile createChainV3FromBundledV2() throws Exception {
    try (InputStream input =
        PipelineCompatibilityReportAugmenterTest.class.getResourceAsStream(
            "/product-pipelines/profiles/create-chain-v2.yaml")) {
      ProductPipelineProfile v2 = ProductPipelineProfileParser.parse(input);
      return new ProductPipelineProfile(
          v2.schemaVersion(),
          v2.profileId(),
          "3",
          v2.runInputs(),
          v2.stages(),
          v2.terminal(),
          v2.dependencyRoots(),
          v2.compilerPipeline(),
          v2.implementationGate());
    }
  }

  private static CompilerPipelineIndex indexProducingCreateChainTerminals() {
    CompilerPipelineNode assembler =
        new CompilerPipelineNode(
            "cip-chain-assembler",
            "Assembly",
            "",
            List.of("CHAIN_STRUCTURE"),
            List.of("GRAPH_ASSEMBLY_RESULT"),
            List.of(),
            null,
            List.of(),
            List.of(),
            true,
            List.of(),
            "skill-sha-assembler",
            "addon-sha-assembler",
            0,
            0,
            true,
            CompilerNodeExecutionMode.JAVA_ADAPTER,
            "graph-assembly",
            null);
    CompilerPipelineNode validator =
        new CompilerPipelineNode(
            "cip-structural-validator",
            "Validation",
            "",
            List.of("GRAPH_ASSEMBLY_RESULT"),
            List.of("COMPILER_VALIDATION_BUNDLE"),
            List.of("cip-chain-assembler"),
            null,
            List.of(),
            List.of(),
            true,
            List.of(),
            "skill-sha-validator",
            "addon-sha-validator",
            1,
            0,
            true,
            CompilerNodeExecutionMode.JAVA_ADAPTER,
            "structural-validation",
            null);
    return new CompilerPipelineIndex(
        CompilerPipelineIndexBuilder.SCHEMA_VERSION,
        new CipKnowledgePackVersion("v1_0_0", "v1_0_0"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", "digest-ready-v2"),
        Map.of(),
        List.of(assembler, validator),
        List.of(
            new CompilerPipelineDependency(
                "cip-chain-assembler",
                "cip-structural-validator",
                List.of("GRAPH_ASSEMBLY_RESULT"))));
  }
}
