package org.qubership.integration.platform.ai.compiler.pipeline;

/** Pinned execution mode for one compiler pipeline node. */
public enum CompilerNodeExecutionMode {
  PRE_SATISFIED,
  VIRTUAL_ORCHESTRATOR,
  LLM_SKILL,
  JAVA_ADAPTER
}
