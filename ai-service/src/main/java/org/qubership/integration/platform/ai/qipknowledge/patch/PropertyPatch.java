package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Patch for adding, updating, or removing one node property. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PropertyPatch(
    GraphPatchOperation operation, String targetNodeId, PlanProperty property) {}
