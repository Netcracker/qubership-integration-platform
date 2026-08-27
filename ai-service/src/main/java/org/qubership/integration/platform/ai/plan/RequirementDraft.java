package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

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
    ResolvedCatalogBinding catalogBinding,
    boolean awaitingPlanContinuation,
    List<RequirementFact> facts,
    boolean importIntent,
    List<UploadedSpecCandidate> uploadedSpecCandidates,
    List<org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportResult>
        uploadedSpecImportResults) {

  public RequirementDraft {
    decision = decision != null ? decision : decisionFromComplete(complete);
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    assembledText = assembledText != null ? assembledText.trim() : "";
    sourceSkillHash =
        sourceSkillHash != null && !sourceSkillHash.isBlank() ? sourceSkillHash.trim() : null;
    facts = facts == null ? List.of() : List.copyOf(facts);
    uploadedSpecCandidates =
        uploadedSpecCandidates == null ? List.of() : List.copyOf(uploadedSpecCandidates);
    uploadedSpecImportResults =
        uploadedSpecImportResults == null ? List.of() : List.copyOf(uploadedSpecImportResults);
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
        List.of(),
        List.of());
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
        List.of(),
        List.of());
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
        List.of(),
        List.of());
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
        List.of(),
        List.of());
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
        List.of(),
        List.of());
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
        List.of(),
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
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
        uploadedSpecCandidates,
        uploadedSpecImportResults);
  }

  public RequirementDraft withUploadedSpecCandidates(List<UploadedSpecCandidate> candidates) {
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
        importIntent,
        candidates,
        uploadedSpecImportResults);
  }

  public RequirementDraft withUploadedSpecImportResults(
      List<org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportResult>
          results) {
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
        importIntent,
        uploadedSpecCandidates,
        results);
  }

  private static DraftDecision decisionFromComplete(boolean complete) {
    return complete ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT;
  }
}
