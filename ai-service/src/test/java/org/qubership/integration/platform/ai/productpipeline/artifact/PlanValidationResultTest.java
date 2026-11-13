package org.qubership.integration.platform.ai.productpipeline.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

class PlanValidationResultTest {

  @Test
  void compilerBoundaryPreservesTypedIdentityWithoutReadingTheMessage() throws Exception {
    var mapper = new ObjectMapper();
    for (String message : List.of("Correct this value", "The generated setting is absent")) {
      var issueJson = mapper.createObjectNode();
      issueJson.put("issueId", "validation-17");
      issueJson.put("severity", "BLOCKER");
      issueJson.put("message", message);
      issueJson.put("findingCode", "MISSING_REQUIRED_PROPERTY");
      issueJson.put("originRecordId", "Node/A~B");
      issueJson.put("fieldPointer", "/properties/Topic~1A~0B");
      var issue = mapper.treeToValue(issueJson,
          org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue.class);
      var result = PlanValidationResult.fromCompiler(
          new ValidationResult(false, List.of(issue), "blocked"), List.of());
      var finding = result.findings().getFirst();
      assertEquals("MISSING_REQUIRED_PROPERTY", finding.code());
      assertEquals("Node/A~B", finding.originRecordId());
      assertEquals("/properties/Topic~1A~0B", finding.fieldPointer());
    }
  }

  @Test
  void approvalEligibleRequiresValidCompilerAndEmptyExclusions() {
    var eligible =
        PlanValidationResult.fromCompiler(
            new ValidationResult(true, List.of(), "ok"), List.of());
    assertTrue(eligible.approvalEligible());
    var blocked =
        PlanValidationResult.fromCompiler(
            new ValidationResult(true, List.of(), "ok"), List.of("forbidden element"));
    assertFalse(blocked.approvalEligible());
  }

  @Test
  void buildValidationResultPropagatesElseConditionBlockerCode() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "validation-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "node 'else-1' (else) has unknown property key 'condition'.",
            "plan-validator",
            List.of("else-1"),
            List.of(),
            "Remove condition from else");
    var result =
        PlanValidationResult.fromCompiler(
            new ValidationResult(false, List.of(issue), "Plan validation failed with 1 blocker(s)"),
            List.of());

    assertFalse(result.approvalEligible());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "else.condition".equals(finding.code())
                        && finding.blocker()
                        && finding.message().contains("else.condition")),
        result.findings().toString());
  }

  @Test
  void buildValidationResultKeepsSecurityIssueIdAsFindingCode() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "security-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "External route RBAC requires a non-empty roles list",
            "cip-security-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Configure one or more explicit RBAC roles");
    var result =
        PlanValidationResult.fromCompiler(
            new ValidationResult(false, List.of(issue), "security validation failed with 1 blocker(s)"),
            List.of());

    assertFalse(result.approvalEligible());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "security-1".equals(finding.code())
                        && finding.blocker()
                        && finding.message().contains("RBAC")
                        && finding.message().contains("affectedNodes=http-trigger-1")
                        && finding.message().contains("owner=cip-security-validator")
                        && finding.message().contains(
                            "suggestedFix=Configure one or more explicit RBAC roles")),
        result.findings().toString());
  }

  @Test
  void buildValidationResultKeepsHookRepairEvidence() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "materialization-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "Node 'call-sf' (service-call) is missing required materialization property 'after.script'",
            "cip-service-call-generator",
            List.of("call-sf"),
            List.of(),
            "Include the complete Groovy script body inside the native before/after hook"
                + " through cip-service-call-generator");
    var result =
        PlanValidationResult.fromCompiler(
            new ValidationResult(false, List.of(issue), "materialization failed"), List.of());

    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.blocker()
                        && finding.message().contains("after.script")
                        && finding.message().contains("affectedNodes=call-sf")
                        && finding.message().contains("owner=cip-service-call-generator")
                        && finding.message().contains(
                            "suggestedFix=Include the complete Groovy script body")),
        result.findings().toString());
  }
}
