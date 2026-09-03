package org.qubership.integration.platform.ai.plan;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

/**
 * Factual mapping lookup from the stored requirement brief. Identifiers, paths, expressions, and
 * statuses are the stored values.
 */
public record MappingQueryAnswer(
    String language,
    boolean matchFound,
    List<TransitionFact> transitions,
    List<RuleFact> rules,
    List<String> unresolvedTargetPaths,
    String rendered) {

  public MappingQueryAnswer {
    language = language == null || language.isBlank() ? "en" : language;
    transitions = transitions == null ? List.of() : List.copyOf(transitions);
    rules = rules == null ? List.of() : List.copyOf(rules);
    unresolvedTargetPaths =
        unresolvedTargetPaths == null ? List.of() : List.copyOf(unresolvedTargetPaths);
    rendered = rendered == null ? "" : rendered;
  }

  public record TransitionFact(
      String sourceRef, String targetRef, String mappingIntentId, boolean passThrough) {

    public TransitionFact {
      sourceRef = sourceRef == null ? "" : sourceRef;
      targetRef = targetRef == null ? "" : targetRef;
      mappingIntentId =
          mappingIntentId == null || mappingIntentId.isBlank() ? null : mappingIntentId;
    }
  }

  public record RuleFact(
      String mappingIntentId,
      String sourceRef,
      String targetRef,
      String sourcePath,
      String targetPath,
      String expression,
      MappingRuleStatus status) {

    public RuleFact {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId;
      sourceRef = sourceRef == null ? "" : sourceRef;
      targetRef = targetRef == null ? "" : targetRef;
      sourcePath = sourcePath == null ? "" : sourcePath;
      targetPath = targetPath == null ? "" : targetPath;
    }
  }
}
