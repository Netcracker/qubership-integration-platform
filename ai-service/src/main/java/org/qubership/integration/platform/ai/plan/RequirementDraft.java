package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
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
    @JsonInclude(JsonInclude.Include.NON_NULL) ResolvedCatalogBinding catalogBinding,
    boolean awaitingPlanContinuation,
    List<RequirementFact> facts,
    boolean importIntent,
    DesignMode designModeHint,
    List<RequirementServiceCall> serviceCalls) {

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
    if (serviceCalls.size() == 1 && catalogBinding != null) {
      RequirementServiceCall only = serviceCalls.getFirst();
      if (only.catalogBinding() == null) {
        RequirementServiceCall promoted = promoteLegacyBinding(only, catalogBinding);
        if (promoted.catalogBinding() != null) {
          serviceCalls = List.of(promoted);
          catalogBinding = null;
        }
      }
    } else if (serviceCalls.size() > 1) {
      catalogBinding = null;
    }
    complete = decision == DraftDecision.READY_FOR_PLAN && openQuestions.isEmpty();
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
        null,
        false,
        List.of(),
        false,
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
        null,
        false,
        List.of(),
        false,
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
      ResolvedCatalogBinding catalogBinding,
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
        catalogBinding,
        awaitingPlanContinuation,
        List.of(),
        false,
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
      ResolvedCatalogBinding catalogBinding,
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
        catalogBinding,
        awaitingPlanContinuation,
        facts,
        false,
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
        null,
        false,
        List.of(),
        false,
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
      ResolvedCatalogBinding catalogBinding,
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
        catalogBinding,
        awaitingPlanContinuation,
        facts,
        importIntent,
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
      ResolvedCatalogBinding catalogBinding,
      boolean awaitingPlanContinuation,
      List<RequirementFact> facts,
      boolean importIntent,
      DesignMode designModeHint) {
    this(
        complete,
        assembledText,
        decision,
        openQuestions,
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        apiHubCandidate,
        catalogBinding,
        awaitingPlanContinuation,
        facts,
        importIntent,
        designModeHint,
        List.of());
  }

  public boolean readyForPlan() {
    return decision == DraftDecision.READY_FOR_PLAN && openQuestions.isEmpty();
  }

  public boolean hasPendingImport() {
    return apiHubCandidate != null && catalogBinding == null;
  }

  public String planningText() {
    StringBuilder body = new StringBuilder(assembledText);
    if (catalogBinding != null) {
      body.append("\n\nResolved catalog binding:\n");
      body.append("- systemId: ").append(catalogBinding.systemId()).append('\n');
      body.append("- specificationId: ").append(catalogBinding.specificationId()).append('\n');
      body.append("- specificationGroupId: ").append(catalogBinding.specificationGroupId());
      if (catalogBinding.systemType() != null && !catalogBinding.systemType().isBlank()) {
        body.append('\n').append("- systemType: ").append(catalogBinding.systemType());
      }
      catalogBinding
          .optionalOperationId()
          .ifPresent(
              operationId ->
                  body.append('\n').append("- integrationOperationId: ").append(operationId));
    }
    return body.toString();
  }

  public RequirementDraft withCatalogBinding(ResolvedCatalogBinding binding) {
    return new RequirementDraft(
        complete,
        assembledText,
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        null,
        binding,
        false,
        facts,
        false,
        designModeHint,
        serviceCalls);
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
        catalogBinding,
        awaiting,
        facts,
        importIntent,
        designModeHint,
        serviceCalls);
  }

  /**
   * Sets a pending API Hub candidate and records durable import intent. The confirmation reaches
   * the reader as a decision, so nothing is pinned as an open question.
   */
  public RequirementDraft withApiHubCandidate(ApiHubRequirementRefs candidate) {
    return new RequirementDraft(
        false,
        assembledText,
        DraftDecision.NEEDS_INPUT,
        List.of(),
        sourceSkillId,
        sourceSkillVersion,
        sourceSkillHash,
        candidate,
        null,
        false,
        facts,
        true,
        designModeHint,
        serviceCalls);
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
        catalogBinding,
        awaitingPlanContinuation,
        facts,
        importIntent,
        designModeHint,
        serviceCalls);
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
        catalogBinding,
        awaitingPlanContinuation,
        facts,
        intent,
        designModeHint,
        serviceCalls);
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
        catalogBinding,
        awaitingPlanContinuation,
        nextFacts,
        importIntent,
        designModeHint,
        List.of());
  }

  private static DraftDecision decisionFromComplete(boolean complete) {
    return complete ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT;
  }

  private static List<RequirementServiceCall> serviceCallsFromFacts(List<RequirementFact> facts) {
    List<RequirementServiceCall> calls = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact == null
          || fact.polarity() != RequirementFactPolarity.POSITIVE
          || fact.kind() != RequirementFactKind.SERVICE_CALL) {
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

  private static RequirementServiceCall promoteLegacyBinding(
      RequirementServiceCall call, ResolvedCatalogBinding binding) {
    if (binding.optionalOperationId().isEmpty()
        || binding.systemId() == null
        || binding.systemId().isBlank()
        || binding.specificationId() == null
        || binding.specificationId().isBlank()
        || binding.specificationGroupId() == null
        || binding.specificationGroupId().isBlank()) {
      return call;
    }
    String operationQuery = call.operation().isBlank() ? "service-call" : call.operation();
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            call.serviceCallId().isBlank() ? call.sourceFactId() : call.serviceCallId(),
            call.sourceFactId(),
            operationQuery,
            binding.systemId(),
            binding.specificationGroupId(),
            binding.specificationId(),
            binding.integrationOperationId(),
            null,
            null,
            null,
            "catalog",
            Instant.EPOCH,
            "legacy-catalog-binding");
    return new RequirementServiceCall(
        call.serviceCallId(),
        call.sourceFactId(),
        call.participant(),
        call.operation(),
        hint);
  }
}
