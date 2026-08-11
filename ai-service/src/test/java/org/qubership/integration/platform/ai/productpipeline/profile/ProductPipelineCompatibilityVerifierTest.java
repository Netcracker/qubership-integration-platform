package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPackageIdentity;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSource;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineChangeClass;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class ProductPipelineCompatibilityVerifierTest {

  private final ProductPipelineCompatibilityVerifier verifier =
      new ProductPipelineCompatibilityVerifier();

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
        new QipKnowledgePackVersion("test", "test"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", digest),
        Map.of(),
        List.of(),
        List.of());
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
}
