package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

/** Records deterministic and compiler plan-validation findings for a candidate plan. */
public record PlanValidationResult(List<PlanValidationFinding> findings) {

  public PlanValidationResult {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /** Returns false when any finding is a blocker. */
  public boolean approvalEligible() {
    return findings.stream().noneMatch(PlanValidationFinding::blocker);
  }

  public static PlanValidationResult fromCompiler(
      ValidationResult compilerValidation, List<String> exclusionFindings) {
    List<PlanValidationFinding> findings = new ArrayList<>();
    if (compilerValidation != null) {
      if (compilerValidation.issues() != null) {
        for (ValidationIssue issue : compilerValidation.issues()) {
          if (issue == null || issue.severity() != ValidationSeverity.BLOCKER) {
            continue;
          }
          // Legacy validator reports still use message-based codes.
          boolean hasTypedCode = issue.findingCode() != null && !issue.findingCode().isBlank();
          String propertyCode = hasTypedCode ? null : propertyFindingCode(issue.message());
          String issueId = issue.issueId() == null ? "" : issue.issueId().trim();
          String code = hasTypedCode ? issue.findingCode() : typedFindingCode(issue.message(), propertyCode, issueId);
          String message =
              propertyCode != null
                  ? propertyCode
                      + (issue.message() == null || issue.message().isBlank()
                          ? ""
                          : ": " + issue.message())
                  : issue.message() == null || issue.message().isBlank()
                      ? "compiler validation blocker"
                      : issue.message();
          findings.add(
              new PlanValidationFinding(code, withCorrectiveEvidence(message, issue), true, null,
                  issue.originRecordId(), issue.fieldPointer()));
        }
      }
      if (!compilerValidation.valid()
          && findings.stream().noneMatch(PlanValidationFinding::blocker)) {
        findings.add(
            new PlanValidationFinding(
                "COMPILER_INVALID",
                compilerValidation.summary() == null
                    ? "compiler validation failed"
                    : compilerValidation.summary(),
                true));
      }
    } else if (exclusionFindings == null || exclusionFindings.isEmpty()) {
      findings.add(
          new PlanValidationFinding(
              "COMPILER_MISSING", "compiler validation result is required", true));
    }
    if (exclusionFindings != null) {
      for (String exclusion : exclusionFindings) {
        if (exclusion == null || exclusion.isBlank()) {
          continue;
        }
        String mapped = propertyFindingCode(exclusion);
        String code = typedFindingCode(exclusion, mapped, mapped != null ? mapped : "EXCLUSION");
        findings.add(new PlanValidationFinding(code, exclusion, true));
      }
    }
    return new PlanValidationResult(findings);
  }

  private static String withCorrectiveEvidence(String message, ValidationIssue issue) {
    StringBuilder text = new StringBuilder(message == null ? "" : message);
    if (issue.affectedNodeIds() != null && !issue.affectedNodeIds().isEmpty()) {
      text.append(" affectedNodes=").append(String.join(",", issue.affectedNodeIds()));
    }
    if (issue.ownerCapabilityId() != null && !issue.ownerCapabilityId().isBlank()) {
      text.append(" owner=").append(issue.ownerCapabilityId().trim());
    }
    if (issue.suggestedFix() != null && !issue.suggestedFix().isBlank()) {
      text.append(" suggestedFix=").append(issue.suggestedFix().trim());
    }
    return text.toString();
  }

  /**
   * Assigns a typed finding code at the compiler boundary. Property-path codes such as {@code
   * else.condition} stay as-is (they map to {@link RecoveryCauseCode#UNKNOWN_PROPERTY}). Generic
   * unknown-property and missing-required messages get an explicit cause code so routing never
   * re-reads the English message.
   */
  private static String typedFindingCode(String message, String propertyCode, String fallback) {
    if (propertyCode != null && !propertyCode.isBlank()) {
      return propertyCode;
    }
    String haystack = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
    if (haystack.contains("unknown property key")) {
      return RecoveryCauseCode.UNKNOWN_PROPERTY.name();
    }
    if (haystack.contains("required")
        && (haystack.contains("property") || haystack.contains("setting"))) {
      return RecoveryCauseCode.MISSING_REQUIRED_PROPERTY.name();
    }
    return fallback == null || fallback.isBlank() ? "COMPILER_BLOCKER" : fallback;
  }

  /** Maps structural messages such as {@code (else) has unknown property key 'condition'} → {@code else.condition}. */
  private static String propertyFindingCode(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String trimmed = message.trim();
    if (trimmed.equals("else.condition") || trimmed.equals("else.priority")) {
      return trimmed;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "\\((else)\\)\\s+has unknown property key '(condition|priority)'",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(trimmed);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase(java.util.Locale.ROOT)
          + "."
          + matcher.group(2).toLowerCase(java.util.Locale.ROOT);
    }
    if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("else.condition")) {
      return "else.condition";
    }
    if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("else.priority")) {
      return "else.priority";
    }
    return null;
  }
}
