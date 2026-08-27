package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

/** LLM-facing draft input for {@link RequirementDraftTool#captureRequirementDraft}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementDraftCapture(
    boolean complete,
    String assembledText,
    DraftDecision decision,
    List<String> openQuestions,
    ApiHubRequirementRefs apiHubCandidate,
    ResolvedCatalogBinding catalogBinding,
    List<RequirementFact> facts,
    List<UploadedSpecCandidate> uploadedSpecCandidates) {

  public RequirementDraftCapture {
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    facts = facts == null ? List.of() : List.copyOf(facts);
    uploadedSpecCandidates =
        uploadedSpecCandidates == null ? List.of() : List.copyOf(uploadedSpecCandidates);
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate,
      ResolvedCatalogBinding catalogBinding,
      List<RequirementFact> facts) {
    this(
        complete, assembledText, decision, openQuestions, apiHubCandidate, catalogBinding, facts, List.of());
  }

  public RequirementDraftCapture(boolean complete, String assembledText) {
    this(complete, assembledText, null, List.of(), null, null, List.of(), List.of());
  }

  public RequirementDraftCapture(
      boolean complete, String assembledText, DraftDecision decision, List<String> openQuestions) {
    this(complete, assembledText, decision, openQuestions, null, null, List.of(), List.of());
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, null, List.of(), List.of());
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate,
      ResolvedCatalogBinding catalogBinding) {
    this(
        complete, assembledText, decision, openQuestions, apiHubCandidate, catalogBinding, List.of(), List.of());
  }
}
