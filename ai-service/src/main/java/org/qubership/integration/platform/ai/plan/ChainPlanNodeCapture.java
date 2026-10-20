package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;

/**
 * LLM-facing plan node for {@link ChainPlanTool#captureChainPlan}.
 *
 * <p>Skeleton capture is structural only. Node properties are added later by generator skills via
 * {@code captureGraphPatch}; they are not accepted on this record (extra JSON fields are ignored).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChainPlanNodeCapture(
    @Description("Unique node id within the plan graph") String nodeId,
    @Description("Catalog element type, e.g. http-trigger, script, condition, if, else")
        String type,
    @Description("Human-readable element label") String label,
    @Description("Parent node id for container children; null at chain root. Triggers must be null.")
        String parentNodeId,
    @Description("Order within an ordered container, e.g. catch-2 priority") Integer order) {}
