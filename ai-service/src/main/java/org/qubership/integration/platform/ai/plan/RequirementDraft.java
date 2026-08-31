package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/** Iterative requirement vision accumulated before the compiler spine runs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementDraft(
    boolean complete,
    String assembledText,
    DraftDecision decision,
    List<String> openQuestions,
    String sourceSkillId,
    String sourceSkillVersion,
    String sourceSkillHash,
    ApiHubRequirementRefs apiHubCandidate,
    boolean awaitingPlanContinuation,
    List<RequirementFact> facts,
    boolean importIntent,
    @JsonAlias("apiHubCandidateServiceCallId") String apiHubCandidateInteractionId,
    Boolean idsRequested,
    RequirementFlow flow,
    List<CatalogBindingHint> catalogBindings) {

  public RequirementDraft {
    decision = decision != null ? decision : decisionFromComplete(complete);
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    assembledText = assembledText != null ? assembledText.trim() : "";
    sourceSkillHash =
        sourceSkillHash != null && !sourceSkillHash.isBlank() ? sourceSkillHash.trim() : null;
    facts = facts == null ? List.of() : List.copyOf(facts);
    apiHubCandidateInteractionId =
        apiHubCandidateInteractionId == null || apiHubCandidateInteractionId.isBlank()
            ? null
            : apiHubCandidateInteractionId.trim();
    flow = flow == null ? RequirementFlow.EMPTY : flow;
    catalogBindings = catalogBindings == null ? List.of() : List.copyOf(catalogBindings);
    complete = decision == DraftDecision.READY_FOR_PLAN && openQuestions.isEmpty();
  }

  /** Compatibility constructor for drafts captured before generic catalogBindings storage. */
  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent,
      String apiHubCandidateInteractionId,
      Boolean idsRequested,
      RequirementFlow flow) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        flow,
        List.of());
  }

  /** Compatibility constructor for drafts captured before business-first flow ownership. */
  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent,
      String apiHubCandidateInteractionId,
      Boolean idsRequested) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        RequirementFlow.EMPTY);
  }

  /** Compatibility constructor for drafts captured before the author could decline the IDS. */
  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent,
      String apiHubCandidateInteractionId) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        null);
  }

  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        null,
        null,
        false,
        List.of(),
        false,
        null,
        null);
  }

  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        null,
        false,
        List.of(),
        false,
        null,
        null);
  }

  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        List.of(),
        false,
        null,
        null);
  }

  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        false,
        null,
        null);
  }

  public RequirementDraft(boolean complete, String assembledText) {
    this(
        complete,
        assembledText,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        false,
        List.of(),
        false,
        null,
        null);
  }

  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        null,
        null);
  }

  /**
   * Compatibility constructor for tests and call sites that still pass projected service calls.
   * Bindings on those calls are copied onto {@link #catalogBindings()}.
   */
  public RequirementDraft(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      String sourceSkillId,
      String sourceSkillVersion,
      String sourceSkillHash,
      ApiHubRequirementRefs apiHubCandidate,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent,
      List<org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall>
          serviceCalls) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        null,
        null,
        RequirementFlow.EMPTY,
        hintsFromCalls(serviceCalls));
  }

  public boolean readyForPlan() {
    return decision == DraftDecision.READY_FOR_PLAN
        && openQuestions.isEmpty()
        && !flow.interactions().isEmpty()
        && RequirementFlowValidator.validateBindings(flow, facts, catalogBindings).isEmpty();
  }

  public boolean hasPendingImport() {
    if (apiHubCandidate == null) {
      return false;
    }
    if (apiHubCandidateInteractionId != null) {
      return catalogBindings.stream()
          .noneMatch(hint -> apiHubCandidateInteractionId.equals(hint.interactionId()));
    }
    return true;
  }

  /** True when the interaction this pending import belongs to already has a catalog binding. */
  public boolean selectedImportCallAlreadyBound() {
    if (apiHubCandidateInteractionId != null) {
      return catalogBindings.stream()
          .anyMatch(hint -> apiHubCandidateInteractionId.equals(hint.interactionId()));
    }
    return !catalogBindings.isEmpty();
  }

  public String planningText() {
    return assembledText;
  }

  /** Same draft with the author's IDS answer recorded. */
  public RequirementDraft withIdsRequested(Boolean requested) {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        requested,
        flow,
        catalogBindings);
  }

  /** Same draft with a captured business interaction graph. */
  public RequirementDraft withFlow(RequirementFlow nextFlow) {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        nextFlow,
        catalogBindings);
  }

  /**
   * Replaces or appends the catalog binding for one interaction. Other bindings stay in place.
   */
  public RequirementDraft withBoundInteraction(String interactionId, CatalogBindingHint hint) {
    String id = interactionId == null ? "" : interactionId.trim();
    if (id.isEmpty() || hint == null) {
      return this;
    }
    List<CatalogBindingHint> next = new ArrayList<>();
    boolean replaced = false;
    for (CatalogBindingHint existing : catalogBindings) {
      if (!replaced && existing.interactionId().equals(id)) {
        next.add(hint);
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced) {
      if (!flow.interactions().isEmpty() && flow.interaction(id).isEmpty()) {
        return this;
      }
      next.add(hint);
    }
    boolean bound =
        !flow.interactions().isEmpty()
            && RequirementFlowValidator.validateBindings(flow, facts, next).isEmpty();
    return new RequirementDraft(
        bound,
        assembledText,
        bound ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT,
        bound ? List.of() : openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        null,
        false,
        facts,
        false,
        null,
        idsRequested,
        flow,
        List.copyOf(next));
  }

  public RequirementDraft withBoundServiceCall(String interactionId, CatalogBindingHint hint) {
    return withBoundInteraction(interactionId, hint);
  }

  public RequirementDraft withAwaitingPlanContinuation(boolean awaiting) {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaiting,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        flow,
        catalogBindings);
  }

  /**
   * Sets a pending API Hub candidate and records durable import intent. The confirmation reaches
   * the reader as a decision, so nothing is pinned as an open question.
   */
  public RequirementDraft withApiHubCandidate(ApiHubRequirementRefs candidate) {
    return withApiHubCandidate(candidate, apiHubCandidateInteractionId);
  }

  public RequirementDraft withApiHubCandidate(
      ApiHubRequirementRefs candidate, String interactionId) {
    return new RequirementDraft(
        false,
        assembledText,
        DraftDecision.NEEDS_INPUT,
        List.of(),
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        candidate,
        false,
        facts,
        true,
        interactionId,
        idsRequested,
        flow,
        catalogBindings);
  }

  /** Clears the pending candidate while keeping {@link #importIntent()} for re-gather. */
  public RequirementDraft clearApiHubCandidate() {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        null,
        awaitingPlanContinuation,
        facts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        flow,
        catalogBindings);
  }

  public RequirementDraft withImportIntent(boolean intent) {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        facts,
        intent,
        apiHubCandidateInteractionId,
        idsRequested,
        flow,
        catalogBindings);
  }

  public RequirementDraft withFacts(List<RequirementFact> nextFacts) {
    return new RequirementDraft(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        awaitingPlanContinuation,
        nextFacts,
        importIntent,
        apiHubCandidateInteractionId,
        idsRequested,
        flow,
        catalogBindings);
  }

  public java.util.Optional<CatalogBindingHint> catalogBinding(String interactionId) {
    String id = interactionId == null ? "" : interactionId.trim();
    if (id.isEmpty()) {
      return java.util.Optional.empty();
    }
    return catalogBindings.stream().filter(hint -> id.equals(hint.interactionId())).findFirst();
  }

  private static DraftDecision decisionFromComplete(boolean complete) {
    return complete ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT;
  }

  private static List<CatalogBindingHint> hintsFromCalls(
      List<org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall>
          serviceCalls) {
    if (serviceCalls == null || serviceCalls.isEmpty()) {
      return List.of();
    }
    List<CatalogBindingHint> hints = new ArrayList<>();
    for (org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall call :
        serviceCalls) {
      if (call != null && call.catalogBinding() != null) {
        hints.add(call.catalogBinding());
      }
    }
    return List.copyOf(hints);
  }
}
