package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

/** LLM-facing graph patch input for {@link CompilerGraphPatchTool#captureGraphPatch}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GraphPatchCapture(
    @Description("Unique patch id") String patchId,
    @Description("Compiler skill id that owns this patch, e.g. cip-routing-generator")
        String ownerCapabilityId,
    @Description("Node add/update/remove operations") List<NodePatch> nodePatches,
    @Description("Edge add/update/remove operations") List<EdgePatch> edgePatches,
    @Description("Property patches on existing node ids; use key plus structured value")
        List<PropertyPatchCapture> propertyPatches,
    @Description("Chain-level field patches; use key plus structured value")
        List<ChainPatchCapture> chainPatches,
    List<QipKnowledgeCitation> usedKnowledgeRefs,
    @Description("Why the patch was or was not generated") String rationale,
    @Description(
            "Set true when this skill has nothing to change (NOT_APPLICABLE). Requires empty"
                + " nodePatches, edgePatches, propertyPatches, and chainPatches. Prefer this over"
                + " inventing patch bodies. Omit or false for normal patches.")
        Boolean notApplicable) {

  /** Compatibility constructor for callers that omit {@code notApplicable}. */
  GraphPatchCapture(
      String patchId,
      String ownerCapabilityId,
      List<NodePatch> nodePatches,
      List<EdgePatch> edgePatches,
      List<PropertyPatchCapture> propertyPatches,
      List<ChainPatchCapture> chainPatches,
      List<QipKnowledgeCitation> usedKnowledgeRefs,
      String rationale) {
    this(
        patchId,
        ownerCapabilityId,
        nodePatches,
        edgePatches,
        propertyPatches,
        chainPatches,
        usedKnowledgeRefs,
        rationale,
        null);
  }

  boolean isNotApplicable() {
    return Boolean.TRUE.equals(notApplicable);
  }
}
