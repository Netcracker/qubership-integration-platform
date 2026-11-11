package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
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

  record Resolved(ResolvedServiceCallBinding binding) implements BindingResolutionResult {
    public Resolved {
      binding = DesignArtifacts.requireNonNull(binding, "binding");
    }

    @Override
    public StageOutcomeClass outcomeClass() {
      return StageOutcomeClass.SUCCEEDED;
    }
  }

  record NeedsInput(String serviceCallId, List<String> candidateIds)
      implements BindingResolutionResult {
    public NeedsInput {
      serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
      candidateIds = DesignArtifacts.copyList(candidateIds);
    }

    @Override
    public StageOutcomeClass outcomeClass() {
      return WAITING_FOR_INPUT;
    }
  }

  record Failed(
      String serviceCallId,
      String reason,
      StageOutcomeClass outcomeClass,
      String requestedFact)
      implements BindingResolutionResult {
    public Failed {
      serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
      reason = DesignArtifacts.requireText(reason, "reason");
      outcomeClass = Objects.requireNonNullElse(outcomeClass, StageOutcomeClass.DOMAIN_FAILURE);
      requestedFact =
          requestedFact == null || requestedFact.isBlank() ? "catalog service" : requestedFact;
    }

    public Failed(String serviceCallId, String reason, StageOutcomeClass outcomeClass) {
      this(serviceCallId, reason, outcomeClass, "catalog service");
    }

    public Failed(String serviceCallId, String reason) {
      this(serviceCallId, reason, StageOutcomeClass.DOMAIN_FAILURE, "catalog service");
    }

    /** Absence of a hint for {@code occurrenceId}, distinct from a stale or ambiguous match. */
    public static Failed missingHint(String occurrenceId) {
      return new Failed(
          occurrenceId,
          "no catalog binding hint for interactionId=" + occurrenceId,
          StageOutcomeClass.DOMAIN_FAILURE,
          RecoveryCause.MISSING_CATALOG_BINDING_FACT);
    }

    /**
     * A hint exists, but its interaction identity is not the semantic occurrence. Distinct from
     * absence and from a stale catalog operation.
     */
    public static Failed identityMismatch(String semanticInteractionId, String bindingInteractionId) {
      String semantic = DesignArtifacts.requireText(semanticInteractionId, "semanticInteractionId");
      String binding = DesignArtifacts.requireText(bindingInteractionId, "bindingInteractionId");
      return new Failed(
          semantic,
          "semantic interaction identity "
              + semantic
              + " disagrees with catalog binding interactionId="
              + binding,
          StageOutcomeClass.DOMAIN_FAILURE,
          RecoveryCause.BINDING_IDENTITY_MISMATCH_FACT);
    }

    public boolean isMissingHint() {
      return RecoveryCause.MISSING_CATALOG_BINDING_FACT.equals(requestedFact);
    }

    public boolean isIdentityMismatch() {
      return RecoveryCause.BINDING_IDENTITY_MISMATCH_FACT.equals(requestedFact);
    }
  }
}
