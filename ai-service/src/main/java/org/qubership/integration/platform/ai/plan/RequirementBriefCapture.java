package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/** LLM-facing requirement brief input for {@link RequirementBriefTool#captureRequirementBrief}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementBriefCapture(
    String goal,
    List<String> inputs,
    List<String> constraints,
    List<String> assumptions,
    String summary,
    String approvedDraftReference,
    String approvedDraftText,
    List<RequirementFact> facts,
    List<QipKnowledgeCitation> citations,
    List<RequirementDataMapping> dataMappings) {

  public RequirementBriefCapture {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    facts = facts == null ? List.of() : List.copyOf(facts);
    citations = citations == null ? List.of() : List.copyOf(citations);
    dataMappings = dataMappings == null ? List.of() : List.copyOf(dataMappings);
  }

  /** Previous full capture shape without typed mapping intent. */
  public RequirementBriefCapture(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      String summary,
      String approvedDraftReference,
      String approvedDraftText,
      List<RequirementFact> facts,
      List<QipKnowledgeCitation> citations) {
    this(
        goal,
        inputs,
        constraints,
        assumptions,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        citations,
        List.of());
  }

  public RequirementBriefCapture(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      String summary) {
    this(goal, inputs, constraints, assumptions, summary, null, null, List.of(), List.of(), List.of());
  }
}
