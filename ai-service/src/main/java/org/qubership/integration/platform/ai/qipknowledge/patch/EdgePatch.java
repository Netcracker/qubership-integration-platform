package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;

/** Patch for adding, updating, or removing one plan edge. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdgePatch(
    @Description("ADD, UPDATE, or REMOVE") GraphPatchOperation operation,
    @Description("The edge itself, for ADD and UPDATE") ChainPlanEdge edge,
    @Description("Edge id from the chain graph, for REMOVE") String targetEdgeId) {}
