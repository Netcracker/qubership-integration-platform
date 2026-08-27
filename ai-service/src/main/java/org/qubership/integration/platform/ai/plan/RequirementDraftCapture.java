package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) ResolvedCatalogBinding catalogBinding,
    List<RequirementFact> facts,
    String designModeHint) {

  public RequirementDraftCapture {
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    facts = facts == null ? List.of() : List.copyOf(facts);
  }

  public RequirementDraftCapture(boolean complete, String assembledText) {
    this(complete, assembledText, null, List.of(), null, null, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete, String assembledText, DraftDecision decision, List<String> openQuestions) {
    this(complete, assembledText, decision, openQuestions, null, null, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, null, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate,
      ResolvedCatalogBinding catalogBinding) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, catalogBinding, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate,
      ResolvedCatalogBinding catalogBinding,
      List<RequirementFact> facts) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, catalogBinding, facts, null);
  }
}
