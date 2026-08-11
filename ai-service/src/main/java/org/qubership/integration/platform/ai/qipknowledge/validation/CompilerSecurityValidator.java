package org.qubership.integration.platform.ai.qipknowledge.validation;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Deterministic security validator for compiler graph candidates. */
@ApplicationScoped
public class CompilerSecurityValidator {

  private static final String OWNER = "cip-security-validator";
  private static final Set<String> CREDENTIAL_KEYS =
      Set.of("password", "secret", "token", "apiKey", "connectionString", "saslJaasConfig");

  public ValidationResult validate(ChainPlanGraph graph) {
    List<ValidationIssue> issues = new ArrayList<>();
    int counter = 1;
    if (graph == null || graph.nodes() == null) {
      return new ValidationResult(true, List.of(), "security validation passed");
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      if ("http-trigger".equals(trim(node.type()))) {
        counter = validateExternalRouteRbac(node, issues, counter);
      }
      counter = validateCredentialLiterals(node, issues, counter);
    }
    boolean valid = issues.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
    return new ValidationResult(
        valid,
        List.copyOf(issues),
        valid
            ? "security validation passed"
            : "security validation failed with "
                + issues.stream().filter(issue -> issue.severity() == ValidationSeverity.BLOCKER).count()
                + " blocker(s)");
  }

  private static int validateExternalRouteRbac(
      ChainPlanNode node, List<ValidationIssue> issues, int counter) {
    if (!"true".equalsIgnoreCase(propertyValue(node, "externalRoute"))) {
      return counter;
    }
    if (!"RBAC".equalsIgnoreCase(propertyValue(node, "accessControlType"))) {
      issues.add(
          blocker(
              counter++,
              node.nodeId(),
              "External route requires accessControlType=RBAC",
              "Set accessControlType to RBAC and provide explicit roles"));
      return counter;
    }
    String rolesValue = propertyValue(node, "roles");
    if (rolesValue == null || rolesValue.isBlank() || "[]".equals(rolesValue.trim())) {
      issues.add(
          blocker(
              counter++,
              node.nodeId(),
              "External route RBAC requires a non-empty roles list",
              "Configure one or more explicit RBAC roles"));
      return counter;
    }
    if (rolesValue.contains("*")) {
      issues.add(
          blocker(
              counter++,
              node.nodeId(),
              "External route RBAC roles must not include wildcard role",
              "Replace wildcard with explicit roles"));
    }
    return counter;
  }

  private static int validateCredentialLiterals(
      ChainPlanNode node, List<ValidationIssue> issues, int counter) {
    if (node.properties() == null) {
      return counter;
    }
    for (PlanProperty property : node.properties()) {
      if (property == null || property.key() == null || property.value() == null) {
        continue;
      }
      if (!containsCredentialKey(property.key())) {
        continue;
      }
      String value = property.value().trim();
      if (value.isBlank() || isSecuredVariableReference(value)) {
        continue;
      }
      issues.add(
          blocker(
              counter++,
              node.nodeId(),
              "Literal credential material is forbidden for key '" + property.key() + "'",
              "Use secured variable reference format #{NAME}"));
    }
    return counter;
  }

  private static boolean containsCredentialKey(String propertyKey) {
    String lowered = propertyKey.toLowerCase(Locale.ROOT);
    for (String key : CREDENTIAL_KEYS) {
      if (lowered.contains(key.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSecuredVariableReference(String value) {
    return value.startsWith("#{") && value.endsWith("}") && value.length() > 3;
  }

  private static String propertyValue(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }

  private static ValidationIssue blocker(
      int counter, String nodeId, String message, String suggestedFix) {
    return new ValidationIssue(
        "security-" + counter,
        ValidationSeverity.BLOCKER,
        message,
        OWNER,
        nodeId == null ? List.of() : List.of(nodeId),
        List.of(),
        suggestedFix);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
