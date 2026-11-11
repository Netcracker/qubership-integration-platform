package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;

/** Requires explicit approval of one produced artifact type. */
public record ApprovalPolicy(
    ArtifactTypeRef artifact,
    List<ArtifactTypeRef> candidateSet,
    String bindingResolutionPolicy,
    String bindingResolutionPolicyHash) {

  public static final String CATALOG_FIRST_V1 = "CATALOG_FIRST_V1";

  public static final String CATALOG_FIRST_V1_HASH =
      "ce160e1a62abc9d33b117338b10134e6cf8eeb5065ba6e5392a42b7f9cd17421";

  public ApprovalPolicy {
    candidateSet =
        candidateSet == null || candidateSet.isEmpty()
            ? List.of(artifact)
            : List.copyOf(candidateSet);
  }

  public ApprovalPolicy(ArtifactTypeRef artifact) {
    this(artifact, null, null, null);
  }

  public ApprovalPolicy(ArtifactTypeRef artifact, List<ArtifactTypeRef> candidateSet) {
    this(artifact, candidateSet, null, null);
  }
}
