package org.qubership.integration.platform.ai.qipknowledge.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Merges LLM-captured and deterministic validation issues into one report. */
public final class ValidationResultMerger {

  private ValidationResultMerger() {}

  public static ValidationResult merge(ValidationResult captured, ValidationResult deterministic) {
    Objects.requireNonNull(captured, "captured");
    Objects.requireNonNull(deterministic, "deterministic");

    List<ValidationIssue> merged = new ArrayList<>();
    Set<List<Object>> seen = new LinkedHashSet<>();
    appendUnique(merged, seen, sanitizeCapturedIssues(captured.issues()));
    appendUnique(merged, seen, deterministic.issues());

    boolean valid =
        merged.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
    String summary = buildSummary(valid, merged, captured.summary(), deterministic.summary());
    return new ValidationResult(valid, List.copyOf(merged), summary);
  }

  /**
   * LLM-captured blockers without rule references are advisory only. Deterministic blockers are
   * merged unchanged.
   */
  private static List<ValidationIssue> sanitizeCapturedIssues(List<ValidationIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return List.of();
    }
    List<ValidationIssue> sanitized = new ArrayList<>(issues.size());
    for (ValidationIssue issue : issues) {
      if (issue == null) {
        continue;
      }
      if (issue.severity() == ValidationSeverity.BLOCKER && hasNoRuleRefs(issue)) {
        sanitized.add(downgradeToWarning(issue));
      } else {
        sanitized.add(issue);
      }
    }
    return List.copyOf(sanitized);
  }

  private static boolean hasNoRuleRefs(ValidationIssue issue) {
    return issue.ruleRefs() == null || issue.ruleRefs().isEmpty();
  }

  private static ValidationIssue downgradeToWarning(ValidationIssue issue) {
    return new ValidationIssue(
        issue.issueId(),
        ValidationSeverity.WARNING,
        issue.message(),
        issue.ownerCapabilityId(),
        issue.affectedNodeIds(),
        issue.ruleRefs(),
        issue.suggestedFix(), issue.findingCode(), issue.originRecordId(), issue.fieldPointer());
  }

  private static void appendUnique(
      List<ValidationIssue> merged, Set<List<Object>> seen, List<ValidationIssue> issues) {
    if (issues == null) {
      return;
    }
    for (ValidationIssue issue : issues) {
      if (issue == null) {
        continue;
      }
      List<Object> key = dedupeKey(issue);
      if (seen.add(key)) {
        merged.add(issue);
      }
    }
  }

  private static List<Object> dedupeKey(ValidationIssue issue) {
    String owner = issue.ownerCapabilityId() != null ? issue.ownerCapabilityId() : "";
    String message = issue.message() != null ? issue.message() : "";
    return List.of(Objects.toString(issue.severity(), ""), owner, message,
        issue.affectedNodeIds() == null ? List.of() : issue.affectedNodeIds(),
        Objects.toString(issue.findingCode(), ""), Objects.toString(issue.originRecordId(), ""),
        Objects.toString(issue.fieldPointer(), ""));
  }

  private static String buildSummary(
      boolean valid, List<ValidationIssue> issues, String capturedSummary, String deterministicSummary) {
    if (valid) {
      if (issues.isEmpty()) {
        return "Plan validation passed";
      }
      long warnings =
          issues.stream()
              .filter(issue -> issue.severity() != ValidationSeverity.BLOCKER)
              .count();
      if (warnings > 0) {
        return "Plan validation passed with " + warnings + " advisory issue(s)";
      }
      return "Plan validation passed";
    }
    long blockers =
        issues.stream().filter(issue -> issue.severity() == ValidationSeverity.BLOCKER).count();
    if (blockers > 0) {
      return "Plan validation failed with " + blockers + " blocker(s)";
    }
    if (deterministicSummary != null && !deterministicSummary.isBlank()) {
      return deterministicSummary;
    }
    if (capturedSummary != null && !capturedSummary.isBlank()) {
      return capturedSummary;
    }
    return "Plan validation failed";
  }
}
