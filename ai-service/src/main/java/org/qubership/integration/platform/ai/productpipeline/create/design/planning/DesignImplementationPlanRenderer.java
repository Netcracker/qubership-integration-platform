package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Renders the user-facing {@link ImplementationPlan} from the exact planner report and typed
 * projection. Preserves every planner step in report order with identical {@code reportText}.
 */
public final class DesignImplementationPlanRenderer {

  public ImplementationPlan render(
      DesignPlanReport report, DesignExecutionPlan projection, NormalizedDesignFlow flow) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(flow, "flow");

    List<String> endpointFacts = new ArrayList<>();
    List<String> branchFacts = new ArrayList<>();
    List<String> scriptOutcomes = new ArrayList<>();
    List<String> serviceBindings = new ArrayList<>();
    List<String> negativeConstraints = new ArrayList<>();
    List<String> skillOwnership = new ArrayList<>();
    List<String> sourceArtifactReferences = new ArrayList<>();
    List<String> dependencyProvenance = new ArrayList<>();

    StringBuilder body = new StringBuilder();
    body.append("# Implementation plan: ").append(flow.chainName()).append('\n');
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

    if (flow.trigger() != null) {
      String triggerFact =
          "Trigger "
              + flow.trigger().kind()
              + " via "
              + flow.trigger().sourceParticipantId()
              + (flow.trigger().interfaceName() == null
                  ? ""
                  : " interface " + flow.trigger().interfaceName());
      endpointFacts.add(triggerFact);
      body.append('\n').append("## Trigger").append('\n').append("- ").append(triggerFact).append('\n');
    }

    List<NormalizedDesignFlow.DataMapping> explicitMappings =
        flow.dataMappings().stream()
            .filter(mapping -> mapping.mode() == NormalizedDesignFlow.MappingMode.EXPLICIT)
            .toList();
    if (!explicitMappings.isEmpty()) {
      body.append('\n').append("## Approved mapping intents").append('\n');
      for (NormalizedDesignFlow.DataMapping mapping : explicitMappings) {
        String mappingFact =
            mapping.mappingId()
                + " ["
                + mapping.mode()
                + "] "
                + mapping.fromStepId()
                + " -> "
                + mapping.toStepId();
        scriptOutcomes.add(mappingFact);
        body.append("- ").append(mappingFact).append('\n');
        for (NormalizedDesignFlow.MappingRule rule : mapping.rules()) {
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
}
