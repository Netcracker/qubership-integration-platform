package org.qubership.integration.platform.ai.qipknowledge.validation;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;

/** Deterministic security validator for compiler graph candidates. */
@ApplicationScoped
public class CompilerSecurityValidator {

  private static final String OWNER = "cip-security-validator";
  public static final String EXTERNAL_RBAC_POLICY = "CORPORATE_CIP_STANDARDS:12.4";
  private static final Set<String> CREDENTIAL_KEYS =
      Set.of("password", "secret", "token", "apiKey", "connectionString", "saslJaasConfig");

  public ValidationResult validate(ChainPlanGraph graph) {
    return validate(graph, null);
  }

  public ValidationResult validate(ChainPlanGraph graph, RequirementBrief brief) {
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
        counter = validateExternalRouteRbac(node, brief, issues, counter);
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
      ChainPlanNode node, RequirementBrief brief, List<ValidationIssue> issues, int counter) {
    if (!"true".equalsIgnoreCase(propertyValue(node, "externalRoute"))) {
      return counter;
    }
    if (brief != null && brief.facts().stream().anyMatch(fact -> {
      var conflict = fact.policyConflict();
      return conflict != null && conflict.decision() != null
          && EXTERNAL_RBAC_POLICY.equals(conflict.policyId())
          && "accessControlType".equals(conflict.propertyKey())
          && fact.interactionIds().equals(List.of(node.nodeId()))
          && !conflict.selectedValue().equals(propertyValue(node, "accessControlType"));
    })) {
      issues.add(blocker(counter++, node.nodeId(),
          "External route access control differs from the confirmed user choice",
          "Apply the confirmed accessControlType from the approved policy fact"));
      return counter;
    }
    if (!"RBAC".equalsIgnoreCase(propertyValue(node, "accessControlType"))) {
      if (hasConfirmedAccessControlChoice(node, brief)) {
        issues.add(new ValidationIssue("security-" + counter++, ValidationSeverity.WARNING,
            "The user confirmed " + propertyValue(node, "accessControlType")
                + " for this external route after reviewing the corporate RBAC recommendation",
            OWNER, List.of(node.nodeId()), List.of(), "Retain the confirmed choice; RBAC remains recommended"));
        return counter;
      }
      issues.add(
          blocker(
              counter++,
              node.nodeId(),
              "External route requires accessControlType=RBAC or a confirmed user choice",
              "Explain the corporate RBAC recommendation and ask whether to retain the requested value or use RBAC"));
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

  private static boolean hasConfirmedAccessControlChoice(ChainPlanNode node, RequirementBrief brief) {
    if (brief == null || node.nodeId() == null) {
      return false;
    }
    return brief.facts().stream().anyMatch(fact -> {
      var conflict = fact.policyConflict();
      return conflict != null && EXTERNAL_RBAC_POLICY.equals(conflict.policyId())
          && "accessControlType".equals(conflict.propertyKey())
          && "RBAC".equals(conflict.recommendedValue())
          && ("NONE".equals(conflict.requestedValue()) || "ABAC".equals(conflict.requestedValue()))
          && conflict.decision() == RequirementPolicyConflict.Decision.KEEP_REQUESTED
          && fact.interactionIds().equals(List.of(node.nodeId()))
          && conflict.selectedValue().equals(propertyValue(node, "accessControlType"));
    });
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
      if (!isCredentialKey(property.key())) {
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

  /** True when {@code propertyKey} is a credential the security check rejects as a literal. */
  public static boolean isCredentialKey(String propertyKey) {
    if (propertyKey == null || propertyKey.isBlank()) {
      return false;
    }
    String lowered = propertyKey.toLowerCase(Locale.ROOT);
    for (String key : CREDENTIAL_KEYS) {
      if (lowered.contains(key.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /** True when {@code value} is already a secured reference such as {@code #{NAME}}. */
  public static boolean isSecuredVariableReference(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    return trimmed.startsWith("#{") && trimmed.endsWith("}") && trimmed.length() > 3;
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
