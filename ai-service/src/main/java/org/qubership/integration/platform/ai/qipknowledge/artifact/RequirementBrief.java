package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;

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
    List<RequirementEntryPoint> entryPoints,
    List<RequirementServiceCall> serviceCalls,
    List<RequirementFact> requirements,
    List<MappingIntent> mappingIntents,
    RequirementFlow flow,
    List<CatalogBindingHint> catalogBindings) {

  public RequirementBrief {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    citations = citations == null ? List.of() : List.copyOf(citations);
    facts = facts == null ? List.of() : List.copyOf(facts);
    entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
    serviceCalls = serviceCalls == null ? List.of() : List.copyOf(serviceCalls);
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
    flow = flow == null ? RequirementFlow.EMPTY : flow;
    catalogBindings = catalogBindings == null ? List.of() : List.copyOf(catalogBindings);
    goal = goal == null ? "" : goal;
    summary = summary == null ? "" : summary;
    approvedDraftReference =
        approvedDraftReference == null || approvedDraftReference.isBlank()
            ? null
            : approvedDraftReference.trim();
    approvedDraftText = approvedDraftText == null ? "" : approvedDraftText;
  }

  /** Previous full constructor before flow and catalog bindings were pinned on the brief. */
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
      List<RequirementEntryPoint> entryPoints,
      List<RequirementServiceCall> serviceCalls,
      List<RequirementFact> requirements,
      List<MappingIntent> mappingIntents) {
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
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents,
        RequirementFlow.EMPTY,
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

  /** Facts plus mapping intents; roles are still projected from the approved flow. */
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
      List<MappingIntent> mappingIntents) {
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
        List.of(),
        List.of(),
        List.of(),
        mappingIntents);
  }

  /** Legacy constructor without draft reference or normalized facts. */
  public RequirementBrief(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      List<QipKnowledgeCitation> citations,
      String summary) {
    this(goal, inputs, constraints, assumptions, citations, summary, null, "", List.of());
  }

  public RequirementBrief withFacts(List<RequirementFact> facts) {
    return copy(
        facts, entryPoints, serviceCalls, requirements, mappingIntents, flow, catalogBindings);
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
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents,
        flow,
        catalogBindings);
  }

  public RequirementBrief withServiceCalls(List<RequirementServiceCall> serviceCalls) {
    return copy(
        facts, entryPoints, serviceCalls, requirements, mappingIntents, flow, catalogBindings);
  }

  public RequirementBrief withMappingIntents(List<MappingIntent> mappingIntents) {
    return copy(
        facts, entryPoints, serviceCalls, requirements, mappingIntents, flow, catalogBindings);
  }

  public RequirementBrief withFlow(RequirementFlow flow) {
    return copy(
        facts, entryPoints, serviceCalls, requirements, mappingIntents, flow, catalogBindings);
  }

  public RequirementBrief withCatalogBindings(List<CatalogBindingHint> catalogBindings) {
    return copy(
        facts, entryPoints, serviceCalls, requirements, mappingIntents, flow, catalogBindings);
  }

  private RequirementBrief copy(
      List<RequirementFact> facts,
      List<RequirementEntryPoint> entryPoints,
      List<RequirementServiceCall> serviceCalls,
      List<RequirementFact> requirements,
      List<MappingIntent> mappingIntents,
      RequirementFlow flow,
      List<CatalogBindingHint> catalogBindings) {
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
        entryPoints,
        serviceCalls,
        requirements,
        mappingIntents,
        flow,
        catalogBindings);
  }
}
