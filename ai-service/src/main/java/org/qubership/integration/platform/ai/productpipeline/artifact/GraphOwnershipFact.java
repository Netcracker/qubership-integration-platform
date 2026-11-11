package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Evidence that a capability owns a graph operation target. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphOwnershipFact(
    String capabilityId, String operationKind, String target, String ruleSource) {}
