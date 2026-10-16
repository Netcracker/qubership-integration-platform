package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;

/**
 * One ordered stage in a product-pipeline profile. Exactly one of {@code capabilityId} or {@code
 * bypass} must be set; {@code retry} is always required. Optional {@code skip} applies only with a
 * capability and evaluates against the committed requirement-draft before execution.
 */
public record ProfileStage(
    String stageId,
    String capabilityId,
    List<ArtifactTypeRef> consumes,
    List<ArtifactTypeRef> optionalConsumes,
    List<ArtifactTypeRef> produces,
    List<ArtifactTypeRef> optionalProduces,
    ApprovalPolicy approval,
    BypassPolicy bypass,
    RetryPolicy retry,
    SkipPolicy skip) {

  public ProfileStage {
    consumes = consumes == null ? List.of() : List.copyOf(consumes);
    optionalConsumes = optionalConsumes == null ? List.of() : List.copyOf(optionalConsumes);
    produces = produces == null ? List.of() : List.copyOf(produces);
    optionalProduces = optionalProduces == null ? List.of() : List.copyOf(optionalProduces);
  }

  /** Compatibility constructor for stages without optional artifact collections. */
  public ProfileStage(
      String stageId,
      String capabilityId,
      List<ArtifactTypeRef> consumes,
      List<ArtifactTypeRef> produces,
      ApprovalPolicy approval,
      BypassPolicy bypass,
      RetryPolicy retry,
      SkipPolicy skip) {
    this(
        stageId,
        capabilityId,
        consumes,
        List.of(),
        produces,
        List.of(),
        approval,
        bypass,
        retry,
        skip);
  }

  /** Compatibility constructor for stages without a skip policy. */
  public ProfileStage(
      String stageId,
      String capabilityId,
      List<ArtifactTypeRef> consumes,
      List<ArtifactTypeRef> produces,
      ApprovalPolicy approval,
      BypassPolicy bypass,
      RetryPolicy retry) {
    this(stageId, capabilityId, consumes, produces, approval, bypass, retry, null);
  }
}
