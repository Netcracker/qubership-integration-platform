package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Merges knowledge-pack product profile versions into a pipeline compatibility report.
 *
 * <p>The {@link CompilerPipelineCompatibilityAnalyzer} certifies {@code create-chain@1} and
 * {@code create-chain@2} against the compiler index. Additional profile versions shipped only in
 * the pack (for example {@code create-chain@3}) must be listed when compiler activation is allowed.
 */
public final class PipelineCompatibilityReportAugmenter {

  private PipelineCompatibilityReportAugmenter() {}

  public static PipelineCompatibilityReport augmentWithPackShippedProfiles(
      PipelineCompatibilityReport report, List<String> packShippedProfileKeys) {
    Objects.requireNonNull(report, "report");
    if (!report.activationAllowed()) {
      return report;
    }
    if (packShippedProfileKeys == null || packShippedProfileKeys.isEmpty()) {
      return report;
    }
    LinkedHashSet<String> merged = new LinkedHashSet<>(report.compatibleProfileVersions());
    boolean changed = false;
    for (String key : packShippedProfileKeys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      if (merged.add(key)) {
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
