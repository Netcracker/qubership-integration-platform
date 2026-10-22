package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Creates a derived run that copies parent pins and starts at requirement discovery. */
public record DeriveRunCommand(
    String parentRunId,
    String newRunId,
    String conversationId,
    ProductPipelineProfile profile,
    RunManifest runManifest,
    List<CompilationArtifacts.Reference> sourceReferences) {

  public DeriveRunCommand {
    sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
  }
}
