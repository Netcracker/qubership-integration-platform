package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/**
 * Merges knowledge-pack product profile versions into a pipeline compatibility report.
 *
 * <p>The {@link CompilerPipelineCompatibilityAnalyzer} certifies {@code create-chain@2} against the
 * compiler index. Pack-shipped profiles are validated against the
 * active compiler index even when they override an already-listed version (for example a pack
 * {@code create-chain@2} with a stricter {@code compilerPipeline} policy).
 */
public final class PipelineCompatibilityReportAugmenter {

  private PipelineCompatibilityReportAugmenter() {}

  public static PipelineCompatibilityReport augmentWithPackShippedProfiles(
      PipelineCompatibilityReport report,
      List<String> packShippedProfileKeys,
      Map<String, ProductPipelineProfile> profilesByKey,
      CompilerPipelineIndex activeIndex) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(profilesByKey, "profilesByKey");
    Objects.requireNonNull(activeIndex, "activeIndex");
    if (!report.activationAllowed()) {
      return report;
    }
    LinkedHashSet<String> merged = new LinkedHashSet<>(report.compatibleProfileVersions());
    boolean changed = merged.remove("create-chain@1");
    for (String key : packShippedProfileKeys == null ? List.<String>of() : packShippedProfileKeys) {
      if (key == null || key.isBlank() || "create-chain@1".equals(key)) {
        continue;
      }
      ProductPipelineProfile profile = profilesByKey.get(key);
      if (profile == null) {
        continue;
      }
      if (CompilerPipelineClosureSupport.requiredTerminalsProducible(profile, activeIndex)) {
        if (merged.add(key)) {
          changed = true;
        }
      } else if (merged.remove(key)) {
        changed = true;
      }
    }
    if (!changed) {
      return report;
    }
    return new PipelineCompatibilityReport(
        report.schemaVersion(),
        report.previousCompilerDigest(),
        report.candidateCompilerDigest(),
        report.changeClass(),
        report.changedNodes(),
        report.changedDependencies(),
        report.changedPhases(),
        report.changedArtifactContracts(),
        List.copyOf(merged),
        report.requiredGates(),
        report.activationAllowed(),
        report.blockingFindings());
  }
}
