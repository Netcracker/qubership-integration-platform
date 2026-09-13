package org.qubership.integration.platform.ai.harness;

import java.util.List;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Request body for {@code POST /api/v1/harness/executor-run}. */
public record ExecutorHarnessRequest(
    String conversationId,
    String plannerResponse,
    ChainSemanticRevision semanticRevision,
    RequirementBrief requirementBrief,
    List<ResolvedServiceCallBinding> bindings) {

  public ExecutorHarnessRequest {
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
  }
}
