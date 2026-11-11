package org.qubership.integration.platform.ai.productpipeline.artifact;

/**
 * Immutable producer context attached to every product-pipeline artifact revision.
 *
 * @param runId durable run identifier
 * @param stageId profile stage that produced the revision
 * @param profileId pinned profile identity
 * @param profileVersion pinned profile version
 * @param profileDigest content digest of the pinned profile
 * @param capabilityId capability that produced the revision, when applicable
 * @param capabilityVersion pinned capability version
 * @param closureDigest digest of the pinned dependency closure
 */
public record ArtifactProvenance(
    String runId,
    String stageId,
    String profileId,
    String profileVersion,
    String profileDigest,
    String capabilityId,
    String capabilityVersion,
    String closureDigest) {}
