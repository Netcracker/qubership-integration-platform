package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Starts a new run or resumes an existing durable run for one conversation. */
public record StartOrResumeCommand(
    String conversationId,
    String runId,
    ProductPipelineProfile profile,
    RunManifest runManifest) {}
