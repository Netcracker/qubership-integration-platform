package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;

/** Outcome of resolving a plan from batch JSON and/or the active conversation snapshot. */
public sealed interface ChainPlanLoadResult
    permits ChainPlanLoadResult.Loaded, ChainPlanLoadResult.LegacyEmpty, ChainPlanLoadResult.Error {

  record Loaded(ChainImplementationPlan plan, String conversationId, String source)
      implements ChainPlanLoadResult {}

  record LegacyEmpty() implements ChainPlanLoadResult {}

  /** User-facing fatal message without orchestrator error prefix. */
  record Error(String message) implements ChainPlanLoadResult {}
}
