package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Runs the shared compiler DAG for an approved design-execution plan. Only {@link
 * DesignExecutionCapability} may call this after implementation approval.
 */
public interface ApprovedCompilerExecutionRunner {

  /** Runs the DAG without surfacing per-skill progress (tests / callers that ignore activity). */
  default CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings,
      RunManifest runManifest) {
    return execute(approvedPlan, flow, bindings, runManifest, (skillId, status) -> {});
  }

  /**
   * Runs the DAG and reports {@code skillId → status} for chat activity (same channel as
   * brainstorming / planning spine).
   */
  CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings,
      RunManifest runManifest,
      BiConsumer<String, String> skillProgress);

  /** Runs one stage attempt and preserves its identity in compiler artifacts. */
  default CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings,
      RunManifest runManifest,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    return execute(approvedPlan, flow, bindings, runManifest, skillProgress);
  }
}
