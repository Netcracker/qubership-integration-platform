package org.qubership.integration.platform.ai.compiler.pipeline;

/** Classification of a semantic diff between certified and candidate pipeline indexes. */
public enum PipelineChangeClass {
  BOOTSTRAP,
  CONTENT_ONLY,
  TOPOLOGY_OR_CONTRACT,
  REQUIRES_PROFILE_BUMP,
  BREAKING
}
