package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mapping-contract observation attached to a {@link PlanValidationFinding}. Absent fields stay
 * empty so legacy JSON without this object remains readable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingValidationDetails(
    String mappingIntentId,
    String sourceRef,
    String sourcePort,
    String targetRef,
    String targetPort,
    String sourcePath,
    String targetPath,
    String expression,
    String ruleStatus,
    String consumedBriefArtifactId,
    String consumedBriefContentHash,
    String sourceSchemaOwner,
    String sourceSchemaDirection,
    String sourceSchemaDigest,
    String sourceSchemaProvenance,
    String targetSchemaOwner,
    String targetSchemaDirection,
    String targetSchemaDigest,
    String targetSchemaProvenance,
    String expectedContract,
    String observed) {

  public MappingValidationDetails {
    mappingIntentId = mappingIntentId == null ? "" : mappingIntentId;
    sourceRef = sourceRef == null ? "" : sourceRef;
    sourcePort = sourcePort == null ? "" : sourcePort;
    targetRef = targetRef == null ? "" : targetRef;
    targetPort = targetPort == null ? "" : targetPort;
    sourcePath = sourcePath == null ? "" : sourcePath;
    targetPath = targetPath == null ? "" : targetPath;
    expression = expression == null ? "" : expression;
    ruleStatus = ruleStatus == null ? "" : ruleStatus;
    consumedBriefArtifactId = consumedBriefArtifactId == null ? "" : consumedBriefArtifactId;
    consumedBriefContentHash = consumedBriefContentHash == null ? "" : consumedBriefContentHash;
    sourceSchemaOwner = sourceSchemaOwner == null ? "" : sourceSchemaOwner;
    sourceSchemaDirection = sourceSchemaDirection == null ? "" : sourceSchemaDirection;
    sourceSchemaDigest = sourceSchemaDigest == null ? "" : sourceSchemaDigest;
    sourceSchemaProvenance = sourceSchemaProvenance == null ? "" : sourceSchemaProvenance;
    targetSchemaOwner = targetSchemaOwner == null ? "" : targetSchemaOwner;
    targetSchemaDirection = targetSchemaDirection == null ? "" : targetSchemaDirection;
    targetSchemaDigest = targetSchemaDigest == null ? "" : targetSchemaDigest;
    targetSchemaProvenance = targetSchemaProvenance == null ? "" : targetSchemaProvenance;
    expectedContract = expectedContract == null ? "" : expectedContract;
    observed = observed == null ? "" : observed;
  }

  public static MappingValidationDetails empty() {
    return new MappingValidationDetails(
        "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
  }

  @JsonIgnore
  public boolean isPresent() {
    return !mappingIntentId.isBlank()
        || !targetPath.isBlank()
        || !sourcePath.isBlank()
        || !consumedBriefArtifactId.isBlank()
        || !consumedBriefContentHash.isBlank()
        || !targetSchemaDigest.isBlank()
        || !expectedContract.isBlank()
        || !observed.isBlank();
  }
}
