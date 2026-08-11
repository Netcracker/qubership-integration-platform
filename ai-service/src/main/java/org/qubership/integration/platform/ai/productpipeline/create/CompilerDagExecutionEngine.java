package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Uni;
import java.util.function.BiConsumer;

/**
 * Shared compiler DAG execution engine. Owns scheduler iteration, skill and Java-adapter dispatch,
 * patch collection/application, assembly, and compiler validation.
 */
public interface CompilerDagExecutionEngine {

  Uni<CompilerDagExecutionResult> execute(
      CompilerDagExecutionRequest request, BiConsumer<String, String> skillProgress);

  /** Executes one stage attempt while preserving its identity for idempotent artifacts. */
  default Uni<CompilerDagExecutionResult> execute(
      CompilerDagExecutionRequest request,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    return execute(request, skillProgress);
  }
}
