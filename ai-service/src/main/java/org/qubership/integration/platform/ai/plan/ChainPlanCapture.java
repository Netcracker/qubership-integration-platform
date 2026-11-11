package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

/** LLM-facing chain plan input for {@link ChainPlanTool#captureChainPlan}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChainPlanCapture(
    @Description("Plan schema version, use 1.0") String schemaVersion,
    @Description("Chain metadata") ChainSection chain,
    @Description("Flat list of plan nodes including container children")
        List<ChainPlanNodeCapture> nodes,
    @Description("Execution edges between nodes at root or scoped branches")
        List<ChainPlanEdge> edges) {}
