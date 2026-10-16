package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;

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
    List<CapturedMappingIntent> mappingIntents) {

  public RequirementBriefCapture {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    facts = facts == null ? List.of() : List.copyOf(facts);
    citations = citations == null ? List.of() : List.copyOf(citations);
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
  }

  /** Capture without mapping intents. */
  @JsonIgnore
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

  @JsonIgnore
  public RequirementBriefCapture(
      String goal,
      List<String> inputs,
      List<String> constraints,
      List<String> assumptions,
      String summary) {
    this(goal, inputs, constraints, assumptions, summary, null, null, List.of(), List.of(), List.of());
  }

  public List<MappingIntent> toIntents() {
    if (mappingIntents.isEmpty()) {
      return List.of();
    }
    List<MappingIntent> intents = new ArrayList<>(mappingIntents.size());
    for (CapturedMappingIntent captured : mappingIntents) {
      if (captured != null) {
        intents.add(captured.toIntent());
      }
    }
    return List.copyOf(intents);
  }
}
