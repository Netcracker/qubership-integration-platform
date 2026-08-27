package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Builds the requirement brief seeded into compiler DAG execution for design-execution.
 *
 * <p>Prefers the approved analysis brief when present, then enriches it with normalized-flow
 * trigger/step facts and resolved catalog binding ids so generator skills see design intent
 * without inventing values. On a repair turn, the halt evidence and the prior chain-plan graph
 * fold into the same brief text rather than a second path the generator skills would need to know
 * about.
 */
public final class DesignExecutionBriefFactory {

  private static final Set<String> HTTP_METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  private DesignExecutionBriefFactory() {}

  public static RequirementBrief build(
      RequirementBrief storedBrief,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings) {
    Objects.requireNonNull(flow, "flow");
    List<CatalogBindingResolution> resolved =
        bindings == null ? List.of() : List.copyOf(bindings);
    if (storedBrief != null) {
      return enrich(storedBrief, flow, resolved);
    }
    return fromFlow(flow, resolved);
  }

  /**
   * Same brief, plus the halt evidence and the chain-plan graph the failing attempt produced.
   * {@code repairEvidence} and {@code priorGraph} are null on a first turn, in which case this
   * returns exactly what {@link #build(RequirementBrief, NormalizedDesignFlow, List)} does — the
   * compiler DAG reads the seed text off {@code approvedDraftText}, so the halt findings and the
   * prior graph go there rather than through a separate path the generator skills never see.
   */
  public static RequirementBrief build(
      RequirementBrief storedBrief,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph) {
    RequirementBrief brief = build(storedBrief, flow, bindings);
    if (repairEvidence == null || !repairEvidence.hasEvidence()) {
      return brief;
    }
    return brief.withApprovedDraftText(
        withRepairEvidence(brief.approvedDraftText(), repairEvidence, priorGraph));
  }

  private static String withRepairEvidence(
      String draftText, StageRepairEvidence repair, ChainPlanGraph priorGraph) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "Repair the previous design-execution attempt. Correct the step named below instead of "
            + "regenerating the whole chain.\n\n");
    sb.append("Halt repair evidence:\n");
    if (repair.outcomeClass() != null && !repair.outcomeClass().isBlank()) {
      sb.append("- outcomeClass: ").append(repair.outcomeClass().trim()).append('\n');
    }
    if (repair.failedStageId() != null && !repair.failedStageId().isBlank()) {
      sb.append("- failedStageId: ").append(repair.failedStageId().trim()).append('\n');
    }
    if (repair.findings() != null && !repair.findings().isBlank()) {
      sb.append("- validationFindings:\n").append(repair.findings().trim()).append('\n');
    }
    if (repair.errorEvidence() != null && !repair.errorEvidence().isBlank()) {
      sb.append("- errorEvidence:\n").append(repair.errorEvidence().trim()).append('\n');
    }
    if (repair.haltFollowUpText() != null && !repair.haltFollowUpText().isBlank()) {
      sb.append("- haltFollowUpText: ").append(repair.haltFollowUpText().trim()).append('\n');
    }
    if (priorGraph != null) {
      sb.append("\nPrior chain plan graph:\n").append(formatPriorGraph(priorGraph)).append('\n');
    }
    sb.append('\n').append(draftText == null ? "" : draftText);
    return sb.toString();
  }

  private static String formatPriorGraph(ChainPlanGraph graph) {
    StringBuilder body = new StringBuilder();
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      body.append("- ")
          .append(node.nodeId())
          .append(" [")
          .append(node.type())
          .append("] ")
          .append(node.label() == null ? "" : node.label())
          .append('\n');
    }
    return body.toString().trim();
  }

  private static RequirementBrief enrich(
      RequirementBrief brief, NormalizedDesignFlow flow, List<CatalogBindingResolution> bindings) {
    LinkedHashSet<String> inputs = new LinkedHashSet<>(brief.inputs());
    LinkedHashSet<String> constraints = new LinkedHashSet<>(brief.constraints());
    List<RequirementFact> facts = new ArrayList<>(brief.facts());

    appendFlowSignals(flow, inputs, constraints, facts);
    appendBindingInputs(bindings, inputs);

    String draftText =
        firstNonBlank(brief.approvedDraftText(), formatBindingBlock(bindings), formatFlowSeed(flow));
    return RequirementBriefProjector.project(
        new RequirementBrief(
            firstNonBlank(brief.goal(), flow.chainName()),
            List.copyOf(inputs),
            List.copyOf(constraints),
            brief.assumptions(),
            brief.citations(),
            firstNonBlank(brief.summary(), flow.description()),
            brief.approvedDraftReference(),
            draftText,
            List.copyOf(facts),
            flow.dataMappings().isEmpty() ? brief.dataMappings() : dataMappingsFrom(flow)));
  }

  private static RequirementBrief fromFlow(
      NormalizedDesignFlow flow, List<CatalogBindingResolution> bindings) {
    LinkedHashSet<String> inputs = new LinkedHashSet<>();
    LinkedHashSet<String> constraints = new LinkedHashSet<>(flow.constraints());
    List<RequirementFact> facts = new ArrayList<>();
    appendFlowSignals(flow, inputs, constraints, facts);
    appendBindingInputs(bindings, inputs);
    String summary =
        firstNonBlank(flow.description(), "Implement chain " + flow.chainName() + " from approved design flow");
    return RequirementBriefProjector.project(
        new RequirementBrief(
            flow.chainName(),
            List.copyOf(inputs),
            List.copyOf(constraints),
            flow.assumptions(),
            List.of(),
            summary,
            null,
            firstNonBlank(formatBindingBlock(bindings), formatFlowSeed(flow)),
            List.copyOf(facts),
            dataMappingsFrom(flow)));
  }

  private static List<RequirementDataMapping> dataMappingsFrom(NormalizedDesignFlow flow) {
    return flow.dataMappings().stream()
        .map(
            mapping ->
                new RequirementDataMapping(
                    mapping.mappingId(),
                    mapping.stage() == null
                        ? null
                        : RequirementDataMapping.Stage.valueOf(mapping.stage().name()),
                    mapping.fromStepId(),
                    mapping.toStepId(),
                    RequirementDataMapping.Mode.valueOf(mapping.mode().name()),
                    mapping.rules().stream()
                        .map(
                            rule ->
                                new RequirementDataMapping.Rule(
                                    rule.sourcePath(), rule.targetPath(), rule.expression()))
                        .toList(),
                    mapping.sourceFactIds()))
        .toList();
  }

  private static void appendFlowSignals(
      NormalizedDesignFlow flow,
      LinkedHashSet<String> inputs,
      LinkedHashSet<String> constraints,
      List<RequirementFact> facts) {
    NormalizedDesignFlow.Trigger trigger = flow.trigger();
    if (trigger != null) {
      String method = blankToNull(trigger.operationName());
      String path = blankToNull(trigger.endpointOrTopic());
      if (path != null) {
        boolean kafka = "kafka".equalsIgnoreCase(trigger.kind());
        String endpoint = method == null ? path : method + " " + path;
        inputs.add(endpoint);
        facts.add(
            new RequirementFact(
                "design-flow-trigger",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                kafka ? "kafka-trigger-2" : "http-trigger",
                endpoint,
                "",
                nullToEmpty(trigger.operationName()),
                kafka ? path : "",
                kafka ? "" : httpMethodFromTrigger(trigger),
                kafka ? "" : path));
      }
    }
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      if (step == null || step.kind() == null) {
        continue;
      }
      String label =
          firstNonBlank(step.operationQuery(), step.description(), step.kind());
      inputs.add(step.kind() + ": " + label);
      RequirementFactKind kind =
          "script".equalsIgnoreCase(step.kind())
              ? RequirementFactKind.BEHAVIOR
              : RequirementFactKind.SERVICE_CALL;
      if ("script".equalsIgnoreCase(step.kind()) || "service-call".equalsIgnoreCase(step.kind())) {
        boolean serviceCall = "service-call".equalsIgnoreCase(step.kind());
        facts.add(
            new RequirementFact(
                "design-flow-" + step.stepId(),
                RequirementFactPolarity.POSITIVE,
                kind,
                serviceCall ? "http-service-call" : "script",
                label,
                serviceCall ? participantDisplayName(flow, step.toParticipantId()) : "",
                serviceCall ? nullToEmpty(step.operationQuery()) : "",
                "",
                "",
                ""));
      }
    }
    for (String constraint : flow.constraints()) {
      if (constraint != null && !constraint.isBlank()) {
        constraints.add(constraint.trim());
      }
    }
  }

  private static void appendBindingInputs(
      List<CatalogBindingResolution> bindings, LinkedHashSet<String> inputs) {
    for (CatalogBindingResolution binding : bindings) {
      if (binding == null) {
        continue;
      }
      inputs.add(
          "Resolved catalog binding for "
              + binding.serviceCallStepId()
              + ": systemId="
              + binding.systemId()
              + ", specificationGroupId="
              + binding.specificationGroupId()
              + ", specificationId="
              + binding.specificationId()
              + ", integrationOperationId="
              + binding.integrationOperationId());
    }
  }

  private static String formatBindingBlock(List<CatalogBindingResolution> bindings) {
    if (bindings == null || bindings.isEmpty()) {
      return "";
    }
    StringBuilder body = new StringBuilder("Resolved catalog bindings:\n");
    for (CatalogBindingResolution binding : bindings) {
      if (binding == null) {
        continue;
      }
      body.append("- serviceCallStepId: ")
          .append(binding.serviceCallStepId())
          .append('\n')
          .append("- systemId: ")
          .append(binding.systemId())
          .append('\n')
          .append("- specificationGroupId: ")
          .append(binding.specificationGroupId())
          .append('\n')
          .append("- specificationId: ")
          .append(binding.specificationId())
          .append('\n')
          .append("- integrationOperationId: ")
          .append(binding.integrationOperationId())
          .append('\n');
    }
    return body.toString().trim();
  }

  private static String formatFlowSeed(NormalizedDesignFlow flow) {
    StringBuilder body = new StringBuilder();
    body.append("Chain: ").append(flow.chainName()).append('\n');
    if (flow.trigger() != null) {
      body.append("Trigger: ")
          .append(nullToEmpty(flow.trigger().operationName()))
          .append(' ')
          .append(nullToEmpty(flow.trigger().endpointOrTopic()))
          .append('\n');
    }
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      if (step == null) {
        continue;
      }
      body.append("Step ")
          .append(step.kind())
          .append(": ")
          .append(firstNonBlank(step.operationQuery(), step.description(), step.stepId()))
          .append('\n');
    }
    return body.toString().trim();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private static String httpMethodFromTrigger(NormalizedDesignFlow.Trigger trigger) {
    String operation = blankToNull(trigger.operationName());
    if (operation == null) {
      return "";
    }
    String upper = operation.toUpperCase(Locale.ROOT);
    return HTTP_METHODS.contains(upper) ? upper : "";
  }

  private static String participantDisplayName(NormalizedDesignFlow flow, String participantId) {
    if (participantId == null || participantId.isBlank()) {
      return "";
    }
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      if (participantId.equals(participant.participantId())) {
        return participant.displayName();
      }
    }
    return "";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }
}
