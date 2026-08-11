package org.qubership.integration.platform.ai.qipknowledge.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

/** Deterministic quality validator for naming and materialization constraints. */
@ApplicationScoped
public class CompilerQualityValidator {

  private static final String OWNER = "cip-quality-validator";
  private static final Pattern CHAIN_NAME_PATTERN =
      Pattern.compile("^[A-Z][A-Za-z0-9]*\\.[A-Z][A-Za-z0-9]*\\.[A-Z][A-Za-z0-9]*$");
  private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][A-Za-z0-9]*$");

  private final MaterializationRequirementsValidator materializationRequirementsValidator;

  @Inject
  public CompilerQualityValidator(
      MaterializationRequirementsValidator materializationRequirementsValidator) {
    this.materializationRequirementsValidator =
        Objects.requireNonNull(materializationRequirementsValidator, "materializationRequirementsValidator");
  }

  public ValidationResult validate(NamingManifest namingManifest, ChainPlanGraph graph) {
    List<ValidationIssue> issues = new ArrayList<>();
    int counter = 1;

    for (ValidationIssue issue : materializationRequirementsValidator.validate(graph)) {
      issues.add(
          new ValidationIssue(
              "quality-" + counter++,
              issue.severity(),
              issue.message(),
              issue.ownerCapabilityId(),
              issue.affectedNodeIds(),
              issue.ruleRefs(),
              issue.suggestedFix()));
    }

    counter = validateNamingManifest(namingManifest, issues, counter);
    validateGraphProperties(graph, issues, counter);

    boolean valid = issues.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
    return new ValidationResult(
        valid,
        List.copyOf(issues),
        valid
            ? "quality validation passed"
            : "quality validation failed with "
                + issues.stream().filter(issue -> issue.severity() == ValidationSeverity.BLOCKER).count()
                + " blocker(s)");
  }

  private static int validateNamingManifest(
      NamingManifest namingManifest, List<ValidationIssue> issues, int counter) {
    if (namingManifest == null) {
      return warning(
          issues,
          counter,
          "Naming manifest is missing",
          "Provide naming manifest from cip-naming-generator");
    }
    String chainName = namingManifest.chainName();
    if (chainName == null || !CHAIN_NAME_PATTERN.matcher(chainName).matches()) {
      counter =
          warning(
              issues,
              counter,
              "Chain name should match {Domain}.{Direction}.{Action}",
              "Use a chain name like Sales.Inbound.Create");
    }
    Map<String, String> labels = namingManifest.labelsByRoleId();
    Set<String> uniqueLabels = new HashSet<>();
    for (Map.Entry<String, String> entry : labels.entrySet()) {
      String value = entry.getValue();
      if (value == null || value.isBlank()) {
        counter =
            warning(
                issues,
                counter,
                "Naming label must be non-blank for role '" + entry.getKey() + "'",
                "Provide a human-readable unique label");
        continue;
      }
      if (!uniqueLabels.add(value)) {
        counter =
            warning(
                issues,
                counter,
                "Naming labels should be unique: '" + value + "' is duplicated",
                "Use unique labels in naming manifest");
      }
      if (isTicketNumberLike(value) || startsWithDigit(value)) {
        counter =
            warning(
                issues,
                counter,
                "Naming label should not start with ticket/number pattern: '" + value + "'",
                "Use semantic labels without ticket IDs or numeric prefixes");
      }
    }
    return counter;
  }

  private static boolean isTicketNumberLike(String value) {
    int dash = value.indexOf('-');
    if (dash <= 0 || dash == value.length() - 1) {
      return false;
    }
    String prefix = value.substring(0, dash);
    String suffix = value.substring(dash + 1);
    return prefix.chars().allMatch(Character::isUpperCase)
        && suffix.chars().allMatch(ch -> Character.isDigit(ch) || Character.isLetter(ch));
  }

  private static boolean startsWithDigit(String value) {
    return !value.isEmpty() && Character.isDigit(value.charAt(0));
  }

  private static int validateGraphProperties(
      ChainPlanGraph graph, List<ValidationIssue> issues, int counter) {
    if (graph == null || graph.nodes() == null) {
      return counter;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.properties() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (property == null || property.key() == null || property.key().isBlank()) {
          continue;
        }
        if (!CAMEL_CASE.matcher(property.key()).matches()) {
          counter =
              warning(
                  issues,
                  counter,
                  "Property key should be camelCase: '" + property.key() + "'",
                  "Rename plan property keys to camelCase");
        }
      }
    }
    return counter;
  }

  private static int warning(
      List<ValidationIssue> issues, int counter, String message, String suggestedFix) {
    issues.add(
        new ValidationIssue(
            "quality-" + counter,
            ValidationSeverity.WARNING,
            message,
            OWNER,
            List.of(),
            List.of(),
            suggestedFix));
    return counter + 1;
  }
}
