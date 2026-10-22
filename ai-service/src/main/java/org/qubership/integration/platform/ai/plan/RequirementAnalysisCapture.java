package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.CipKnowledgeCitation;

/** Model-authored interpretation of a selected requirement draft. */
public record RequirementAnalysisCapture(
    @Description("Concise goal for the selected requirements") String goal,
    @Description("Input descriptions for the selected inbound interactions") List<String> inputs,
    @Description("Assumptions needed to interpret the selected requirements") List<String> assumptions,
    @Description("Readable summary for review") String summary,
    @Description("Knowledge references used during analysis") List<CipKnowledgeCitation> citations,
    @Description("Field adaptations on selected flow transitions; omit for pass-through")
        List<Mapping> mappingIntents,
    @Description("Requirements omitted from the selected draft, supported by exact saved author quotes; "
        + "use an empty list after reviewing all source requirements. A report does not authorize changes.")
        List<SourceOmission> sourceOmissions) {

  public RequirementAnalysisCapture(String goal, List<String> inputs, List<String> assumptions,
      String summary, List<CipKnowledgeCitation> citations, List<Mapping> mappingIntents) {
    this(goal, inputs, assumptions, summary, citations, mappingIntents, List.of());
  }

  public RequirementAnalysisCapture {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    citations = citations == null ? List.of() : List.copyOf(citations);
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
    sourceOmissions = sourceOmissions == null ? List.of() : List.copyOf(sourceOmissions);
  }

  public record SourceOmission(
      @Description("The sourceTurnId in the saved author evidence") String sourceTurnId,
      @Description("Exact source text that states the omitted requirement") String quote,
      @Description("What the selected draft omits, accounting for later amendments and choices")
          String explanation) {}

  public record Mapping(
      @Description("Selected source interaction id") String sourceRef,
      @Description("Selected target interaction id") String targetRef,
      @Description("Field rules for this transition") List<Rule> rules,
      @Description("Optional implementation preference") String implementationPreference) {

    public Mapping {
      rules = rules == null ? List.of() : List.copyOf(rules);
    }

    MappingIntent toIntent() {
      return new MappingIntent(
          "", sourceRef, null, targetRef, null,
          rules.stream().map(Rule::toIntentRule).toList(), implementationPreference);
    }
  }

  public record Rule(
      @Description("Source field path, when reading an input field") String sourcePath,
      @Description("Target field path") String targetPath,
      @Description("Expression for computed, constant, or default values") String expression) {

    MappingIntentRule toIntentRule() {
      return new MappingIntentRule(sourcePath, targetPath, expression);
    }
  }

  List<MappingIntent> toIntents() {
    return mappingIntents.stream().map(Mapping::toIntent).toList();
  }
}
