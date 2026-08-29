package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

/**
 * Runs the shared compiler DAG for an approved design-execution plan. Only {@link
 * DesignExecutionCapability} may call this after implementation approval.
 */
public interface ApprovedCompilerExecutionRunner {

  /** Runs the DAG without surfacing per-skill progress (tests / callers that ignore activity). */
  default CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest) {
    return execute(approvedPlan, revision, bindings, runManifest, (skillId, status) -> {});
  }

  /**
   * Runs the DAG and reports {@code skillId → status} for chat activity (same channel as
   * brainstorming / planning spine).
   */
  default CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      BiConsumer<String, String> skillProgress) {
    return execute(approvedPlan, revision, bindings, runManifest, null, skillProgress);
  }

  /** Runs one stage attempt and preserves its identity in compiler artifacts. */
  default CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    return execute(approvedPlan, revision, bindings, runManifest, attemptId, null, null, skillProgress);
  }

  /**
   * Runs one stage attempt informed by a halt: the previous outcome, findings, and failed stage,
   * plus the chain-plan graph that attempt produced. Both {@code repairEvidence} and {@code
   * priorGraph} are null on a first turn. {@code DesignExecutionBriefFactory} folds them into the
   * seed brief, so the retried generator skills correct the failing step instead of regenerating
   * the whole plan the way the first attempt did.
   */
  CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      BiConsumer<String, String> skillProgress);
}
