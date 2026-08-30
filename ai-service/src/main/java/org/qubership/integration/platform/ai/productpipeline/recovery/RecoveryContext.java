package org.qubership.integration.platform.ai.productpipeline.recovery;

import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Complete recovery input for the failure narrative agent. */
public record RecoveryContext(
    RecoveryEvidence evidence,
    RequirementBrief approvedBrief,
    Object rejectedArtifact,
    String responseLocale) {}
