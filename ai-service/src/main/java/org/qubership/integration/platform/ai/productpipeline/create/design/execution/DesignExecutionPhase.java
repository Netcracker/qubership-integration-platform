package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

/** Ordered executor phases for an approved design execution. */
public enum DesignExecutionPhase {
  PRECONDITIONS,
  BINDINGS_RESOLVED,
  GENERATORS_COMPLETE,
  ASSEMBLY_COMPLETE,
  VALIDATION_COMPLETE,
  WAITING_FOR_MATERIALIZATION,
  COMPLETE
}
