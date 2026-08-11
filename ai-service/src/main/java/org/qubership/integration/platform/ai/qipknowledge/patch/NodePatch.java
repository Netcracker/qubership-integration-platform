package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** Patch for adding, updating, or removing one plan node. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodePatch(
    @Description("ADD, UPDATE, or REMOVE") GraphPatchOperation operation,
    @Description("Node body for ADD or UPDATE; null for REMOVE") ChainPlanNode node,
    @Description("Target node id for UPDATE or REMOVE") String targetNodeId) {}
