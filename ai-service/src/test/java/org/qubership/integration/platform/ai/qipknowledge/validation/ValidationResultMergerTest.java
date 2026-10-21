package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.CipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.CipKnowledgeRefType;

class ValidationResultMergerTest {

  @Test
  void separatorCharactersCannotMergeDifferentOwnersOrNodeLists() {
    var first = new ValidationIssue("first", ValidationSeverity.BLOCKER, "part|value",
        "owner", List.of("a,b", "c"), List.of(), "Correct the first issue");
    var otherOwner = new ValidationIssue("other-owner", ValidationSeverity.BLOCKER, "value",
        "owner|part", first.affectedNodeIds(), List.of(), "Correct the other owner");
    var otherNodes = new ValidationIssue("other-nodes", ValidationSeverity.BLOCKER, first.message(),
        first.ownerCapabilityId(), List.of("a", "b,c"), List.of(), "Correct the other nodes");
    var merged = ValidationResultMerger.merge(new ValidationResult(true, List.of(), "empty"),
        new ValidationResult(false, List.of(first, otherOwner, otherNodes), "blocked"));
    assertEquals(List.of(first, otherOwner, otherNodes), merged.issues());
  }

  @ParameterizedTest
  @EnumSource(value = ValidationSeverity.class, names = {"WARNING", "BLOCKER"})
  void capturedAdviceCannotHideAnIdenticalDeterministicBlocker(ValidationSeverity capturedSeverity) {
    var capturedIssue = new ValidationIssue("captured-1", capturedSeverity,
        "Missing script body", "cip-script-generator", List.of("script-1"), List.of(), "Add script");
    var deterministicIssue = new ValidationIssue("materialization-1", ValidationSeverity.BLOCKER,
        capturedIssue.message(), capturedIssue.ownerCapabilityId(), capturedIssue.affectedNodeIds(),
        List.of(), "Add the complete script");

    var merged = ValidationResultMerger.merge(
        new ValidationResult(false, List.of(capturedIssue), "advice"),
        new ValidationResult(false, List.of(deterministicIssue), "blocked"));

    assertFalse(merged.valid());
    assertTrue(merged.hasBlockingIssues());
    assertTrue(merged.issues().contains(deterministicIssue));
  }

  @Test
  void mergesDeterministicBlockersIntoCapturedValidReport() {
    ValidationResult captured = new ValidationResult(true, List.of(), "Plan validation passed");
    ValidationIssue blocker =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.BLOCKER,
            "Chain has no trigger element",
            "plan-validator",
            List.of(),
            List.of(),
            "Add a trigger");
    ValidationResult deterministic =
        new ValidationResult(false, List.of(blocker), "Plan validation failed with 1 blocker(s)");

    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    assertFalse(merged.valid());
    assertEquals(1, merged.issues().size());
    assertTrue(merged.summary().contains("1 blocker"));
  }

  @Test
  void downgradesCapturedBlockerWithoutRuleRefsWhenDeterministicPasses() {
    ValidationIssue blocker =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.BLOCKER,
            "http-trigger must set accessControlType",
            "plan-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Add accessControlType");
    ValidationResult captured =
        new ValidationResult(false, List.of(blocker), "Plan validation failed with 1 blocker(s)");
    ValidationResult deterministic = new ValidationResult(true, List.of(), "Plan validation passed");

    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    assertTrue(merged.valid());
    assertEquals(1, merged.issues().size());
    assertEquals(ValidationSeverity.WARNING, merged.issues().get(0).severity());
    assertTrue(merged.summary().contains("advisory"));
  }

  @Test
  void keepsCapturedBlockerWhenRuleRefsPresent() {
    ValidationIssue blocker =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.BLOCKER,
            "Deprecated element type",
            "plan-validator",
            List.of("n1"),
            List.of(
                CipKnowledgeCitation.declaredRule("VR-E-010", CipKnowledgeRefType.VALIDATION_RULE)),
            "Replace with v2 equivalent");
    ValidationResult captured =
        new ValidationResult(false, List.of(blocker), "Plan validation failed with 1 blocker(s)");
    ValidationResult deterministic = new ValidationResult(true, List.of(), "Plan validation passed");

    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    assertFalse(merged.valid());
    assertEquals(ValidationSeverity.BLOCKER, merged.issues().get(0).severity());
  }

  @Test
  void keepsDeterministicScriptMaterializationBlockerWhenCapturedBlockerDowngraded() {
    ValidationIssue capturedBlocker =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.BLOCKER,
            "Script node is missing a Groovy body",
            "plan-validator",
            List.of("script-1"),
            List.of(),
            "Add script body");
    ValidationResult captured =
        new ValidationResult(false, List.of(capturedBlocker), "Plan validation failed with 1 blocker(s)");
    ValidationIssue materializationBlocker =
        new ValidationIssue(
            "materialization-1",
            ValidationSeverity.BLOCKER,
            "Node 'script-1' (script) is missing required materialization property 'script'",
            "cip-script-generator",
            List.of("script-1"),
            List.of(),
            "Add script through cip-script-generator");
    ValidationResult deterministic =
        new ValidationResult(
            false, List.of(materializationBlocker), "Plan validation failed with 1 blocker(s)");

    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    assertFalse(merged.valid());
    assertTrue(merged.hasBlockingIssues());
    assertTrue(
        merged.issues().stream()
            .anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER));
  }

  @Test
  void normalizesWarningOnlyCaptureToValidWhenDeterministicPasses() {
    ValidationIssue warning =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.WARNING,
            "Consider adding error handling",
            "plan-validator",
            List.of(),
            List.of(),
            "Add try-catch");
    ValidationResult captured =
        new ValidationResult(false, List.of(warning), "Advisory findings only");
    ValidationResult deterministic = new ValidationResult(true, List.of(), "Plan validation passed");

    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    assertTrue(merged.valid());
    assertEquals(1, merged.issues().size());
  }
}
