package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

class CompilerQualityValidatorTest {

  @Test
  void namingViolationsAreWarnings() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "123Invalid", Map.of("role-1", "TICKET-123"), List.of(), List.of()),
            graph());

    assertTrue(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.severity() == ValidationSeverity.WARNING));
  }

  @Test
  void missingMaterializationPropertiesAreBlockers() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new ValidationIssue(
                    "validation-1",
                    ValidationSeverity.BLOCKER,
                    "missing required property",
                    "cip-structure-generator",
                    List.of("script"),
                    List.of(),
                    "fix")));
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "Sales.Inbound.Create", Map.of("role-1", "Script"), List.of(), List.of()),
            graph());

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER));
  }

  @Test
  void matchingNamingManifestPasses() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "Sales.Inbound.Create", Map.of("role-1", "ScriptNode"), List.of(), List.of()),
            graph());

    assertTrue(result.valid());
    assertTrue(result.issues().isEmpty());
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("sales", "Sales"),
        List.of(
            new ChainPlanNode(
                "trigger",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/sales"),
                    new PlanProperty("httpMethodRestrict", "POST"))),
            new ChainPlanNode(
                "script",
                "script",
                "ScriptNode",
                null,
                null,
                List.of(new PlanProperty("scriptBody", "noop")))),
        List.of());
  }
}
