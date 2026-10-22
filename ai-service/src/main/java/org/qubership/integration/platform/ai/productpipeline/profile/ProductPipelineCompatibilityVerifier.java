package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;

/**
 * Startup gate that accepts only an active compiler package with an approved compatibility report
 * for the selected product profile.
 */
public final class ProductPipelineCompatibilityVerifier {

  public void verify(
      ProductPipelineProfile profile,
      CompilerPipelineIndex activeIndex,
      PipelineCompatibilityReport report) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(activeIndex, "activeIndex");
    Objects.requireNonNull(report, "report");

    String profileKey = profile.profileId() + "@" + profile.profileVersion();
    if (!report.compatibleProfileVersions().contains(profileKey)) {
      throw new IllegalStateException(
          "Selected product profile "
              + profileKey
              + " is absent from pipeline compatibility report compatibleProfileVersions");
    }

    String activeDigest =
        activeIndex.packageIdentity() == null
            ? null
            : activeIndex.packageIdentity().packageDigest();
    if (!Objects.equals(activeDigest, report.candidateCompilerDigest())) {
      throw new IllegalStateException(
          "Active compiler package digest does not match compatibility report candidate digest"
              + " (active="
              + activeDigest
              + ", candidate="
              + report.candidateCompilerDigest()
              + ")");
    }

    if (!report.activationAllowed()) {
      throw new IllegalStateException(
          "Pipeline compatibility report denies activation"
              + (report.blockingFindings().isEmpty()
                  ? ""
                  : ": " + String.join("; ", report.blockingFindings())));
    }
  }
}
