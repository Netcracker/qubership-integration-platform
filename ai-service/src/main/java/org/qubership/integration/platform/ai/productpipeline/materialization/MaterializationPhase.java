package org.qubership.integration.platform.ai.productpipeline.materialization;

/** Durable phase markers for incremental catalog materialization. */
public enum MaterializationPhase {
  CHAIN,
  ELEMENTS,
  PROPERTIES,
  CONNECTIONS,
  READ_BACK,
  RECONCILE,
  COMPLETE
}
