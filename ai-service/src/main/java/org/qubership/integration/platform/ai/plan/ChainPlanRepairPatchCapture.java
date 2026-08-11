package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;

/** LLM-facing edge-only patch for repairing an invalid chain plan draft. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChainPlanRepairPatchCapture(
    @Description("Unique repair patch id") String patchId,
    @Description("Edge add/update/remove operations allowed by the current repair diagnostics")
        List<EdgePatch> edgePatches,
    @Description("Why these edge changes fix the reported validation errors") String rationale) {}
