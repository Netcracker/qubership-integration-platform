package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Builds the requirement brief seeded into compiler DAG execution for design-execution.
 *
 * <p>Prefers the approved analysis brief when present, then enriches it with normalized-flow
 * trigger/step facts and resolved catalog binding ids so generator skills see design intent
 * without inventing values.
 */
public final class DesignExecutionBriefFactory {

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

  private static RequirementBrief enrich(
      RequirementBrief brief, NormalizedDesignFlow flow, List<CatalogBindingResolution> bindings) {
    LinkedHashSet<String> inputs = new LinkedHashSet<>(brief.inputs());
    LinkedHashSet<String> constraints = new LinkedHashSet<>(brief.constraints());
    List<RequirementFact> facts = new ArrayList<>(brief.facts());

    appendFlowSignals(flow, inputs, constraints, facts);
    appendBindingInputs(bindings, inputs);

    String draftText =
        firstNonBlank(brief.approvedDraftText(), formatBindingBlock(bindings), formatFlowSeed(flow));
    return new RequirementBrief(
        firstNonBlank(brief.goal(), flow.chainName()),
        List.copyOf(inputs),
        List.copyOf(constraints),
        brief.assumptions(),
        brief.citations(),
        firstNonBlank(brief.summary(), flow.description()),
        brief.approvedDraftReference(),
        draftText,
        List.copyOf(facts),
        flow.dataMappings().isEmpty() ? brief.dataMappings() : dataMappingsFrom(flow));
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
    return new RequirementBrief(
        flow.chainName(),
        List.copyOf(inputs),
        List.copyOf(constraints),
        flow.assumptions(),
        List.of(),
        summary,
        null,
        firstNonBlank(formatBindingBlock(bindings), formatFlowSeed(flow)),
        List.copyOf(facts),
        dataMappingsFrom(flow));
  }

  private static List<RequirementDataMapping> dataMappingsFrom(NormalizedDesignFlow flow) {
    return flow.dataMappings().stream()
        .map(
            mapping ->
                new RequirementDataMapping(
                    mapping.mappingId(),
                    RequirementDataMapping.Stage.valueOf(mapping.stage().name()),
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
        String endpoint = method == null ? path : method + " " + path;
        inputs.add(endpoint);
        facts.add(
            new RequirementFact(
                "design-flow-trigger",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "",
                endpoint));
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
        facts.add(
            new RequirementFact(
                "design-flow-" + step.stepId(),
                RequirementFactPolarity.POSITIVE,
                kind,
                "",
                label));
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
    StringBuilder body = new StringBuilder("Resolved catalog binding:\n");
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }
}
