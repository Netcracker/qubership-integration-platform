package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class CompilerValidationPipelineTest {

  @Test
  void bundleContainsEveryCompilerValidationPass() {
    CompilerValidationPipeline pipeline =
        new CompilerValidationPipeline(
            graph -> valid("elements"),
            graph -> valid("structure"),
            graph -> valid("configuration"),
            security(valid("security")),
            quality(valid("quality")));

    CompilerValidationBundle bundle =
        pipeline.validate("digest-1", new NamingManifest(1, "Sales.Inbound.Create", java.util.Map.of(), List.of(), List.of()), graph());

    assertEquals("digest-1", bundle.graphDigest());
    assertEquals(
        Set.of(
            "cip-element-validator",
            "cip-structural-validator",
            "cip-configuration-validator",
            "cip-security-validator",
            "cip-quality-validator"),
        bundle.passes().stream().map(pass -> pass.validatorSkillId()).collect(java.util.stream.Collectors.toSet()));
    assertTrue(bundle.approvalEligible());
  }

  @Test
  void bundleFailsWhenAnyPassHasBlocker() {
    CompilerValidationPipeline pipeline =
        new CompilerValidationPipeline(
            graph -> valid("elements"),
            graph ->
                new ValidationResult(
                    false,
                    List.of(
                        new ValidationIssue(
                            "validation-1",
                            ValidationSeverity.BLOCKER,
                            "broken structure",
                            "cip-structural-validator",
                            List.of(),
                            List.of(),
                            "fix")),
                    "blocked"),
            graph -> valid("configuration"),
            security(valid("security")),
            quality(valid("quality")));

    CompilerValidationBundle bundle =
        pipeline.validate("digest-1", new NamingManifest(1, "Sales.Inbound.Create", java.util.Map.of(), List.of(), List.of()), graph());

    assertFalse(bundle.approvalEligible());
    assertTrue(
        bundle.passes().stream()
            .anyMatch(pass -> "cip-structural-validator".equals(pass.validatorSkillId()) && !pass.result().valid()));
  }

  private static ValidationResult valid(String summary) {
    return new ValidationResult(true, List.of(), summary);
  }

  private static CompilerSecurityValidator security(ValidationResult result) {
    CompilerSecurityValidator validator = mock(CompilerSecurityValidator.class);
    when(validator.validate(org.mockito.ArgumentMatchers.any())).thenReturn(result);
    return validator;
  }

  private static CompilerQualityValidator quality(ValidationResult result) {
    CompilerQualityValidator validator = mock(CompilerQualityValidator.class);
    when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(result);
    return validator;
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
                    new PlanProperty("httpMethodRestrict", "POST"),
                    new PlanProperty("externalRoute", "false"))),
            new ChainPlanNode(
                "script",
                "script",
                "MapPayload",
                null,
                null,
                List.of(new PlanProperty("script", "return body")))),
        List.of());
  }
}
