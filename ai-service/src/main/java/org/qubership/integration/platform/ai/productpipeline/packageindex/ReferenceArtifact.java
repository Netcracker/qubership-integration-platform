package org.qubership.integration.platform.ai.productpipeline.packageindex;

import java.util.Optional;

/** One frozen mapping from reference baseline to a selected target dependency. */
public record ReferenceArtifact(
    String dependencyId,
    String kind,
    Optional<String> referencePath,
    String targetPath,
    Optional<String> sourceSha256,
    String targetSha256,
    ReferenceDisposition disposition,
    Optional<String> adaptationReason) {

  public ReferenceArtifact {
    referencePath = referencePath == null ? Optional.empty() : referencePath;
    sourceSha256 = sourceSha256 == null ? Optional.empty() : sourceSha256;
    adaptationReason = adaptationReason == null ? Optional.empty() : adaptationReason;
  }
}
