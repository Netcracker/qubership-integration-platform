package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

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
    List<RequirementServiceCall> serviceCalls,
    String apiHubCandidateServiceCallId,
    Boolean idsRequested) {

  public RequirementDraft {
    decision = decision != null ? decision : decisionFromComplete(complete);
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    assembledText = assembledText != null ? assembledText.trim() : "";
    sourceSkillHash =
        sourceSkillHash != null && !sourceSkillHash.isBlank() ? sourceSkillHash.trim() : null;
    facts = facts == null ? List.of() : List.copyOf(facts);
    if (serviceCalls == null || serviceCalls.isEmpty()) {
      serviceCalls = serviceCallsFromFacts(facts);
    } else {
      serviceCalls = List.copyOf(serviceCalls);
    }
    apiHubCandidateServiceCallId =
        apiHubCandidateServiceCallId == null || apiHubCandidateServiceCallId.isBlank()
            ? null
            : apiHubCandidateServiceCallId.trim();
    complete = decision == DraftDecision.READY_FOR_PLAN && openQuestions.isEmpty();
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
      List<RequirementServiceCall> serviceCalls,
      String apiHubCandidateServiceCallId) {
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
        serviceCalls,
        apiHubCandidateServiceCallId,
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
      boolean importIntent,
      List<RequirementServiceCall> serviceCalls) {
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
        serviceCalls,
        null);
  }

  public boolean readyForPlan() {
    return decision == DraftDecision.READY_FOR_PLAN
        && openQuestions.isEmpty()
        && allServiceCallsResolved();
  }

  /** True when every active service call has a binding whose {@code serviceCallId} matches. */
  public boolean allServiceCallsResolved() {
    if (serviceCalls.isEmpty()) {
      return true;
    }
    for (RequirementServiceCall call : serviceCalls) {
      if (call.catalogBinding() == null
          || !call.serviceCallId().equals(call.catalogBinding().serviceCallId())) {
        return false;
      }
    }
    return true;
  }

  public boolean hasPendingImport() {
    if (apiHubCandidate == null) {
      return false;
    }
    if (apiHubCandidateServiceCallId != null) {
      return serviceCalls.stream()
          .filter(call -> apiHubCandidateServiceCallId.equals(call.serviceCallId()))
          .findFirst()
          .map(call -> call.catalogBinding() == null)
          .orElse(true);
    }
    if (!serviceCalls.isEmpty()) {
      return !allServiceCallsResolved();
    }
    return false;
  }

  /** True when the call this pending import belongs to already has a catalog binding. */
  public boolean selectedImportCallAlreadyBound() {
    if (apiHubCandidateServiceCallId != null) {
      return serviceCalls.stream()
          .anyMatch(
              call ->
                  apiHubCandidateServiceCallId.equals(call.serviceCallId())
                      && call.catalogBinding() != null);
    }
    return !serviceCalls.isEmpty() && allServiceCallsResolved();
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
        serviceCalls,
        apiHubCandidateServiceCallId,
        requested);
  }

  /**
   * Replaces the catalog binding for one service call. Other calls keep their bindings. Ready for
   * plan only when every remaining call is bound.
   */
  public RequirementDraft withBoundServiceCall(String serviceCallId, CatalogBindingHint hint) {
    String id = serviceCallId == null ? "" : serviceCallId.trim();
    List<RequirementServiceCall> next = new ArrayList<>();
    boolean replaced = false;
    for (RequirementServiceCall call : serviceCalls) {
      if (!replaced && call.serviceCallId().equals(id)) {
        next.add(
            new RequirementServiceCall(
                call.serviceCallId(),
                call.sourceFactId(),
                call.participant(),
                call.operation(),
                hint));
        replaced = true;
      } else {
        next.add(call);
      }
    }
    if (!replaced) {
      return this;
    }
    boolean resolved =
        next.stream().allMatch(call -> call.catalogBinding() != null) || next.isEmpty();
    return new RequirementDraft(
        resolved,
        assembledText,
        resolved ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT,
        resolved ? List.of() : openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        null,
        false,
        facts,
        false,
        next,
        null);
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
        serviceCalls,
        apiHubCandidateServiceCallId);
  }

  /**
   * Sets a pending API Hub candidate and records durable import intent. The confirmation reaches
   * the reader as a decision, so nothing is pinned as an open question.
   */
  public RequirementDraft withApiHubCandidate(ApiHubRequirementRefs candidate) {
    return withApiHubCandidate(candidate, apiHubCandidateServiceCallId);
  }

  public RequirementDraft withApiHubCandidate(
      ApiHubRequirementRefs candidate, String serviceCallId) {
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
        serviceCalls,
        serviceCallId);
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
        serviceCalls,
        apiHubCandidateServiceCallId);
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
        serviceCalls,
        apiHubCandidateServiceCallId);
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
        List.of(),
        apiHubCandidateServiceCallId);
  }

  private static DraftDecision decisionFromComplete(boolean complete) {
    return complete ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT;
  }

  private static List<RequirementServiceCall> serviceCallsFromFacts(List<RequirementFact> facts) {
    List<RequirementServiceCall> calls = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact == null || !fact.needsCatalogBinding()) {
        continue;
      }
      calls.add(
          new RequirementServiceCall(
              fact.serviceCallId(),
              fact.sourceFactId(),
              fact.participant(),
              fact.operation()));
    }
    return List.copyOf(calls);
  }

}
