package org.qubership.integration.platform.ai.productpipeline.profile;

/** Stage outcome that completes a product-pipeline profile. */
public record TerminalPolicy(String stageId, String state) {}
