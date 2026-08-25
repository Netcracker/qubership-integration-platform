package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates profile-neutral sequential contracts: stage order, artifact availability, schema
 * resolution, capability closure, explicit retry, and reachable terminal policy.
 */
public final class ProductPipelineProfileValidator {

  private static final ArtifactTypeRef RUN_MANIFEST_BOOTSTRAP = new ArtifactTypeRef("run-manifest", 1);

  private ProductPipelineProfileValidator() {}

  public static void validate(
      ProductPipelineProfile profile,
      ArtifactSchemaRegistry schemaRegistry,
      Set<String> knownCapabilities) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(schemaRegistry, "schemaRegistry");
    Objects.requireNonNull(knownCapabilities, "knownCapabilities");

    String profileId = profile.profileId();
    List<ProfileStage> stages = profile.stages() == null ? List.of() : profile.stages();
    Set<String> stageIds = new HashSet<>();
    Set<ArtifactTypeRef> available = new HashSet<>();
    // Runtime always prepends the committed run manifest before stage execution.
    available.add(RUN_MANIFEST_BOOTSTRAP);

    if (profile.runInputs() != null) {
      for (ArtifactTypeRef input : profile.runInputs()) {
        requireSupportedSchema(profileId, null, input, schemaRegistry);
        available.add(input);
      }
    }

    for (ProfileStage stage : stages) {
      String stageId = stage.stageId();
      if (stageId == null || stageId.isBlank()) {
        throw fail(profileId, stageId, "stageId is required");
      }
      if (!stageIds.add(stageId)) {
        throw fail(profileId, stageId, "duplicate stageId");
      }
      if (stage.retry() == null) {
        throw fail(profileId, stageId, "retry policy is required");
      }
      RetryPolicy retry = stage.retry();
      if (retry.maxTechnicalRetries() < 0
          || retry.defaultDelayMs() < 0
          || retry.backoffCoefficient() < 1
          || retry.maximumDelayMs() < retry.defaultDelayMs()) {
        throw fail(profileId, stageId, "retry policy has invalid delay limits");
      }

      boolean hasCapability = stage.capabilityId() != null && !stage.capabilityId().isBlank();
      boolean hasBypass = stage.bypass() != null;
      if (hasCapability == hasBypass) {
        throw fail(
            profileId,
            stageId,
            "stage must declare exactly one of capabilityId or bypass");
      }
      if (hasCapability && !knownCapabilities.contains(stage.capabilityId())) {
        throw fail(profileId, stageId, "unknown capabilityId " + stage.capabilityId());
      }
      if (stage.skip() != null) {
        if (!hasCapability) {
          throw fail(profileId, stageId, "skip requires capabilityId (cannot combine with bypass)");
        }
        try {
          SkipPolicy.requireKnownConditions(stage.skip());
        } catch (IllegalArgumentException ex) {
          throw fail(profileId, stageId, ex.getMessage());
        }
      }

      List<ArtifactTypeRef> consumes = stage.consumes() == null ? List.of() : stage.consumes();
      List<ArtifactTypeRef> optionalConsumes =
          stage.optionalConsumes() == null ? List.of() : stage.optionalConsumes();
      List<ArtifactTypeRef> produces = stage.produces() == null ? List.of() : stage.produces();
      List<ArtifactTypeRef> optionalProduces =
          stage.optionalProduces() == null ? List.of() : stage.optionalProduces();

      rejectOverlap(profileId, stageId, "consumes", consumes, optionalConsumes);
      rejectOverlap(profileId, stageId, "produces", produces, optionalProduces);

      for (ArtifactTypeRef consumed : consumes) {
        requireSupportedSchema(profileId, stageId, consumed, schemaRegistry);
        if (!available.contains(consumed)) {
          throw fail(
              profileId,
              stageId,
              "artifact "
                  + format(consumed)
                  + " has no earlier producer or run input");
        }
      }
      for (ArtifactTypeRef consumed : optionalConsumes) {
        requireSupportedSchema(profileId, stageId, consumed, schemaRegistry);
        if (!available.contains(consumed)) {
          throw fail(
              profileId,
              stageId,
              "optional artifact "
                  + format(consumed)
                  + " has no earlier producer or run input");
        }
      }

      for (ArtifactTypeRef produced : produces) {
        requireSupportedSchema(profileId, stageId, produced, schemaRegistry);
      }
      for (ArtifactTypeRef produced : optionalProduces) {
        requireSupportedSchema(profileId, stageId, produced, schemaRegistry);
      }
      if (stage.approval() != null) {
        requireSupportedSchema(profileId, stageId, stage.approval().artifact(), schemaRegistry);
        Set<ArtifactTypeRef> declaredForCandidates = new HashSet<>();
        declaredForCandidates.addAll(consumes);
        declaredForCandidates.addAll(optionalConsumes);
        declaredForCandidates.addAll(produces);
        declaredForCandidates.addAll(optionalProduces);
        for (ArtifactTypeRef candidate : stage.approval().candidateSet()) {
          requireSupportedSchema(profileId, stageId, candidate, schemaRegistry);
          if (!declaredForCandidates.contains(candidate)) {
            throw fail(
                profileId,
                stageId,
                "approval candidateSet type "
                    + format(candidate)
                    + " is not declared by consumes, optionalConsumes, produces, or optionalProduces");
          }
        }
        validateBindingResolutionPolicy(profileId, stageId, stage.approval());
        if (stage.approval().candidateSet().size() > 1) {
          available.add(new ArtifactTypeRef("approval-record", 2));
        }
      }
      if (hasBypass) {
        requireSupportedSchema(profileId, stageId, stage.bypass().produces(), schemaRegistry);
        available.add(stage.bypass().produces());
      }
      available.addAll(produces);
      available.addAll(optionalProduces);
    }

    TerminalPolicy terminal = profile.terminal();
    if (terminal == null || terminal.stageId() == null || terminal.stageId().isBlank()) {
      throw fail(profileId, null, "terminal stageId is required");
    }
    if (!stageIds.contains(terminal.stageId())) {
      throw fail(
          profileId,
          terminal.stageId(),
          "terminal stageId is unreachable through ordered stages");
    }
    if (terminal.state() == null || terminal.state().isBlank()) {
      throw fail(profileId, null, "terminal state is required");
    }

    ImplementationGatePolicy implementationGate = profile.implementationGate();
    if (implementationGate != null) {
      if (implementationGate.afterStageId() == null || implementationGate.afterStageId().isBlank()) {
        throw fail(profileId, null, "implementationGate.afterStageId is required");
      }
      if (!stageIds.contains(implementationGate.afterStageId())) {
        throw fail(
            profileId,
            implementationGate.afterStageId(),
            "implementationGate.afterStageId is unreachable through ordered stages");
      }
      if (!"WAITING_FOR_IMPLEMENT".equals(implementationGate.waitingState())) {
        throw fail(
            profileId,
            null,
            "implementationGate.waitingState must be WAITING_FOR_IMPLEMENT");
      }
      requireSupportedSchema(
          profileId, implementationGate.afterStageId(), implementationGate.targetArtifact(), schemaRegistry);
    }

    CompilerPipelinePolicy compilerPipeline = profile.compilerPipeline();
    if (compilerPipeline != null) {
      if (!compilerPipeline.supportedIndexSchemas().contains(2)) {
        throw fail(
            profileId,
            null,
            "compilerPipeline.supportedIndexSchemas must include schema 2");
      }
      if (compilerPipeline.allowedPhases().isEmpty()) {
        throw fail(profileId, null, "compilerPipeline.allowedPhases must not be empty");
      }
      if (compilerPipeline.requiredTerminalArtifacts().isEmpty()) {
        throw fail(
            profileId, null, "compilerPipeline.requiredTerminalArtifacts must not be empty");
      }
      for (ArtifactTypeRef terminalArtifact : compilerPipeline.requiredTerminalArtifacts()) {
        requireSupportedSchema(profileId, null, terminalArtifact, schemaRegistry);
      }
      for (ArtifactTypeRef preSatisfied : compilerPipeline.preSatisfiedArtifacts()) {
        requireSupportedSchema(profileId, null, preSatisfied, schemaRegistry);
      }
    }
  }

  private static void validateBindingResolutionPolicy(
      String profileId, String stageId, ApprovalPolicy approval) {
    String policy = approval.bindingResolutionPolicy();
    String policyHash = approval.bindingResolutionPolicyHash();
    boolean hasPolicy = policy != null && !policy.isBlank();
    boolean hasHash = policyHash != null && !policyHash.isBlank();
    if (hasPolicy != hasHash) {
      throw fail(
          profileId,
          stageId,
          "bindingResolutionPolicy and bindingResolutionPolicyHash must both be present or both absent");
    }
    if (hasPolicy && ApprovalPolicy.CATALOG_FIRST_V1.equals(policy)) {
      if (!ApprovalPolicy.CATALOG_FIRST_V1_HASH.equals(policyHash)) {
        throw fail(
            profileId,
            stageId,
            "CATALOG_FIRST_V1 requires bindingResolutionPolicyHash "
                + ApprovalPolicy.CATALOG_FIRST_V1_HASH);
      }
    }
  }

  private static void rejectOverlap(
      String profileId,
      String stageId,
      String collectionName,
      List<ArtifactTypeRef> required,
      List<ArtifactTypeRef> optional) {
    Set<ArtifactTypeRef> requiredSet = new HashSet<>(required);
    for (ArtifactTypeRef optionalRef : optional) {
      if (requiredSet.contains(optionalRef)) {
        throw fail(
            profileId,
            stageId,
            "overlap between required and optional "
                + collectionName
                + " for "
                + format(optionalRef));
      }
    }
  }

  private static void requireSupportedSchema(
      String profileId,
      String stageId,
      ArtifactTypeRef ref,
      ArtifactSchemaRegistry schemaRegistry) {
    if (ref == null || ref.type() == null || ref.type().isBlank()) {
      throw fail(profileId, stageId, "artifact type is required");
    }
    if (!schemaRegistry.supports(ref)) {
      throw fail(profileId, stageId, "unknown artifact schema " + format(ref));
    }
  }

  private static ProductPipelineProfileValidationException fail(
      String profileId, String stageId, String detail) {
    StringBuilder message = new StringBuilder("Invalid product-pipeline profile ");
    message.append(profileId == null ? "<unknown>" : profileId);
    if (stageId != null && !stageId.isBlank()) {
      message.append(" stage ").append(stageId);
    }
    message.append(": ").append(detail);
    return new ProductPipelineProfileValidationException(message.toString());
  }

  private static String format(ArtifactTypeRef ref) {
    return ref.type() + "@" + ref.schemaVersion();
  }
}
