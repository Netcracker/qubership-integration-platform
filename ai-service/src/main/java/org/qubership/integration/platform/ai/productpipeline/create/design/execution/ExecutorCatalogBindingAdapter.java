package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Post-approval catalog-first binding resolution for outbound service-call steps.
 *
 * <p>Must not resolve bindings before implementation approval. Local catalog matches are preferred;
 * APIHub search and {@code CatalogMutationGateway} import run only on a catalog miss.
 */
public interface ExecutorCatalogBindingAdapter {

  List<BindingResolutionResult> resolve(
      String conversationId,
      NormalizedDesignFlow flow,
      List<CatalogBindingHint> hints,
      ApprovalRecordV2 approval);
}
