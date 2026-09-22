package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationEdge;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFacts;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationNode;

/**
 * Deterministic schema-version-2 implementation-plan renderer. Presenter wording is accepted only
 * when {@link #verifyCoverage} finds every structured fact in the stored text.
 */
public final class ImplementationPlanRenderer {

  private ImplementationPlanRenderer() {}

  public static ImplementationPlan render(
      PlanPresentationFacts facts,
      String sourceSkillId,
      String sourceSkillVersion,
      List<String> sourceArtifactReferences,
      List<String> dependencyProvenance) {
    Objects.requireNonNull(facts, "facts");
    List<String> endpoints = copy(facts.endpointFacts());
    List<String> branches = copy(facts.branchFacts());
    List<String> scripts = copy(facts.scriptOutcomes());
    List<String> bindings = copy(facts.serviceBindings());
    List<String> negatives = copy(facts.negativeConstraints());
    List<String> skills = copy(facts.skillOwnership());
    List<String> sources = copy(sourceArtifactReferences);
    List<String> provenance = copy(dependencyProvenance);

    String planText =
        renderMarkdown(
            facts,
            endpoints,
            branches,
            scripts,
            bindings,
            negatives,
            skills,
            sources,
            provenance);
    return ImplementationPlan.schemaVersion2(
        planText,
        sourceSkillId,
        sourceSkillVersion,
        endpoints,
        branches,
        scripts,
        bindings,
        negatives,
        skills,
        sources,
        provenance);
  }

  public static Optional<String> verifyCoverage(ImplementationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (plan.schemaVersion() < ImplementationPlan.SCHEMA_VERSION_2) {
      return Optional.empty();
    }
    String text = plan.planText() == null ? "" : plan.planText();
    List<String> missing = new ArrayList<>();
    for (String fact : plan.allStructuredFacts()) {
      if (!text.contains(fact)) {
        missing.add(fact);
      }
    }
    if (missing.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of("implementation plan text missing structured facts: " + String.join("; ", missing));
  }

  public static boolean acceptPresenterText(ImplementationPlan structured, String presenterText) {
    if (structured == null || presenterText == null || presenterText.isBlank()) {
      return false;
    }
    ImplementationPlan candidate =
        ImplementationPlan.schemaVersion2(
            presenterText,
            structured.sourceSkillId(),
            structured.sourceSkillVersion(),
            structured.endpointFacts(),
            structured.branchFacts(),
            structured.scriptOutcomes(),
            structured.serviceBindings(),
            structured.negativeConstraints(),
            structured.skillOwnership(),
            structured.sourceArtifactReferences(),
            structured.dependencyProvenance());
    return verifyCoverage(candidate).isEmpty();
  }

  private static String renderMarkdown(
      PlanPresentationFacts facts,
      List<String> endpoints,
      List<String> branches,
      List<String> scripts,
      List<String> bindings,
      List<String> negatives,
      List<String> skills,
      List<String> sources,
      List<String> provenance) {
    StringBuilder body = new StringBuilder();
    String chain =
        facts.chainName() == null || facts.chainName().isBlank() ? "chain" : facts.chainName();
    body.append("# Implementation plan: ").append(chain).append('\n');
    body.append('\n');
    body.append("Schema version: ").append(ImplementationPlan.SCHEMA_VERSION_2).append('\n');
    if (facts.selectedPatternId() != null && !facts.selectedPatternId().isBlank()) {
      body.append("Pattern: ").append(facts.selectedPatternId()).append('\n');
    }
    appendPlannedStructure(body, facts);
    appendSection(body, "Endpoints", endpoints);
    appendSection(body, "Branches", branches);
    appendSection(body, "Script outcomes", scripts);
    appendSection(body, "Service bindings", bindings);
    appendSection(body, "Negative constraints", negatives);
    appendSection(body, "Skill ownership", skills);
    appendSection(body, "Source artifacts", sources);
    appendSection(body, "Dependency provenance", provenance);
    if (facts.validationPassed() != null) {
      body.append('\n').append("Validation: ").append(facts.validationPassed() ? "passed" : "failed");
    }
    return body.toString().trim();
  }

  private static void appendSection(StringBuilder body, String title, List<String> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    body.append('\n').append("## ").append(title).append('\n');
    for (String fact : facts) {
      body.append("- ").append(fact).append('\n');
    }
  }

  private static void appendPlannedStructure(StringBuilder body, PlanPresentationFacts facts) {
    if (facts.coreFlowNodes().isEmpty() && facts.compilerAdditions().isEmpty()) {
      return;
    }
    body.append("\n## Planned chain structure\n");
    if (!facts.coreFlowNodes().isEmpty()) {
      body.append("\n### Core elements\n\n");
      for (PlanPresentationNode node : facts.coreFlowNodes()) {
        body.append("- ").append(node.nodeId()).append(": ").append(node.type());
        if (node.label() != null && !node.label().isBlank()) {
          body.append(" (").append(node.label()).append(')');
        }
        if (node.parentNodeId() != null && !node.parentNodeId().isBlank()) {
          body.append(" inside ").append(node.parentNodeId());
        }
        body.append('\n');
      }
    }
    if (!facts.coreFlowEdges().isEmpty()) {
      body.append("\n### Connections\n\n");
      for (PlanPresentationEdge edge : facts.coreFlowEdges()) {
        body.append("- ").append(edge.fromNodeId()).append(" -> ").append(edge.toNodeId())
            .append('\n');
      }
    }
    if (!facts.compilerAdditions().isEmpty()) {
      body.append("\n### Compiler additions\n\n");
      facts.compilerAdditions().forEach(addition ->
          body.append("- ").append(addition.description()).append('\n'));
    }
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
