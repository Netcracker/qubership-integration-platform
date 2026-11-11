package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Provenance from approved requirement facts. */
public record SemanticProvenance(List<String> sourceFactIds) {

  public SemanticProvenance {
    sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
  }
}
