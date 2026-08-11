package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;

/** Executes pinned JAVA_ADAPTER compiler nodes during planning. */
public interface CompilerNodeExecutionAdapter {

  String adapterId();

  CompilerNodeExecutionResult execute(ResolvedCompilerNode node, PlanningSchedulerState state);
}
