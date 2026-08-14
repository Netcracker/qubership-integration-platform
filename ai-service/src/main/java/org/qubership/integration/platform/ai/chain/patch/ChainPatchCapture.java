package org.qubership.integration.platform.ai.chain.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * The change a model may submit against an existing chain.
 *
 * <p>Elements and connections may be added, reconfigured and removed; renaming what a chain already
 * has stays out of reach. The shape is the first bound on what a chat-driven edit can do to a chain
 * someone already runs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPatchCapture(
    @Description("Short id for this patch") String patchId,
    @Description(
            "Element changes: ADD with a new node id of your choosing, or REMOVE naming an existing"
                + " targetNodeId")
        List<NodePatch> nodePatches,
    @Description(
            "Connection changes: ADD with a new edge id of your choosing, or REMOVE naming an"
                + " existing targetEdgeId")
        List<EdgePatch> edgePatches,
    @Description("Property changes, each naming the node id it applies to")
        List<PropertyPatch> propertyPatches,
    @Description("What the change does, in one sentence for the reader") String rationale) {}
