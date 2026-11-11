package org.qubership.integration.platform.ai.compiler.plan;

/** Readiness state for one generator in the execution manifest. */
public enum GeneratorPlanStatus {
  READY,
  BLOCKED,
  SKIPPED
}
