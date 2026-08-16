package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;

/** Patch for adding, updating, or removing one plan edge. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdgePatch(
    @Description("Required. One of ADD, UPDATE, REMOVE") GraphPatchOperation operation,
    @Description(
            "The whole connection, for ADD and UPDATE. Its id goes in edge.edgeId -- for ADD that"
                + " is the new id you invent. Leave empty for REMOVE")
        ChainPlanEdge edge,
    @Description(
            "Id of a connection the chain graph already lists, for REMOVE only. Leave empty for"
                + " ADD: a new connection carries its id in edge.edgeId instead")
        String targetEdgeId) {}
