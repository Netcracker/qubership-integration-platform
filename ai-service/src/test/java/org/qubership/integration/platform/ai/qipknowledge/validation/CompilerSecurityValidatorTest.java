package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class CompilerSecurityValidatorTest {

  @Test
  void externalRouteWithoutRbacBlocks() {
    CompilerSecurityValidator validator = new CompilerSecurityValidator();

    ValidationResult result = validator.validate(graph(httpTrigger(List.of(
        new PlanProperty("externalRoute", "true"),
        new PlanProperty("accessControlType", "NONE")))));

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER));
  }

  @Test
  void wildcardRoleBlocks() {
    CompilerSecurityValidator validator = new CompilerSecurityValidator();

    ValidationResult result = validator.validate(graph(httpTrigger(List.of(
        new PlanProperty("externalRoute", "true"),
        new PlanProperty("accessControlType", "RBAC"),
        new PlanProperty("roles", "[\"*\"]")))));

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("wildcard")));
  }

  @Test
  void literalCredentialBlocksWithoutLeakingValue() {
    CompilerSecurityValidator validator = new CompilerSecurityValidator();
    String leaked = "VerySecretValue123";

    ValidationResult result = validator.validate(graph(scriptWithCredential(leaked)));

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("credential")));
    assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains(leaked)));
  }

  @Test
  void securedVariableReferencePasses() {
    CompilerSecurityValidator validator = new CompilerSecurityValidator();

    ValidationResult result =
        validator.validate(graph(scriptWithCredential("#{SECURED_VARIABLE}")));

    assertTrue(result.valid());
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph("1.0", new ChainSection("sales", "Sales"), List.of(nodes), List.of());
  }

  private static ChainPlanNode httpTrigger(List<PlanProperty> properties) {
    return new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, properties);
  }

  private static ChainPlanNode scriptWithCredential(String credentialValue) {
    return new ChainPlanNode(
        "script-1",
        "script",
        "Script",
        null,
        null,
        List.of(new PlanProperty("password", credentialValue)));
  }
}
