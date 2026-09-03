package org.qubership.integration.platform.ai.plan;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

/**
 * Typed interpretation of one mapping conversation turn. The language model does not return a
 * replacement requirement brief.
 */
public sealed interface MappingTurnResult
    permits MappingTurnResult.Changes,
        MappingTurnResult.Query,
        MappingTurnResult.Clarification,
        MappingTurnResult.ConfirmationRequired {

  static Changes changes(Operation... operations) {
    return new Changes(List.of(operations));
  }

  /** Mapping mutations to apply atomically to {@code mappingIntents}. */
  record Changes(List<Operation> operations) implements MappingTurnResult {
    public Changes {
      operations = operations == null ? List.of() : List.copyOf(operations);
    }
  }

  /** Read-only lookup. The applicator does not change the brief. */
  record Query(MappingQuerySelector selector) implements MappingTurnResult {}

  /** The named transition or rule is ambiguous or missing. */
  record Clarification(String reason, List<String> candidates) implements MappingTurnResult {
    public Clarification {
      reason = reason == null ? "" : reason;
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
  }

  /**
   * An irreversible mapping change needs an explicit decision. Author-facing confirmation stays
   * outside this type.
   */
  record ConfirmationRequired(Kind kind, String mappingIntentId, String targetPath)
      implements MappingTurnResult {
    public ConfirmationRequired {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
      targetPath = targetPath == null || targetPath.isBlank() ? null : targetPath.trim();
    }

    public enum Kind {
      DELETE_INTENT,
      DELETE_LAST_RULE
    }
  }

  sealed interface Operation
      permits AddIntent, AddRule, UpdateRule, DeleteRule, DeleteIntent {}

  /**
   * Names approved source and target occurrences. The runtime assigns the mapping intent id and
   * ports after it resolves the transition. {@code implementationPreference} is optional; SCRIPT
   * when the author asked for a script.
   */
  record AddIntent(
      String sourceRef,
      String targetRef,
      List<MappingIntentRule> rules,
      String implementationPreference)
      implements Operation {
    public AddIntent {
      sourceRef = sourceRef == null ? "" : sourceRef.trim();
      targetRef = targetRef == null ? "" : targetRef.trim();
      rules = rules == null ? List.of() : List.copyOf(rules);
      implementationPreference =
          implementationPreference == null || implementationPreference.isBlank()
              ? null
              : implementationPreference.trim();
    }

    public AddIntent(String sourceRef, String targetRef, List<MappingIntentRule> rules) {
      this(sourceRef, targetRef, rules, null);
    }
  }

  record AddRule(String mappingIntentId, String sourcePath, String targetPath, String expression)
      implements Operation {
    public AddRule {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
      sourcePath = sourcePath == null ? "" : sourcePath.trim();
      targetPath = targetPath == null ? "" : targetPath.trim();
      expression = expression == null || expression.isBlank() ? null : expression.trim();
    }
  }

  /**
   * {@code targetPath} selects the existing rule. {@code newTargetPath} is the replacement target
   * when the author is renaming the writer.
   */
  record UpdateRule(
      String mappingIntentId,
      String targetPath,
      String sourcePath,
      String newTargetPath,
      String expression)
      implements Operation {
    public UpdateRule {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
      targetPath = targetPath == null ? "" : targetPath.trim();
      sourcePath = sourcePath == null ? "" : sourcePath.trim();
      newTargetPath =
          newTargetPath == null || newTargetPath.isBlank() ? null : newTargetPath.trim();
      expression = expression == null || expression.isBlank() ? null : expression.trim();
    }
  }

  record DeleteRule(String mappingIntentId, String targetPath) implements Operation {
    public DeleteRule {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
      targetPath = targetPath == null ? "" : targetPath.trim();
    }
  }

  record DeleteIntent(String mappingIntentId) implements Operation {
    public DeleteIntent {
      mappingIntentId = mappingIntentId == null ? "" : mappingIntentId.trim();
    }
  }
}
