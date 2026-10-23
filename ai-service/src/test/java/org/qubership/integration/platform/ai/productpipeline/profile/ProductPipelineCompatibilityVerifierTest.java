package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPackageIdentity;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSource;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineChangeClass;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReportAugmenter;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

class ProductPipelineCompatibilityVerifierTest {

  private final ProductPipelineCompatibilityVerifier verifier =
      new ProductPipelineCompatibilityVerifier();

  @Test
  void acceptsPackShippedCreateChainV3WhenReportAugmented() throws Exception {
    ProductPipelineProfile profile = createChainV3FromBundledV2();
    CompilerPipelineIndex activeIndex = activeIndexWithCreateChainTerminals("digest-active");
    PipelineCompatibilityReport report =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            reportForActiveDigest(),
            List.of("create-chain@3"),
            Map.of("create-chain@3", profile),
            activeIndex);
    assertDoesNotThrow(() -> verifier.verify(profile, activeIndex, report));
  }

  @Test
  void startupRejectsDigestMismatch() {
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "create-chain",
            "1",
            List.of(),
            List.of(),
            new TerminalPolicy("finish", "PLAN_APPROVED"),
            List.of());
    CompilerPipelineIndex activeIndex = activeIndex("digest-active");
    assertThrows(
        IllegalStateException.class,
        () -> verifier.verify(profile, activeIndex, reportForDifferentDigest()));
  }

  private static CompilerPipelineIndex activeIndex(String digest) {
    return new CompilerPipelineIndex(
        CompilerPipelineIndexBuilder.SCHEMA_VERSION,
        new CipKnowledgePackVersion("test", "test"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", digest),
        Map.of(),
        List.of(),
        List.of());
  }

  private static CompilerPipelineIndex activeIndexWithCreateChainTerminals(String digest) {
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
        new CipKnowledgePackVersion("test", "test"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", digest),
        Map.of(),
        List.of(assembler, validator),
        List.of(
            new CompilerPipelineDependency(
                "cip-chain-assembler",
                "cip-structural-validator",
                List.of("GRAPH_ASSEMBLY_RESULT"))));
  }

  private static PipelineCompatibilityReport reportForDifferentDigest() {
    return new PipelineCompatibilityReport(
        1,
        "digest-previous",
        "digest-other",
        PipelineChangeClass.CONTENT_ONLY,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("create-chain@1"),
        List.of(),
        true,
        List.of());
  }

  private static ProductPipelineProfile createChainV3FromBundledV2() throws Exception {
    try (InputStream input =
        ProductPipelineCompatibilityVerifierTest.class.getResourceAsStream(
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

  private static PipelineCompatibilityReport reportForActiveDigest() {
    return new PipelineCompatibilityReport(
        1,
        "digest-previous",
        "digest-active",
        PipelineChangeClass.CONTENT_ONLY,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("create-chain@1", "create-chain@2"),
        List.of(),
        true,
        List.of());
  }
}
