package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;
import java.util.stream.Collectors;

/** Raised when runtime-supported generators are missing policy contracts or mappings. */
public class CompilerGeneratorPolicyValidationException extends RuntimeException {

  private final List<CompilerGeneratorPolicyIssue> issues;

  public CompilerGeneratorPolicyValidationException(
      String message, List<CompilerGeneratorPolicyIssue> issues) {
    super(formatMessage(message, issues));
    this.issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public List<CompilerGeneratorPolicyIssue> issues() {
    return issues;
  }

  private static String formatMessage(String message, List<CompilerGeneratorPolicyIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return message;
    }
    String details =
        issues.stream()
            .map(issue -> issue.generatorId() + " (" + issue.skillId() + "): " + issue.reason())
            .collect(Collectors.joining(System.lineSeparator()));
    return message + System.lineSeparator() + details;
  }
}
