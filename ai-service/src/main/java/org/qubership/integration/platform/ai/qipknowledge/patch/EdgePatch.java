package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;

/** Patch for adding, updating, or removing one plan edge. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdgePatch(
    GraphPatchOperation operation, ChainPlanEdge edge, String targetEdgeId) {}
