package org.qubership.integration.platform.ai.productpipeline.profile;

/** Durable typed output for a stage that skips capability execution. */
public record BypassPolicy(ArtifactTypeRef produces) {}
