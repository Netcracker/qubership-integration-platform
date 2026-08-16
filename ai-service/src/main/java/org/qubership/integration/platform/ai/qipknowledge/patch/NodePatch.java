package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** Patch for adding, updating, or removing one plan node. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodePatch(
    @Description("Required. One of ADD, UPDATE, REMOVE") GraphPatchOperation operation,
    @Description(
            "The whole element, for ADD and UPDATE. Its id goes in node.nodeId -- for ADD that is"
                + " the new id you invent. Leave empty for REMOVE")
        ChainPlanNode node,
    @Description(
            "Id of an element the chain already has, for UPDATE and REMOVE only. Leave empty for"
                + " ADD: a new element carries its id in node.nodeId instead")
        String targetNodeId) {}
