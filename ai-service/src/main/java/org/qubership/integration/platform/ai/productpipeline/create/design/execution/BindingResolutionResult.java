package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Per service-call binding outcome after implementation approval.
 *
 * <p>{@link NeedsInput} maps to product {@code WAITING_FOR_INPUT} via {@link
 * StageOutcomeClass#NEEDS_INPUT}.
 */
public sealed interface BindingResolutionResult {

  /** Product-facing alias used by catalog-first tests for ambiguous matches. */
  StageOutcomeClass WAITING_FOR_INPUT = StageOutcomeClass.NEEDS_INPUT;

  StageOutcomeClass outcomeClass();

  record Resolved(CatalogBindingResolution binding) implements BindingResolutionResult {
    public Resolved {
      binding = DesignArtifacts.requireNonNull(binding, "binding");
    }

    @Override
    public StageOutcomeClass outcomeClass() {
      return StageOutcomeClass.SUCCEEDED;
    }
  }

  record NeedsInput(String serviceCallStepId, List<String> candidateIds)
      implements BindingResolutionResult {
    public NeedsInput {
      serviceCallStepId = DesignArtifacts.requireText(serviceCallStepId, "serviceCallStepId");
      candidateIds = DesignArtifacts.copyList(candidateIds);
    }

    @Override
    public StageOutcomeClass outcomeClass() {
      return WAITING_FOR_INPUT;
    }
  }

  record Failed(String serviceCallStepId, String reason, StageOutcomeClass outcomeClass)
      implements BindingResolutionResult {
    public Failed {
      serviceCallStepId = DesignArtifacts.requireText(serviceCallStepId, "serviceCallStepId");
      reason = DesignArtifacts.requireText(reason, "reason");
      outcomeClass = Objects.requireNonNullElse(outcomeClass, StageOutcomeClass.DOMAIN_FAILURE);
    }

    public Failed(String serviceCallStepId, String reason) {
      this(serviceCallStepId, reason, StageOutcomeClass.DOMAIN_FAILURE);
    }
  }
}
