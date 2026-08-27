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
    List<RequirementDataMapping> dataMappings,
    List<RequirementEntryPoint> entryPoints,
    List<RequirementServiceCall> serviceCalls,
    List<RequirementFact> requirements,
    List<MappingIntent> mappingIntents) {

  public RequirementBrief {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    citations = citations == null ? List.of() : List.copyOf(citations);
    facts = facts == null ? List.of() : List.copyOf(facts);
    dataMappings = dataMappings == null ? List.of() : List.copyOf(dataMappings);
    entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
    serviceCalls = serviceCalls == null ? List.of() : List.copyOf(serviceCalls);
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
    goal = goal == null ? "" : goal;
    summary = summary == null ? "" : summary;
    approvedDraftReference =
        approvedDraftReference == null || approvedDraftReference.isBlank()
            ? null
            : approvedDraftReference.trim();
    approvedDraftText = approvedDraftText == null ? "" : approvedDraftText;
  }

  /** Compatibility constructor used while v2 roles are projected beside legacy facts. */
  public RequirementBrief(
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
        dataMappings,
        List.of(),
        List.of(),
        List.of(),
        List.of());
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

  public RequirementBrief withFacts(List<RequirementFact> facts) {
    return new RequirementBrief(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        dataMappings,
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents);
  }

  public RequirementBrief withApprovedDraftText(String approvedDraftText) {
    return new RequirementBrief(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        dataMappings,
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents);
  }

  public RequirementBrief withServiceCalls(List<RequirementServiceCall> serviceCalls) {
    return new RequirementBrief(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        dataMappings,
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents);
  }

  public RequirementBrief withDataMappings(List<RequirementDataMapping> dataMappings) {
    return new RequirementBrief(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        dataMappings,
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents);
  }

  public RequirementBrief withMappingIntents(List<MappingIntent> mappingIntents) {
    return new RequirementBrief(
        goal,
        inputs,
        constraints,
        assumptions,
        citations,
        summary,
        approvedDraftReference,
        approvedDraftText,
        facts,
        dataMappings,
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents);
  }
}
