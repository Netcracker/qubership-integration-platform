package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
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
    List<RequirementFact> facts,
    @Description(
            "true when the author asked for an Integration Design Specification, false when they"
                + " said they do not want one; omit while they have not said either way")
        Boolean idsRequested) {

  public RequirementDraftCapture {
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    facts = facts == null ? List.of() : List.copyOf(facts);
  }

  /** Compatibility constructor for captures taken before the author could decline the IDS. */
  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate,
      List<RequirementFact> facts) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, facts, null);
  }

  public RequirementDraftCapture(boolean complete, String assembledText) {
    this(complete, assembledText, null, List.of(), null, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete, String assembledText, DraftDecision decision, List<String> openQuestions) {
    this(complete, assembledText, decision, openQuestions, null, List.of(), null);
  }

  public RequirementDraftCapture(
      boolean complete,
      String assembledText,
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs apiHubCandidate) {
    this(complete, assembledText, decision, openQuestions, apiHubCandidate, List.of(), null);
  }
}
