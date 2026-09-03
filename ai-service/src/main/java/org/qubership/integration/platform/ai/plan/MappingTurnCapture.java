package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

/**
 * Structured mapping-turn interpretation. The model does not return a replacement requirement
 * brief.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingTurnCapture(
    @Description(
            "CHANGES when the author requests field adaptation. QUERY when they inspect stored "
                + "mapping. NONE when they do not. CLARIFICATION when a transition cannot be "
                + "resolved uniquely.")
        Kind outcome,
    @Description(
            "New mapping intents to add, one per approved flow transition. Empty for NONE and "
                + "QUERY. Do not invent mappingIntentId.")
        List<IntentChange> addIntents,
    @Description("Rules to add to an existing mapping intent id. Empty when adding a new intent.")
        List<RuleChange> addRules,
    @Description(
            "Short reason code when outcome is CLARIFICATION, such as AMBIGUOUS_TRANSITION. Empty "
                + "otherwise.")
        String clarificationReason,
    @Description("Approved interaction ids that could match. Empty when not a clarification.")
        List<String> candidates,
    @Description("Read-only selector when outcome is QUERY. Empty otherwise.")
        QuerySelector query,
    @Description(
            "Rules to change on an existing mapping intent. targetPath selects the stored rule. "
                + "Empty when not editing.")
        List<RuleChange> updateRules,
    @Description("Rules to remove from an existing mapping intent. Empty when not deleting a rule.")
        List<RuleChange> deleteRules,
    @Description(
            "Mapping intents to remove. Use sourceRef and targetRef of the stored intent. Empty "
                + "when not deleting an intent.")
        List<IntentChange> deleteIntents) {

  public MappingTurnCapture {
    outcome = outcome == null ? Kind.NONE : outcome;
    addIntents = addIntents == null ? List.of() : List.copyOf(addIntents);
    addRules = addRules == null ? List.of() : List.copyOf(addRules);
    clarificationReason = clarificationReason == null ? "" : clarificationReason.trim();
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    query =
        query == null
            ? new QuerySelector(null, null, null, null, null, false, "ANY")
            : query;
    updateRules = updateRules == null ? List.of() : List.copyOf(updateRules);
    deleteRules = deleteRules == null ? List.of() : List.copyOf(deleteRules);
    deleteIntents = deleteIntents == null ? List.of() : List.copyOf(deleteIntents);
  }

  public MappingTurnCapture(
      Kind outcome,
      List<IntentChange> addIntents,
      List<RuleChange> addRules,
      String clarificationReason,
      List<String> candidates) {
    this(outcome, addIntents, addRules, clarificationReason, candidates, null, List.of(), List.of(), List.of());
  }

  public MappingTurnCapture(
      Kind outcome,
      List<IntentChange> addIntents,
      List<RuleChange> addRules,
      String clarificationReason,
      List<String> candidates,
      QuerySelector query) {
    this(
        outcome,
        addIntents,
        addRules,
        clarificationReason,
        candidates,
        query,
        List.of(),
        List.of(),
        List.of());
  }

  public enum Kind {
    CHANGES,
    QUERY,
    NONE,
    CLARIFICATION
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QuerySelector(
      @Description("Existing mapping intent id. Empty when not selecting by id.")
          String mappingIntentId,
      @Description("Source interaction id or unique operation name. Empty when unconstrained.")
          String sourceRef,
      @Description("Target interaction id or unique operation name. Empty when unconstrained.")
          String targetRef,
      @Description("Source field path to look up. Empty when unconstrained.") String sourcePath,
      @Description("Target field path to look up. Empty when unconstrained.") String targetPath,
      @Description("True when the author asked which required targets remain unresolved.")
          boolean unresolvedOnly,
      @Description("ANY, MAPPED, or PASS_THROUGH.") String coverage) {

    public QuerySelector {
      mappingIntentId = blankToNull(mappingIntentId);
      sourceRef = blankToNull(sourceRef);
      targetRef = blankToNull(targetRef);
      sourcePath = blankToNull(sourcePath);
      targetPath = blankToNull(targetPath);
      coverage = coverage == null || coverage.isBlank() ? "ANY" : coverage.trim();
    }

    private static String blankToNull(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      return value.trim();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IntentChange(
      @Description("Approved source interaction id or unique operation name.") String sourceRef,
      @Description("Approved target interaction id or unique operation name.") String targetRef,
      @Description("Field copy, constant, default, or expression rules for this transition.")
          List<MappingIntentRule> rules,
      @Description("SCRIPT when the author asked for a script. Empty otherwise.")
          String implementationPreference) {

    public IntentChange {
      sourceRef = sourceRef == null ? "" : sourceRef.trim();
      targetRef = targetRef == null ? "" : targetRef.trim();
      rules = rules == null ? List.of() : List.copyOf(rules);
      implementationPreference =
          implementationPreference == null || implementationPreference.isBlank()
              ? null
              : implementationPreference.trim();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleChange(
      @Description("Existing mapping intent id from the current brief.") String mappingIntentId,
      @Description("Source field path or quoted constant. Empty when the rule is expression-only.")
          String sourcePath,
      @Description("Target field path this rule writes.") String targetPath,
      @Description("Template, conditional, default, JSON construction, or other expression.")
          String expression,
      @Description("Replacement target path when renaming the writer. Empty when the target stays.")
          String newTargetPath,
      @Description("Source interaction id or unique operation name when mappingIntentId is empty.")
          String sourceRef,
      @Description("Target interaction id or unique operation name when mappingIntentId is empty.")
          String targetRef) {

    public RuleChange {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
      sourcePath = sourcePath == null ? "" : sourcePath.trim();
      targetPath = targetPath == null ? "" : targetPath.trim();
      expression = expression == null || expression.isBlank() ? null : expression.trim();
      newTargetPath =
          newTargetPath == null || newTargetPath.isBlank() ? null : newTargetPath.trim();
      sourceRef = sourceRef == null ? "" : sourceRef.trim();
      targetRef = targetRef == null ? "" : targetRef.trim();
    }

    public RuleChange(
        String mappingIntentId, String sourcePath, String targetPath, String expression) {
      this(mappingIntentId, sourcePath, targetPath, expression, null, null, null);
    }
  }
}
