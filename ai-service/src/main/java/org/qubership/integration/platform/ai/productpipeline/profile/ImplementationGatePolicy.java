package org.qubership.integration.platform.ai.productpipeline.profile;

/** Declares a post-approval wait that requires an explicit implement command. */
public record ImplementationGatePolicy(
    String afterStageId, ArtifactTypeRef targetArtifact, String waitingState) {}
