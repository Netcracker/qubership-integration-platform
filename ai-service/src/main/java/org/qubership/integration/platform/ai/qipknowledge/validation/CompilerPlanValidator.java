package org.qubership.integration.platform.ai.qipknowledge.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

/** Deterministic compiler plan validator for a small VR-* rule subset. */
@ApplicationScoped
public class CompilerPlanValidator {

  static final String OWNER_CAPABILITY_ID = InternalPipelineSkills.PLAN_VALIDATOR;

  private static final String VR_L_001_FIX =
      "Replace with v2 equivalent: try-catch-finally-2, try-2, catch-2, finally-2, loop-2,"
          + " split-async-2, async-split-element-2, split-2, split-element-2, main-split-element-2";

  private static final String VR_G_001_FIX =
      "Add at least one trigger element (http-trigger, chain-trigger-2, async-api-trigger,"
          + " kafka-trigger-2, quartz-scheduler, or rabbitmq-trigger-2).";

  private static final String VR_G_004_FIX =
      "Remove orphan elements or add dependency edges from reachable elements.";

  private final ChainPlanGraphValidator structuralValidator;
  private final SchemaResourceLoader schemaResourceLoader;
  private final ChainElementCatalog elementCatalog;
  private final MaterializationRequirementsValidator materializationRequirementsValidator;

  @Inject
  public CompilerPlanValidator(
      ChainPlanGraphValidator structuralValidator,
      SchemaResourceLoader schemaResourceLoader,
      ChainElementCatalog elementCatalog,
      MaterializationRequirementsValidator materializationRequirementsValidator) {
    this.structuralValidator = Objects.requireNonNull(structuralValidator, "structuralValidator");
    this.schemaResourceLoader =
        Objects.requireNonNull(schemaResourceLoader, "schemaResourceLoader");
    this.elementCatalog = Objects.requireNonNull(elementCatalog, "elementCatalog");
    this.materializationRequirementsValidator =
        Objects.requireNonNull(
            materializationRequirementsValidator, "materializationRequirementsValidator");
  }

  public ValidationResult validate(PlanGraphValidationInput input) {
    Objects.requireNonNull(input, "input");

    List<ValidationIssue> issues = new ArrayList<>();
    int issueCounter = 1;

    ChainPlanGraph graph = input.graph();
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      issues.add(
          blocker(
              issueCounter++,
              "graph must contain at least one node",
              "Provide a ChainPlanGraph with nodes",
              List.of()));
      return result(issues);
    }

    for (String structuralError : structuralValidator.validate(graph)) {
      issues.add(
          blocker(
              issueCounter++,
              structuralError,
              "Fix the structural graph issue",
              List.of()));
    }

    issueCounter = checkDeprecatedTypes(graph, issues, issueCounter);
    issueCounter = checkKnownElementTypes(graph, issues, issueCounter);

    Set<String> triggerNodeIds = new HashSet<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (isTriggerType(node.type())) {
        triggerNodeIds.add(node.nodeId());
      }
    }
    if (triggerNodeIds.isEmpty()) {
      issues.add(
          vrBlocker(
              issueCounter++,
              "VR-G-001",
              "Chain has no trigger element",
              List.of(),
              VR_G_001_FIX));
    } else {
      issueCounter = checkReachability(graph, triggerNodeIds, issues, issueCounter);
    }

    for (ValidationIssue materializationIssue :
        materializationRequirementsValidator.validate(graph)) {
      issues.add(
          new ValidationIssue(
              "validation-" + issueCounter++,
              materializationIssue.severity(),
              materializationIssue.message(),
              materializationIssue.ownerCapabilityId(),
              materializationIssue.affectedNodeIds(),
              materializationIssue.ruleRefs(),
              materializationIssue.suggestedFix()));
    }

    return result(issues);
  }

  private int checkDeprecatedTypes(
      ChainPlanGraph graph, List<ValidationIssue> issues, int issueCounter) {
    for (ChainPlanNode node : graph.nodes()) {
      if (elementCatalog.isDeprecated(node.type())) {
        issues.add(
            vrBlocker(
                issueCounter++,
                "VR-L-001",
                "Deprecated element type '" + node.type() + "' on node '" + node.nodeId() + "'",
                List.of(node.nodeId()),
                VR_L_001_FIX));
      }
    }
    return issueCounter;
  }

  private int checkKnownElementTypes(
      ChainPlanGraph graph, List<ValidationIssue> issues, int issueCounter) {
    for (ChainPlanNode node : graph.nodes()) {
      String type = node.type() != null ? node.type().trim() : "";
      if (type.isEmpty()) {
        continue;
      }
      if (type.contains("+") || type.contains("/") || type.contains("\\")) {
        issues.add(
            blocker(
                issueCounter++,
                "Invalid element type '" + type + "' on node '" + node.nodeId() + "'",
                "Use an exact catalog element type (for example script, service-call, http-trigger)",
                List.of(node.nodeId())));
        continue;
      }
      if (!isKnownElementType(type)) {
        issues.add(
            blocker(
                issueCounter++,
                "Unknown element type '" + type + "' on node '" + node.nodeId() + "'",
                "Call describeElementPatchSchema for the intended type and use that exact type string",
                List.of(node.nodeId())));
      }
    }
    return issueCounter;
  }

  private boolean isKnownElementType(String type) {
    return schemaResourceLoader.existsElementSchema(type);
  }

  private int checkReachability(
      ChainPlanGraph graph,
      Set<String> triggerNodeIds,
      List<ValidationIssue> issues,
      int issueCounter) {
    Set<String> reachable = computeReachable(graph, triggerNodeIds);
    for (ChainPlanNode node : graph.nodes()) {
      if (isTriggerType(node.type())) {
        continue;
      }
      if (!reachable.contains(node.nodeId())) {
        issues.add(
            vrBlocker(
                issueCounter++,
                "VR-G-004",
                "Node '" + node.nodeId() + "' is not reachable from any trigger",
                List.of(node.nodeId()),
                VR_G_004_FIX));
      }
    }
    return issueCounter;
  }

  private static Set<String> computeReachable(ChainPlanGraph graph, Set<String> triggerNodeIds) {
    Set<String> reachable = new HashSet<>(triggerNodeIds);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ChainPlanNode node : graph.nodes()) {
        if (node.parentNodeId() != null
            && reachable.contains(node.parentNodeId())
            && reachable.add(node.nodeId())) {
          changed = true;
        }
      }
      if (graph.edges() != null) {
        for (ChainPlanEdge edge : graph.edges()) {
          if (reachable.contains(edge.fromNodeId()) && reachable.add(edge.toNodeId())) {
            changed = true;
          }
        }
      }
    }
    return reachable;
  }

  private static boolean isTriggerType(String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    return ChainElementFamilies.isTrigger(type);
  }

  private ValidationIssue vrBlocker(
      int issueCounter,
      String ruleId,
      String message,
      List<String> affectedNodeIds,
      String suggestedFix) {
    return new ValidationIssue(
        "validation-" + issueCounter,
        ValidationSeverity.BLOCKER,
        message,
        OWNER_CAPABILITY_ID,
        List.copyOf(affectedNodeIds),
        List.of(QipKnowledgeCitation.declaredRule(ruleId, QipKnowledgeRefType.VALIDATION_RULE)),
        suggestedFix);
  }

  private static ValidationIssue blocker(
      int issueCounter, String message, String suggestedFix, List<String> affectedNodeIds) {
    return new ValidationIssue(
        "validation-" + issueCounter,
        ValidationSeverity.BLOCKER,
        message,
        OWNER_CAPABILITY_ID,
        List.copyOf(affectedNodeIds),
        List.of(),
        suggestedFix);
  }

  private static ValidationResult result(List<ValidationIssue> issues) {
    long blockers = issues.stream().filter(i -> i.severity() == ValidationSeverity.BLOCKER).count();
    if (blockers == 0) {
      return new ValidationResult(true, List.copyOf(issues), "Plan validation passed");
    }
    return new ValidationResult(
        false, List.copyOf(issues), "Plan validation failed with " + blockers + " blocker(s)");
  }
}
