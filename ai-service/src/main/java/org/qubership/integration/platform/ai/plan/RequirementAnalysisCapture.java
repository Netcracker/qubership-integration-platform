package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;

/** Model-authored interpretation of an approved requirement draft. */
public record RequirementAnalysisCapture(
    @Description("Concise goal for the approved requirements") String goal,
    @Description("Input descriptions for the approved inbound interactions") List<String> inputs,
    @Description("Assumptions needed to interpret the approved requirements") List<String> assumptions,
    @Description("Readable summary for review") String summary,
    @Description("Knowledge references used during analysis") List<QipKnowledgeCitation> citations,
    @Description("Field adaptations on approved flow transitions; omit for pass-through")
        List<Mapping> mappingIntents) {

  public RequirementAnalysisCapture {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    citations = citations == null ? List.of() : List.copyOf(citations);
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
  }

  public record Mapping(
      @Description("Approved source interaction id") String sourceRef,
      @Description("Approved target interaction id") String targetRef,
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
