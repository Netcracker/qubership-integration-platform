package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

/**
 * Typed control-flow region. Sequence, condition, and split variants are added when branching
 * lands; this core keeps the list empty.
 */
public interface SemanticRegion {

  String regionId();
}
