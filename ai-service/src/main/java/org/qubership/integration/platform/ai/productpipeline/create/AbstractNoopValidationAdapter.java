package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;

/** Base adapter that delegates deterministic pass execution to the spine. */
abstract class AbstractNoopValidationAdapter implements CompilerNodeExecutionAdapter {

  @Override
  public CompilerNodeExecutionResult execute(ResolvedCompilerNode node, PlanningSchedulerState state) {
    return new CompilerNodeExecutionResult(List.of(), List.of());
  }
}
