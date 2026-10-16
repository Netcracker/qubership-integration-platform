package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;

/** Typed graph patch produced by a compiler-backed generator pass. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphPatch(
    @Description("Unique patch id") String patchId,
    @Description("Compiler skill id that owns this patch, e.g. cip-routing-generator")
        String ownerCapabilityId,
    @Description("Node add/update/remove operations") List<NodePatch> nodePatches,
    @Description("Edge add/update/remove operations") List<EdgePatch> edgePatches,
    @Description("Property add/update/remove operations on existing node ids")
        List<PropertyPatch> propertyPatches,
    @Description("Chain-level plan field patches") List<ChainPatch> chainPatches,
    List<QipKnowledgeCitation> usedKnowledgeRefs,
    @Description("Why the patch was or was not generated") String rationale) {}
