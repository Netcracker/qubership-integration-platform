package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Directed control-flow edge. Containment and mapping are stored separately. */
public record SemanticExecutionEdge(
    String edgeId,
    String sourceNodeId,
    String targetNodeId,
    String regionId,
    String route,
    String mappingId) {

  public SemanticExecutionEdge {
    edgeId = DesignArtifacts.requireText(edgeId, "edgeId");
    sourceNodeId = DesignArtifacts.requireText(sourceNodeId, "sourceNodeId");
    targetNodeId = DesignArtifacts.requireText(targetNodeId, "targetNodeId");
    regionId = DesignArtifacts.nullableTrimmed(regionId);
    route = DesignArtifacts.nullableTrimmed(route);
    mappingId = DesignArtifacts.nullableTrimmed(mappingId);
  }
}
