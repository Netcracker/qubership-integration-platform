package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;

/**
 * Validates required materialization properties from {@link CompilerContract.ElementContract}.
 * Addon overlay supplies owner and examples only.
 */
@ApplicationScoped
public class MaterializationRequirementsValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
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
      if ("service-call".equals(elementType) && node.properties() != null) {
        for (PlanProperty property : node.properties()) {
          if (("before".equals(property.key()) || "after".equals(property.key()))
              && hasMissingHookBody(property.value())) {
            issues.add(materializationBlocker(issueCounter++, node, elementType,
                property.key() + ".script", overlayRequirement));
          }
        }
      }
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

  private static boolean hasMissingHookBody(String value) {
    try {
      JsonNode hooks = MAPPER.readTree(value);
      if (hooks == null) {
        return false;
      }
      for (JsonNode hook : hooks.isArray() ? hooks : List.of(hooks)) {
        if ("script".equals(hook.path("type").asText())
            && !ScriptBodyPromptRedaction.isPresentScriptBody(hook.path("script").asText(null))) {
          return true;
        }
      }
      return false;
    } catch (JsonProcessingException | IllegalArgumentException error) {
      return true;
    }
  }

  public static List<ValidationIssue> validateRequestedHooks(
      ChainPlanGraph graph, List<RequirementFact> facts) {
    List<ValidationIssue> issues = new ArrayList<>();
    for (var fact : facts) {
      var intent = fact.elementIntent();
      if (intent == null || fact.polarity() != RequirementFactPolarity.POSITIVE
          || intent.placement() != Placement.BEFORE_HOOK && intent.placement() != Placement.AFTER_HOOK) {
        continue;
      }
      String key = intent.placement() == Placement.BEFORE_HOOK ? "before" : "after";
      var owner = graph.nodes().stream().filter(node -> node.nodeId().equals(intent.ownerInteractionId()))
          .findFirst().orElse(null);
      boolean implemented = owner != null && owner.properties().stream()
          .filter(property -> key.equals(property.key()))
          .anyMatch(property -> hasExecutableScriptHook(property.value(), intent));
      if (!implemented) {
        issues.add(new ValidationIssue("requested-hook-" + fact.sourceFactId(), ValidationSeverity.BLOCKER,
            "Node '" + intent.ownerInteractionId() + "' is missing the approved inline " + key
                + " script (sourceFactId=" + fact.sourceFactId() + ").",
            "cip-service-call-generator", List.of(intent.ownerInteractionId()), List.of(),
            "Implement this fact in the owner's native " + key + " hook with a complete Groovy script and its approved values."
                + " A standalone Script does not satisfy this hook.",
            "MISSING_REQUIRED_PROPERTY", intent.ownerInteractionId(), "/properties/" + key + ".script"));
      }
    }
    return List.copyOf(issues);
  }

  private static boolean hasExecutableScriptHook(String value) {
    return hasExecutableScriptHook(value, null);
  }

  private static boolean hasExecutableScriptHook(String value,
      org.qubership.integration.platform.ai.plan.RequirementElementIntent intent) {
    try {
      JsonNode hooks = MAPPER.readTree(value);
      if (hooks != null) {
        for (JsonNode hook : hooks.isArray() ? hooks : List.of(hooks)) {
          if ("script".equals(hook.path("type").asText())
              && ScriptBodyPromptRedaction.isPresentScriptBody(hook.path("script").asText(null))
              && (intent == null || intent.exactValues().stream().allMatch(hook.path("script").asText()::contains)
                  && intent.requestedProperties().stream().allMatch(property ->
                      Objects.equals(property.value(), hook.path(property.key()).asText(null))))) {
            return true;
          }
        }
      }
    } catch (JsonProcessingException | IllegalArgumentException error) {
      return false;
    }
    return false;
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
        "before.script".equals(propertyKey) || "after.script".equals(propertyKey)
            ? "cip-service-call-generator"
            : overlayRequirement != null
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
        "before.script".equals(propertyKey) || "after.script".equals(propertyKey)
            ? "Include the complete Groovy script body inside the native before/after hook through " + owner
            : example != null && !example.isBlank()
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
        suggestedFix,
        "MISSING_REQUIRED_PROPERTY",
        node.nodeId(),
        "/properties/" + propertyKey.replace("~", "~0").replace("/", "~1"));
  }

  private static String normalizeType(String type) {
    return type != null ? type.trim() : "";
  }
}
