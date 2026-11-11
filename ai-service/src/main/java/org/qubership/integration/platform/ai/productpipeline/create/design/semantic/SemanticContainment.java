package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Structural parent-child relation. It does not describe exchange flow. */
public record SemanticContainment(String parentNodeId, String childNodeId, String role) {

  public SemanticContainment {
    parentNodeId = DesignArtifacts.requireText(parentNodeId, "parentNodeId");
    childNodeId = DesignArtifacts.requireText(childNodeId, "childNodeId");
    role = DesignArtifacts.requireText(role, "role");
  }
}
