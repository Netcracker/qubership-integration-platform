package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/**
 * Immutable graph patch bound to base/result digests, inputs, citations, applicability, and one
 * invocation key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphPatchArtifact(
    int schemaVersion,
    String patchId,
    String ownerCapabilityId,
    String baseGraphDigest,
    String resultGraphDigest,
    GraphPatch patch,
    List<CompilationArtifacts.Reference> consumedArtifacts,
    List<String> sourceRequirementFactIds,
    List<QipKnowledgeCitation> knowledgeCitations,
    String rationale,
    PatchApplicability applicability,
    String invocationKey) {

  public GraphPatchArtifact {
    consumedArtifacts = consumedArtifacts == null ? List.of() : List.copyOf(consumedArtifacts);
    sourceRequirementFactIds =
        sourceRequirementFactIds == null ? List.of() : List.copyOf(sourceRequirementFactIds);
    knowledgeCitations =
        knowledgeCitations == null ? List.of() : List.copyOf(knowledgeCitations);
  }
}
