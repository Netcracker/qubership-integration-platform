package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Renders the user-facing {@link ImplementationPlan} from the exact planner report and typed
 * projection. Preserves every planner step in report order with identical {@code reportText}.
 */
public final class DesignImplementationPlanRenderer {

  public ImplementationPlan render(
      DesignPlanReport report, DesignExecutionPlan projection, ChainSemanticRevision revision) {
    return render(report, projection, revision, null);
  }

  public ImplementationPlan render(
      DesignPlanReport report,
      DesignExecutionPlan projection,
      ChainSemanticRevision revision,
      RequirementBrief brief) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(revision, "revision");

    List<String> endpointFacts = new ArrayList<>();
    List<String> branchFacts = new ArrayList<>();
    List<String> scriptOutcomes = new ArrayList<>();
    List<String> serviceBindings = new ArrayList<>();
    List<String> negativeConstraints = new ArrayList<>();
    List<String> skillOwnership = new ArrayList<>();
    List<String> sourceArtifactReferences = new ArrayList<>();
    List<String> dependencyProvenance = new ArrayList<>();

    StringBuilder body = new StringBuilder();
    body.append("# Implementation plan: ").append(revision.chainIdentity()).append('\n');
    body.append('\n');
    body.append("Schema version: ").append(ImplementationPlan.SCHEMA_VERSION_2).append('\n');
    body.append("Binding resolution policy: ")
        .append(projection.bindingResolutionPolicy())
        .append('\n');
    body.append("Design input: ").append(projection.designInputRef()).append('\n');
    body.append("Design input hash: ").append(projection.designInputHash()).append('\n');
    body.append("Source report hash: ").append(projection.sourceReportHash()).append('\n');
    body.append("Compiler catalog hash: ").append(projection.compilerCatalogHash()).append('\n');
    body.append('\n');
    appendPlannedStructure(body, revision);
    body.append('\n');
    body.append("## Planner steps").append('\n');

    for (DesignExecutionPlan.Step step : projection.steps()) {
      body.append(step.reportOrdinal()).append(". ").append(step.reportText()).append('\n');
      body.append("   - stepId: ").append(step.stepId()).append('\n');
      if (!step.dependsOn().isEmpty()) {
        String depends = String.join(", ", step.dependsOn());
        body.append("   - dependsOn: ").append(depends).append('\n');
        dependencyProvenance.add(step.stepId() + " dependsOn " + depends);
      }
      if (!step.owningSkillIds().isEmpty()) {
        String owners = String.join(", ", step.owningSkillIds());
        body.append("   - owningSkills: ").append(owners).append('\n');
        skillOwnership.add(step.stepId() + " owned by " + owners);
      }
      if (!step.toolOperationRefs().isEmpty()) {
        body.append("   - toolOperations: ")
            .append(String.join(", ", step.toolOperationRefs()))
            .append('\n');
      }
      if (!step.participantRefs().isEmpty()) {
        body.append("   - participants: ")
            .append(String.join(", ", step.participantRefs()))
            .append('\n');
      }
      if (!step.operationQueryRefs().isEmpty()) {
        String queries = String.join(", ", step.operationQueryRefs());
        body.append("   - operationQueries: ").append(queries).append('\n');
        serviceBindings.add(step.stepId() + " queries " + queries);
      }
    }

    if (!revision.entryPoints().isEmpty()) {
      String triggerFact = "Trigger " + revision.chainIdentity();
      String label = revision.entryPoints().getFirst().presentation().label();
      if (label != null && !label.isBlank()) {
        triggerFact = triggerFact + " interface " + label;
      }
      endpointFacts.add(triggerFact);
      body.append('\n').append("## Trigger").append('\n').append("- ").append(triggerFact).append('\n');
    }

    List<String> approvedRequirementFacts = new ArrayList<>();
    if (brief != null) {
      for (var fact : brief.facts()) {
        if (fact == null
            || fact.polarity() != RequirementFactPolarity.POSITIVE
            || fact.text().isBlank()) {
          continue;
        }
        boolean endpoint =
            fact.kind() == RequirementFactKind.ENDPOINT
                || (!fact.httpMethod().isBlank() && !fact.path().isBlank());
        if (endpoint && !endpointFacts.contains(fact.text())) {
          endpointFacts.add(fact.text());
          approvedRequirementFacts.add(fact.text());
        } else if (fact.kind() == RequirementFactKind.BEHAVIOR
            && !scriptOutcomes.contains(fact.text())) {
          scriptOutcomes.add(fact.text());
          approvedRequirementFacts.add(fact.text());
        }
      }
      for (CatalogBindingHint binding : brief.catalogBindings()) {
        String bindingFact = serviceBindingFact(binding);
        if (!serviceBindings.contains(bindingFact)) {
          serviceBindings.add(bindingFact);
        }
      }
    }
    if (!approvedRequirementFacts.isEmpty()) {
      body.append('\n').append("## Approved requirement facts").append('\n');
      for (String fact : approvedRequirementFacts) {
        body.append("- ").append(fact).append('\n');
      }
    }

    if (!serviceBindings.isEmpty()) {
      body.append('\n').append("## Service bindings").append('\n');
      for (String binding : serviceBindings) {
        body.append("- ").append(binding).append('\n');
      }
    }

    if (!revision.mappingBodies(brief).isEmpty()) {
      body.append('\n').append("## Approved mapping intents").append('\n');
      for (MappingIntent mapping : revision.mappingBodies(brief)) {
        String mappingFact =
            mapping.mappingIntentId()
                + " "
                + mapping.sourceRef()
                + " -> "
                + mapping.targetRef();
        scriptOutcomes.add(mappingFact);
        body.append("- ").append(mappingFact).append('\n');
        for (var rule : mapping.rules()) {
          body.append("  - ")
              .append(rule.sourcePath())
              .append(" -> ")
              .append(rule.targetPath());
          if (rule.expression() != null) {
            body.append(" | expression: ").append(rule.expression());
          }
          body.append('\n');
        }
      }
    }

    sourceArtifactReferences.add("design-plan-report");
    sourceArtifactReferences.add(projection.designInputRef());
    body.append('\n').append("## Structural findings").append('\n');
    body.append("- Planner steps: ").append(projection.steps().size()).append('\n');
    body.append("- Exact source report preserved as design-plan-report").append('\n');
    body.append("- No chain, service, specification, or graph artifact claimed before approval")
        .append('\n');

    String planText = body.toString().trim();
    // Keep every reportText literally present for coverage checks.
    for (DesignExecutionPlan.Step step : projection.steps()) {
      if (!planText.contains(step.reportText())) {
        throw new IllegalStateException(
            "implementation plan omitted planner reportText for " + step.stepId());
      }
    }

    return ImplementationPlan.schemaVersion2(
        planText,
        CipDesignPlannerAdapter.SKILL_ID,
        "1",
        endpointFacts,
        branchFacts,
        scriptOutcomes,
        serviceBindings,
        negativeConstraints,
        skillOwnership,
        sourceArtifactReferences,
        dependencyProvenance);
  }

  private static String serviceBindingFact(CatalogBindingHint binding) {
    return binding.interactionId()
        + " -> "
        + binding.operationQuery()
        + " [systemId="
        + binding.systemId()
        + ", specificationGroupId="
        + binding.specificationGroupId()
        + ", specificationId="
        + binding.specificationId()
        + ", integrationOperationId="
        + binding.integrationOperationId()
        + "]";
  }

  private static void appendPlannedStructure(StringBuilder body, ChainSemanticRevision revision) {
    body.append("## Planned chain structure\n\n");
    body.append("### Entry points\n\n");
    for (SemanticEntryPoint entry :
        revision.entryPoints().stream()
            .sorted(Comparator.comparingInt(SemanticEntryPoint::order))
            .toList()) {
      String label = entry.presentation().label();
      body.append("- ")
          .append(label == null || label.isBlank() ? entry.entryPointId() : label)
          .append(": ")
          .append(entry.triggerNodeId())
          .append(" -> ")
          .append(entry.initialTargetNodeId())
          .append('\n');
    }
    body.append("\n### Elements\n\n");
    for (SemanticNode node : revision.nodes()) {
      body.append("- ").append(node.nodeId()).append(": ");
      switch (node) {
        case SemanticNode.Trigger trigger ->
            body.append("trigger (").append(trigger.capabilityKey()).append(')');
        case SemanticNode.ServiceCall call ->
            body.append("service call (").append(call.operation()).append(')');
        case SemanticNode.Operation operation -> body.append(operation.elementType());
      }
      body.append('\n');
    }
    body.append("\n### Connections\n\n");
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      body.append("- ").append(edge.sourceNodeId()).append(" -> ").append(edge.targetNodeId());
      if (edge.route() != null) {
        body.append(" (")
            .append(edge.route().kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (edge.route() instanceof SemanticRoute.ConditionBranch branch) {
          body.append(": ").append(branch.branchId());
        } else if (edge.route() instanceof SemanticRoute.SplitBranch branch) {
          body.append(": ").append(branch.branchId());
        } else if (edge.route() instanceof SemanticRoute.CatchPath catchPath) {
          body.append(": ").append(catchPath.handlerId());
        }
        body.append(')');
      }
      body.append('\n');
    }
    if (!revision.regions().isEmpty()) {
      body.append("\n### Regions\n\n");
      revision.regions().forEach(region ->
          body.append("- ").append(region.regionId()).append(": ")
              .append(region.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '))
              .append('\n'));
    }
    if (!revision.containment().isEmpty()) {
      body.append("\n### Containment\n\n");
      revision.containment().forEach(relation ->
          body.append("- ").append(relation.parentNodeId()).append(" contains ")
              .append(relation.childNodeId()).append(" (").append(relation.role()).append(")\n"));
    }
  }
}
