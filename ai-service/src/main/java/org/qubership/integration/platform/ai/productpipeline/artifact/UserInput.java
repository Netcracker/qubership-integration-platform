package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.time.Instant;

/** Immutable user input targeted at a stage waiting for input or approval. */
public record UserInput(String inputId, String targetStageId, String text, Instant receivedAt) {}
