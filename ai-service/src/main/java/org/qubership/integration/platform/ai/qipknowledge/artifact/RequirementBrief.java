package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFact;

/** Distilled requirements for a chain planning workflow. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementBrief(
    String goal,
    List<String> inputs,
    List<String> constraints,
    List<String> assumptions,
    List<QipKnowledgeCitation> citations,
    String summary,
    String approvedDraftReference,
    String approvedDraftText,
    List<RequirementFact> facts,
    List<RequirementDataMapping> dataMappings) {

  public RequirementBrief {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    citations = citations == null ? List.of() : List.copyOf(citations);
    facts = facts == null ? List.of() : List.copyOf(facts);
    dataMappings = dataMappings == null ? List.of() : List.copyOf(dataMappings);
    goal = goal == null ? "" : goal;
    summary = summary == null ? "" : summary;
    approvedDraftReference =
        approvedDraftReference == null || approvedDraftReference.isBlank()
            ? null
            : approvedDraftReference.trim();
    approvedDraftText = approvedDraftText == null ? "" : approvedDraftText;
  }

  /** Previous full constructor without typed mapping intent. */
  public RequirementBrief(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      List<QipKnowledgeCitation> citations,
      String summary,
      String approvedDraftReference,
      String approvedDraftText,
      List<RequirementFact> facts) {
    this(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        List.of());
  }

  /** Legacy constructor without draft reference or normalized facts. */
  public RequirementBrief(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      List<QipKnowledgeCitation> citations,
      String summary) {
    this(goal, inputs, constraints, assumptions, citations, summary, null, "", List.of(), List.of());
  }
}
