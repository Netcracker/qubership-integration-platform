package org.qubership.integration.platform.ai.plan.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Compact edge view for plan presentation facts. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanPresentationEdge(
    String fromNodeId, String toNodeId, String fromLabel, String toLabel) {}
