package org.qubership.integration.platform.ai.productpipeline.recovery;

/** Typed brief correction proposed during recovery. */
public record ProposedBriefChange(
    String sourceFactId,
    String field,
    String previousValue,
    String proposedValue,
    String findingCode,
    boolean authorDecisionRequired) {}
