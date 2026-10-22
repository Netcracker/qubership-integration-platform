package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Final assembled graph bound to the ordered patch ledger and ownership evidence. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphAssemblyResult(
    int schemaVersion,
    ChainPlanGraph graph,
    String graphDigest,
    List<CompilationArtifacts.Reference> orderedPatchReferences,
    List<GraphOwnershipFact> ownershipFacts,
    List<PatchRejection> rejectedPatches) {

  public GraphAssemblyResult {
    orderedPatchReferences =
        orderedPatchReferences == null ? List.of() : List.copyOf(orderedPatchReferences);
    ownershipFacts = ownershipFacts == null ? List.of() : List.copyOf(ownershipFacts);
    rejectedPatches = rejectedPatches == null ? List.of() : List.copyOf(rejectedPatches);
  }
}
