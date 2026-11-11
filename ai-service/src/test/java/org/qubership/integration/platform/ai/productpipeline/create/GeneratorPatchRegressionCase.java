package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/** Immutable regression fixture for one promoted-generator graph-patch seam. */
public record GeneratorPatchRegressionCase(
    String caseId,
    String skillId,
    RequirementBrief requirementBrief,
    Map<CompilationArtifacts.Kind, JsonNode> upstreamArtifacts,
    List<CompilationArtifacts.Reference> consumedArtifacts,
    ChainPlanGraph inputGraph,
    GraphPatch capturedPatch,
    PatchApplicability expectedApplicability,
    ChainPlanGraph expectedGraph) {

  public GeneratorPatchRegressionCase {
    upstreamArtifacts = upstreamArtifacts == null ? Map.of() : Map.copyOf(upstreamArtifacts);
    consumedArtifacts = consumedArtifacts == null ? List.of() : List.copyOf(consumedArtifacts);
  }

  @Override
  public String toString() {
    return caseId;
  }
}
