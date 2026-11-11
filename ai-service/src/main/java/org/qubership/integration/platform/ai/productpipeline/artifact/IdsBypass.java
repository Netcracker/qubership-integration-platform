package org.qubership.integration.platform.ai.productpipeline.artifact;

/** Records that the profile intentionally skipped IDS generation. */
public record IdsBypass(String reasonCode, String profileId, String profileVersion) {}
