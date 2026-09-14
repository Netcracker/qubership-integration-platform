package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;

/** Canonical machine-readable contract captured from {@code cip-design-planner}. */
public record DesignPlanContract(
    String schemaVersion,
    String contractId,
    String semanticRevisionId,
    String semanticRevisionHash,
    String apiRelease,
    List<Step> steps) {

  public enum OwnerKind {
    SKILL,
    APIHUB_TOOL
  }

  public enum TargetKind {
    ENTRY_POINT,
    SERVICE_CALL,
    MAPPING_INTENT,
    REGION,
    BEHAVIOR_NODE,
    CATALOG_BINDING
  }

  public enum ClaimRole {
    PRODUCER,
    REFERENCE
  }

  public DesignPlanContract {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    contractId = DesignArtifacts.requireText(contractId, "contractId");
    semanticRevisionId = DesignArtifacts.requireText(semanticRevisionId, "semanticRevisionId");
    semanticRevisionHash =
        DesignArtifacts.requireText(semanticRevisionHash, "semanticRevisionHash");
    apiRelease = DesignArtifacts.requireText(apiRelease, "apiRelease");
    steps = DesignArtifacts.copyList(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps must not be empty");
    }
  }

  public record Owner(OwnerKind kind, String id) {
    public Owner {
      kind = DesignArtifacts.requireNonNull(kind, "kind");
      id = DesignArtifacts.requireText(id, "id");
    }
  }

  public record Claim(TargetKind targetKind, String targetId, ClaimRole role) {
    public Claim {
      targetKind = DesignArtifacts.requireNonNull(targetKind, "targetKind");
      targetId = DesignArtifacts.requireText(targetId, "targetId");
      role = DesignArtifacts.requireNonNull(role, "role");
    }
  }

  public record Step(
      String stepId,
      String summary,
      Owner owner,
      List<Claim> claims,
      List<String> dependsOnStepIds) {
    public Step {
      stepId = DesignArtifacts.requireText(stepId, "stepId");
      summary = DesignArtifacts.requireText(summary, "summary");
      owner = DesignArtifacts.requireNonNull(owner, "owner");
      claims = DesignArtifacts.copyList(claims);
      dependsOnStepIds = DesignArtifacts.copyList(dependsOnStepIds);
    }
  }
}
