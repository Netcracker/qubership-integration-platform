package org.qubership.integration.platform.ai.qipknowledge.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.ScriptBodyPromptRedaction;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirements;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirements.ElementRequirement;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirementsLoader;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Validates catalog materialization requirements from the addon overlay. */
@ApplicationScoped
public class MaterializationRequirementsValidator {

  private final MaterializationRequirementsLoader loader;

  @Inject
  public MaterializationRequirementsValidator(MaterializationRequirementsLoader loader) {
    this.loader = Objects.requireNonNull(loader, "loader");
  }

  public List<ValidationIssue> validate(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return List.of();
    }

    MaterializationRequirements requirements = loader.load();
    if (requirements.elementRequirements().isEmpty()) {
      return List.of();
    }

    List<ValidationIssue> issues = new ArrayList<>();
    int issueCounter = 1;
    for (ChainPlanNode node : graph.nodes()) {
      String elementType = normalizeType(node.type());
      if (elementType.isEmpty()) {
        continue;
      }
      ElementRequirement elementRequirement =
          requirements.elementRequirements().get(elementType);
      if (elementRequirement == null || elementRequirement.requiredProperties().isEmpty()) {
        continue;
      }
      for (String propertyKey : elementRequirement.requiredProperties()) {
        if (hasNonBlankProperty(node, propertyKey)) {
          continue;
        }
        issues.add(
            materializationBlocker(
                issueCounter++,
                node,
                elementType,
                propertyKey,
                elementRequirement));
      }
    }
    return List.copyOf(issues);
  }

  private static boolean hasNonBlankProperty(ChainPlanNode node, String propertyKey) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (!propertyKey.equals(property.key())) {
        continue;
      }
      if ("script".equals(propertyKey)) {
        return ScriptBodyPromptRedaction.isPresentScriptBody(property.value());
      }
      if (property.value() != null && !property.value().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private static ValidationIssue materializationBlocker(
      int issueCounter,
      ChainPlanNode node,
      String elementType,
      String propertyKey,
      ElementRequirement elementRequirement) {
    String owner =
        elementRequirement.ownerGenerator() != null
                && !elementRequirement.ownerGenerator().isBlank()
            ? elementRequirement.ownerGenerator()
            : CompilerPlanValidator.OWNER_CAPABILITY_ID;
    String example = elementRequirement.examples().get(propertyKey);
    String message =
        "Node '"
            + node.nodeId()
            + "' ("
            + elementType
            + ") is missing required materialization property '"
            + propertyKey
            + "'";
    String suggestedFix =
        example != null && !example.isBlank()
            ? "Add "
                + propertyKey
                + " through "
                + owner
                + ", for example '"
                + example
                + "'"
            : "Add " + propertyKey + " through " + owner;
    return new ValidationIssue(
        "materialization-" + issueCounter,
        ValidationSeverity.BLOCKER,
        message,
        owner,
        List.of(node.nodeId()),
        List.of(),
        suggestedFix);
  }

  private static String normalizeType(String type) {
    return type != null ? type.trim() : "";
  }
}
