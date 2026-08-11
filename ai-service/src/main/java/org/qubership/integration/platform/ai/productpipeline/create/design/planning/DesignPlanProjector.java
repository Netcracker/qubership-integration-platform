package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.IdsDocumentParser;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Projects a planner report into a typed {@link DesignExecutionPlan} using the pinned compiler
 * catalog. Does not invent operation IDs, paths, or mapping rules.
 */
public final class DesignPlanProjector {

  public static final String BINDING_RESOLUTION_POLICY = "CATALOG_FIRST_V1";
  public static final String BINDING_RESOLUTION_POLICY_HASH =
      "ce160e1a62abc9d33b117338b10134e6cf8eeb5065ba6e5392a42b7f9cd17421";

  /** Upstream process skill; rewritten onto pinned Validation producers when absent from the DAG. */
  static final String CHAIN_VALIDATOR_SKILL_ID = "cip-chain-validator";

  private final CipDesignPlannerReportParser parser;

  public DesignPlanProjector() {
    this(new CipDesignPlannerReportParser());
  }

  public DesignPlanProjector(CipDesignPlannerReportParser parser) {
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  public DesignExecutionPlan project(
      DesignPlanReport report,
      NormalizedDesignFlow flow,
      ResolvedCompilerDag pinnedDag,
      String compilerCatalogHash,
      Map<String, String> pinnedSkillHashes,
      Map<String, String> pinnedAddonHashes) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(pinnedDag, "pinnedDag");
    Objects.requireNonNull(compilerCatalogHash, "compilerCatalogHash");

    ParsedPlannerReport parsed = parser.parse(report.markdown());
    Map<String, ResolvedCompilerNode> nodesBySkill = indexNodes(pinnedDag);
    // Upstream design-planner still names cip-chain-validator; the runtime skill catalog
    // decomposed that gate into the pinned Validation producers. Rewrite before catalog checks.
    parsed = rewriteChainValidatorAlias(parsed, nodesBySkill);
    validateUnknownSkills(parsed, nodesBySkill);
    validateNoCatalogCycles(nodesBySkill, selectedSkills(parsed));
    validateTriggerCoverage(parsed);
    validateScriptMappingCoverage(parsed, flow);
    validateParticipants(parsed, flow);

    List<DesignExecutionPlan.Step> steps = new ArrayList<>();
    Map<String, List<String>> stepsByOwner = new LinkedHashMap<>();
    String previousApiHubStepId = null;

    for (ParsedPlannerReport.Step parsedStep : parsed.steps()) {
      String stepId = stableStepId(parsedStep);
      List<String> dependsOn =
          deriveDependsOn(parsedStep, nodesBySkill, stepsByOwner, previousApiHubStepId);
      List<String> required = deriveRequiredArtifacts(parsedStep, nodesBySkill);
      List<String> produced = deriveProducedArtifacts(parsedStep, nodesBySkill);

      steps.add(
          new DesignExecutionPlan.Step(
              stepId,
              parsedStep.reportOrdinal(),
              parsedStep.reportText(),
              parsedStep.toPlanOwnerKind(),
              parsedStep.owningSkillIds(),
              parsedStep.toolOperationRefs(),
              mapParticipantRefs(parsedStep.participantRefs(), flow),
              parsedStep.operationQueryRefs(),
              dependsOn,
              required,
              produced));

      if (parsedStep.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL) {
        previousApiHubStepId = stepId;
        for (String tool : parsedStep.toolOperationRefs()) {
          stepsByOwner.computeIfAbsent(tool, key -> new ArrayList<>()).add(stepId);
        }
      } else if (!isConnectionFollowUp(parsedStep) && !isResolveBindingStep(parsedStep)) {
        for (String skillId : parsedStep.owningSkillIds()) {
          stepsByOwner.computeIfAbsent(skillId, key -> new ArrayList<>()).add(stepId);
        }
      }
    }

    String apiRelease = parsed.apiRelease() == null ? "UNSPECIFIED" : parsed.apiRelease();
    String sourceReportHash = sha256(report.markdown());
    String designInputHash = sha256(flow.flowId() + '|' + flow.chainName());

    return new DesignExecutionPlan(
        report.schemaVersion(),
        flow.flowId(),
        CipDesignPlannerAdapter.SKILL_ID,
        "normalized-design-flow/" + flow.flowId(),
        designInputHash,
        apiRelease,
        BINDING_RESOLUTION_POLICY,
        steps,
        "design-plan-report",
        sourceReportHash,
        pinnedSkillHashes == null ? Map.of() : pinnedSkillHashes,
        pinnedAddonHashes == null ? Map.of() : pinnedAddonHashes,
        compilerCatalogHash,
        BINDING_RESOLUTION_POLICY_HASH);
  }

  private static Map<String, ResolvedCompilerNode> indexNodes(ResolvedCompilerDag dag) {
    Map<String, ResolvedCompilerNode> nodes = new HashMap<>();
    for (ResolvedCompilerNode node : dag.nodes()) {
      nodes.put(node.skillId(), node);
    }
    return nodes;
  }

  private static Set<String> selectedSkills(ParsedPlannerReport parsed) {
    Set<String> skills = new LinkedHashSet<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      skills.addAll(step.owningSkillIds());
    }
    return skills;
  }

  /**
   * Maps the process-report skill {@code cip-chain-validator} onto the pinned Validation skill
   * closure when that id is absent from the DAG (canonical catalog uses five dimensional
   * validators that produce {@code COMPILER_VALIDATION_BUNDLE}).
   */
  static ParsedPlannerReport rewriteChainValidatorAlias(
      ParsedPlannerReport parsed, Map<String, ResolvedCompilerNode> nodesBySkill) {
    if (nodesBySkill.containsKey(CHAIN_VALIDATOR_SKILL_ID)) {
      return parsed;
    }
    List<String> validationSkills = pinnedValidationSkillIds(nodesBySkill);
    if (validationSkills.isEmpty()) {
      return parsed;
    }
    boolean rewritten = false;
    List<ParsedPlannerReport.Step> steps = new ArrayList<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (!step.owningSkillIds().contains(CHAIN_VALIDATOR_SKILL_ID)) {
        steps.add(step);
        continue;
      }
      LinkedHashSet<String> owners = new LinkedHashSet<>();
      for (String skillId : step.owningSkillIds()) {
        if (CHAIN_VALIDATOR_SKILL_ID.equals(skillId)) {
          owners.addAll(validationSkills);
        } else {
          owners.add(skillId);
        }
      }
      rewritten = true;
      steps.add(
          new ParsedPlannerReport.Step(
              step.reportOrdinal(),
              step.reportText(),
              step.ownerKind(),
              List.copyOf(owners),
              step.toolOperationRefs(),
              step.participantRefs(),
              step.operationQueryRefs()));
    }
    return rewritten ? new ParsedPlannerReport(steps, parsed.apiRelease()) : parsed;
  }

  private static List<String> pinnedValidationSkillIds(
      Map<String, ResolvedCompilerNode> nodesBySkill) {
    return nodesBySkill.values().stream()
        .filter(DesignPlanProjector::isPinnedValidationProducer)
        .map(ResolvedCompilerNode::skillId)
        .sorted()
        .toList();
  }

  private static boolean isPinnedValidationProducer(ResolvedCompilerNode node) {
    if (node == null || node.skillId() == null || node.skillId().isBlank()) {
      return false;
    }
    if (CHAIN_VALIDATOR_SKILL_ID.equals(node.skillId())) {
      return false;
    }
    if (node.produces() != null
        && node.produces().contains(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name())) {
      return true;
    }
    String phase = node.compilerPhase();
    return phase != null && phase.toLowerCase(Locale.ROOT).startsWith("validation");
  }

  private static void validateUnknownSkills(
      ParsedPlannerReport parsed, Map<String, ResolvedCompilerNode> nodesBySkill) {
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      for (String skillId : step.owningSkillIds()) {
        if (!nodesBySkill.containsKey(skillId)) {
          throw new PlannerContractException("unknown skill in planner report: " + skillId);
        }
      }
    }
  }

  private static void validateNoCatalogCycles(
      Map<String, ResolvedCompilerNode> nodesBySkill, Set<String> selected) {
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String skillId : selected) {
      if (hasCycle(skillId, nodesBySkill, selected, visiting, visited)) {
        throw new PlannerContractException(
            "catalog-derived dependency closure contains a cycle involving " + skillId);
      }
    }
  }

  private static boolean hasCycle(
      String skillId,
      Map<String, ResolvedCompilerNode> nodesBySkill,
      Set<String> selected,
      Set<String> visiting,
      Set<String> visited) {
    if (visited.contains(skillId) || !selected.contains(skillId)) {
      return false;
    }
    if (!visiting.add(skillId)) {
      return true;
    }
    ResolvedCompilerNode node = nodesBySkill.get(skillId);
    if (node != null) {
      for (String dep : node.dependsOn()) {
        if (selected.contains(dep) && hasCycle(dep, nodesBySkill, selected, visiting, visited)) {
          return true;
        }
      }
    }
    visiting.remove(skillId);
    visited.add(skillId);
    return false;
  }

  private static void validateTriggerCoverage(ParsedPlannerReport parsed) {
    boolean hasTrigger =
        parsed.steps().stream()
            .anyMatch(step -> step.owningSkillIds().contains("cip-trigger-generator"));
    if (!hasTrigger) {
      throw new PlannerContractException(
          "planner report missing trigger coverage (cip-trigger-generator)");
    }
  }

  private static void validateScriptMappingCoverage(
      ParsedPlannerReport parsed, NormalizedDesignFlow flow) {
    Set<NormalizedDesignFlow.MappingStage> requiredStages = new LinkedHashSet<>();
    for (NormalizedDesignFlow.DataMapping mapping : flow.dataMappings()) {
      requiredStages.add(mapping.stage());
    }
    if (requiredStages.isEmpty()) {
      return;
    }
    Set<String> scriptTexts = new LinkedHashSet<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (step.owningSkillIds().contains("cip-script-generator")) {
        scriptTexts.add(step.reportText().toLowerCase(Locale.ROOT));
      }
    }
    for (NormalizedDesignFlow.MappingStage stage : requiredStages) {
      String needle =
          switch (stage) {
            case INITIALIZATION -> "initialization";
            case CONVERSION -> "convert";
            case RESPONSE -> "response";
          };
      boolean covered =
          scriptTexts.stream().anyMatch(text -> text.contains(needle));
      if (!covered) {
        throw new PlannerContractException(
            "planner report missing script coverage for mapping stage " + stage);
      }
    }
  }

  private static void validateParticipants(
      ParsedPlannerReport parsed, NormalizedDesignFlow flow) {
    Set<String> known = new LinkedHashSet<>();
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      known.add(participant.participantId());
      known.add(participant.displayName());
    }
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      for (String hint : step.participantRefs()) {
        // The planner reads the IDS, so it names a participant the way the diagram writes it — the
        // bare alias, CIP. The flow carries the normalized id, p-cip. Comparing the raw hint
        // against the normalized id refuses a reference that points at a participant which is
        // right there, so normalize both sides. An invented name still matches nothing.
        if (!known.contains(hint)
            && !known.contains(IdsDocumentParser.normalizeParticipantId(hint))) {
          throw new PlannerContractException(
              "planner report references participant absent from normalized flow: " + hint);
        }
      }
    }
  }

  private static List<String> mapParticipantRefs(
      List<String> hints, NormalizedDesignFlow flow) {
    if (hints.isEmpty()) {
      return List.of();
    }
    Map<String, String> byDisplay = new HashMap<>();
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      byDisplay.put(participant.displayName(), participant.participantId());
    }
    LinkedHashSet<String> refs = new LinkedHashSet<>();
    for (String hint : hints) {
      refs.add(byDisplay.getOrDefault(hint, hint));
    }
    return List.copyOf(refs);
  }

  private static String stableStepId(ParsedPlannerReport.Step step) {
    String owner;
    if (step.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL
        && !step.toolOperationRefs().isEmpty()) {
      owner = step.toolOperationRefs().getFirst();
    } else if (!step.owningSkillIds().isEmpty()) {
      owner = step.owningSkillIds().getFirst();
    } else {
      owner = "step";
    }
    return "step-" + step.reportOrdinal() + "-" + owner;
  }

  private static List<String> deriveDependsOn(
      ParsedPlannerReport.Step step,
      Map<String, ResolvedCompilerNode> nodesBySkill,
      Map<String, List<String>> stepsByOwner,
      String previousApiHubStepId) {
    LinkedHashSet<String> deps = new LinkedHashSet<>();
    if (step.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL) {
      if (step.toolOperationRefs().contains("get_rest_api_operations_specification")
          || step.toolOperationRefs().contains("get_api_operation_specification")) {
        List<String> searchSteps = stepsByOwner.get("search_rest_api_operations");
        if (searchSteps == null || searchSteps.isEmpty()) {
          searchSteps = stepsByOwner.get("search_api_operations");
        }
        if (searchSteps != null && !searchSteps.isEmpty()) {
          deps.add(searchSteps.getLast());
        } else if (previousApiHubStepId != null) {
          deps.add(previousApiHubStepId);
        }
      }
      return List.copyOf(deps);
    }

    String text = step.reportText().toLowerCase(Locale.ROOT);
    if (text.startsWith("resolve ") && text.contains("binding")) {
      List<String> getSteps = stepsByOwner.get("get_rest_api_operations_specification");
      if (getSteps == null || getSteps.isEmpty()) {
        getSteps = stepsByOwner.get("get_api_operation_specification");
      }
      if (getSteps != null && !getSteps.isEmpty()) {
        deps.add(getSteps.getLast());
      }
      return List.copyOf(deps);
    }

    // Connection follow-ups depend on the prior primary step for the same owning skill.
    if (isConnectionFollowUp(step)) {
      for (String skillId : step.owningSkillIds()) {
        List<String> prior = stepsByOwner.get(skillId);
        if (prior != null && !prior.isEmpty()) {
          deps.add(prior.getLast());
        }
      }
      return List.copyOf(deps);
    }

    for (String skillId : step.owningSkillIds()) {
      ResolvedCompilerNode node = nodesBySkill.get(skillId);
      if (node == null) {
        continue;
      }
      for (String depSkill : node.dependsOn()) {
        List<String> prior = stepsByOwner.get(depSkill);
        if (prior != null) {
          deps.addAll(prior);
        }
      }
    }
    return List.copyOf(deps);
  }

  private static boolean isConnectionFollowUp(ParsedPlannerReport.Step step) {
    return step.reportText().toLowerCase(Locale.ROOT).startsWith("connect ");
  }

  private static boolean isResolveBindingStep(ParsedPlannerReport.Step step) {
    String text = step.reportText().toLowerCase(Locale.ROOT);
    return text.startsWith("resolve ") && text.contains("binding");
  }

  private static List<String> deriveRequiredArtifacts(
      ParsedPlannerReport.Step step, Map<String, ResolvedCompilerNode> nodesBySkill) {
    if (step.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL) {
      return List.of("NORMALIZED_DESIGN_FLOW");
    }
    LinkedHashSet<String> required = new LinkedHashSet<>();
    for (String skillId : step.owningSkillIds()) {
      ResolvedCompilerNode node = nodesBySkill.get(skillId);
      if (node != null) {
        required.addAll(node.consumes());
      }
    }
    if (required.isEmpty()) {
      required.add("NORMALIZED_DESIGN_FLOW");
    }
    return List.copyOf(required);
  }

  private static List<String> deriveProducedArtifacts(
      ParsedPlannerReport.Step step, Map<String, ResolvedCompilerNode> nodesBySkill) {
    if (step.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL) {
      return List.of("API_OPERATION_BINDINGS");
    }
    LinkedHashSet<String> produced = new LinkedHashSet<>();
    for (String skillId : step.owningSkillIds()) {
      ResolvedCompilerNode node = nodesBySkill.get(skillId);
      if (node != null) {
        produced.addAll(node.produces());
      }
    }
    return List.copyOf(produced);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
