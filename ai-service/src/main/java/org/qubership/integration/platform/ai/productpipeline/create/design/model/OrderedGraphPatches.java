package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Content-addressed ordered references to {@code GRAPH_PATCH_ARTIFACT} revisions. */
public record OrderedGraphPatches(String schemaVersion, List<Reference> patchRefs) {

  public OrderedGraphPatches {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    patchRefs = DesignArtifacts.copyList(patchRefs);
  }
}
