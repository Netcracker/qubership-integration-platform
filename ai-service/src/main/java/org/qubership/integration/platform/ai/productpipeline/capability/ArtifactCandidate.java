package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Candidate artifact produced by a capability before approval or commit. */
public record ArtifactCandidate(
    CompilationArtifacts.Kind kind,
    Object payload,
    List<CompilationArtifacts.Reference> inputs) {

  public ArtifactCandidate {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
  }
}
