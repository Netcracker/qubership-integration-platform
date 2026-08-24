package org.qubership.integration.platform.ai.productpipeline.create;

/**
 * One stage the diagnosis turn may name as owner, with the artifact type it produces.
 *
 * @param stageId profile stage id
 * @param artifactType produced type, or empty when the stage declares none
 */
public record OwnerCandidate(String stageId, String artifactType) {

  public OwnerCandidate {
    stageId = stageId == null ? "" : stageId;
    artifactType = artifactType == null ? "" : artifactType;
  }
}
