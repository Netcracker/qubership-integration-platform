package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;
import java.util.Map;

/**
 * Typed projection of a {@link DesignPlanReport} enriched from the pinned compiler catalog.
 */
public record DesignExecutionPlan(
    String schemaVersion,
    String semanticRevisionId,
    String designAuthority,
    String designInputRef,
    String designInputHash,
    String apiRelease,
    String bindingResolutionPolicy,
    List<Step> steps,
    String sourceReportRef,
    String sourceReportHash,
    Map<String, String> pinnedSkillHashes,
    Map<String, String> pinnedAddonHashes,
    String compilerCatalogHash,
    String bindingResolutionPolicyHash) {

  public enum OwnerKind {
    SKILL,
    APIHUB_TOOL
  }

  public DesignExecutionPlan {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    semanticRevisionId = DesignArtifacts.requireText(semanticRevisionId, "semanticRevisionId");
    designAuthority = DesignArtifacts.requireText(designAuthority, "designAuthority");
    designInputRef = DesignArtifacts.requireText(designInputRef, "designInputRef");
    designInputHash = DesignArtifacts.requireText(designInputHash, "designInputHash");
    apiRelease = DesignArtifacts.requireText(apiRelease, "apiRelease");
    bindingResolutionPolicy =
        DesignArtifacts.requireText(bindingResolutionPolicy, "bindingResolutionPolicy");
    steps = DesignArtifacts.copyList(steps);
    sourceReportRef = DesignArtifacts.requireText(sourceReportRef, "sourceReportRef");
    sourceReportHash = DesignArtifacts.requireText(sourceReportHash, "sourceReportHash");
    pinnedSkillHashes = DesignArtifacts.copyStringMap(pinnedSkillHashes);
    pinnedAddonHashes = DesignArtifacts.copyStringMap(pinnedAddonHashes);
    compilerCatalogHash = DesignArtifacts.requireText(compilerCatalogHash, "compilerCatalogHash");
    bindingResolutionPolicyHash =
        DesignArtifacts.requireText(bindingResolutionPolicyHash, "bindingResolutionPolicyHash");
  }

  public record Step(
      String stepId,
      int reportOrdinal,
      String reportText,
      OwnerKind ownerKind,
      List<String> owningSkillIds,
      List<String> toolOperationRefs,
      List<String> participantRefs,
      List<String> operationQueryRefs,
      List<String> dependsOn,
      List<String> requiredArtifactTypes,
      List<String> producedArtifactTypes) {

    public Step {
      stepId = DesignArtifacts.requireText(stepId, "stepId");
      if (reportOrdinal < 1) {
        throw new IllegalArgumentException("reportOrdinal must be >= 1");
      }
      reportText = DesignArtifacts.requireText(reportText, "reportText");
      ownerKind = DesignArtifacts.requireNonNull(ownerKind, "ownerKind");
      owningSkillIds = DesignArtifacts.copyList(owningSkillIds);
      toolOperationRefs = DesignArtifacts.copyList(toolOperationRefs);
      participantRefs = DesignArtifacts.copyList(participantRefs);
      operationQueryRefs = DesignArtifacts.copyList(operationQueryRefs);
      dependsOn = DesignArtifacts.copyList(dependsOn);
      requiredArtifactTypes = DesignArtifacts.copyList(requiredArtifactTypes);
      producedArtifactTypes = DesignArtifacts.copyList(producedArtifactTypes);
    }
  }
}
