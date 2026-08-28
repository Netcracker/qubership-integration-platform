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
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/**
 * Validates required materialization properties from {@link CompilerContract.ElementContract}.
 * Addon overlay supplies owner and examples only.
 */
@ApplicationScoped
public class MaterializationRequirementsValidator {

  private final MaterializationRequirementsLoader loader;
  private final CompilerContractRepository contractRepository;

  @Inject
  public MaterializationRequirementsValidator(
      MaterializationRequirementsLoader loader, CompilerContractRepository contractRepository) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
  }

  public MaterializationRequirementsValidator(MaterializationRequirementsLoader loader) {
    this(loader, new ClasspathCompilerContractRepository());
  }

  public List<ValidationIssue> validate(ChainPlanGraph graph) {
    CompilerContract contract = contractRepository.require(CompilerContract.V1);
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      throw new IllegalStateException("Chain plan graph is required for materialization validation");
    }

    MaterializationRequirements overlay = loader.load();
    List<ValidationIssue> issues = new ArrayList<>();
    int issueCounter = 1;
    for (ChainPlanNode node : graph.nodes()) {
      String elementType = normalizeType(node.type());
      if (elementType.isEmpty()) {
        continue;
      }
      ElementContract element = contract.elements().get(elementType);
      if (element == null || element.requiredProperties().isEmpty()) {
        continue;
      }
      ElementRequirement overlayRequirement = overlay.elementRequirements().get(elementType);
      for (String propertyKey : element.requiredProperties()) {
        if (hasNonBlankProperty(node, propertyKey)) {
          continue;
        }
        issues.add(
            materializationBlocker(
                issueCounter++,
                node,
                elementType,
                propertyKey,
                overlayRequirement));
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
      ElementRequirement overlayRequirement) {
    String owner =
        overlayRequirement != null
                && overlayRequirement.ownerGenerator() != null
                && !overlayRequirement.ownerGenerator().isBlank()
            ? overlayRequirement.ownerGenerator()
            : CompilerPlanValidator.OWNER_CAPABILITY_ID;
    String example =
        overlayRequirement != null ? overlayRequirement.examples().get(propertyKey) : null;
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
