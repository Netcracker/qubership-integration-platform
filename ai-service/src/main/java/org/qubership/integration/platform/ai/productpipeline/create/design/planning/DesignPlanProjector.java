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
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.IdsDocumentParser;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
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
      DesignPlanReport report, ChainSemanticRevision revision, CompilerRunPin pin) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(pin, "pin");
    ResolvedCompilerDag pinnedDag = pin.resolvedDag();
    Objects.requireNonNull(pinnedDag, "pin.resolvedDag");
    String compilerCatalogHash = pin.pipelineIndexDigest();
    Objects.requireNonNull(compilerCatalogHash, "pin.pipelineIndexDigest");
    String semanticRevisionId = pin.subjectRevisionId();
    Objects.requireNonNull(semanticRevisionId, "pin.subjectRevisionId");
    String designInputHash = pin.subjectSha256();
    Objects.requireNonNull(designInputHash, "pin.subjectSha256");

    ParsedPlannerReport parsed = parser.parse(report.markdown());
    Map<String, ResolvedCompilerNode> nodesBySkill = indexNodes(pinnedDag);
    // Upstream design-planner still names cip-chain-validator; the runtime skill catalog
    // decomposed that gate into the pinned Validation producers. Rewrite before catalog checks.
    parsed = rewriteChainValidatorAlias(parsed, nodesBySkill);
    validateUnknownSkills(parsed, nodesBySkill);
    validateNoCatalogCycles(nodesBySkill, selectedSkills(parsed));
    validateTriggerCoverage(parsed);
    validateScriptMappingCoverage(parsed, revision);
    validateParticipants(parsed, revision);

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
              mapParticipantRefs(parsedStep.participantRefs()),
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

    return new DesignExecutionPlan(
        report.schemaVersion(),
        semanticRevisionId,
        CipDesignPlannerAdapter.SKILL_ID,
        "chain-semantic-revision/" + semanticRevisionId,
        designInputHash,
        apiRelease,
        BINDING_RESOLUTION_POLICY,
        steps,
        "design-plan-report",
        sourceReportHash,
        pin.skillSha256ById() == null ? Map.of() : pin.skillSha256ById(),
        pin.addonSha256ById() == null ? Map.of() : pin.addonSha256ById(),
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
      ParsedPlannerReport parsed, ChainSemanticRevision revision) {
    if (revision.mappingIntents().isEmpty()) {
      return;
    }
    boolean covered =
        parsed.steps().stream()
            .anyMatch(
                step ->
                    step.owningSkillIds().contains("cip-script-generator")
                        || step.owningSkillIds().contains("cip-transformation-generator"));
    if (!covered) {
      throw new PlannerContractException(
          "planner report missing script coverage for an explicit mapping intent");
    }
  }

  private static void validateParticipants(
      ParsedPlannerReport parsed, ChainSemanticRevision revision) {
    Set<String> known = knownIdentities(revision);
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      for (String hint : step.participantRefs()) {
        if (!known.contains(hint)
            && !known.contains(IdsDocumentParser.normalizeParticipantId(hint))) {
          throw new PlannerContractException(
              "planner report references a participant that is absent from the semantic revision: "
                  + hint);
        }
      }
    }
  }

  private static Set<String> knownIdentities(ChainSemanticRevision revision) {
    Set<String> known = new LinkedHashSet<>();
    known.add(revision.chainIdentity());
    known.add(revision.chainIdentity() + " Service");
    known.add(revision.chainIdentity() + " API");
    known.add("Client");
    known.add("CIP");
    for (SemanticEntryPoint entry : revision.entryPoints()) {
      known.add(entry.entryPointId());
      known.add(entry.triggerNodeId());
      known.add(entry.initialTargetNodeId());
      if (entry.presentation() != null && entry.presentation().label() != null) {
        known.add(entry.presentation().label());
      }
    }
    for (SemanticNode node : revision.nodes()) {
      known.add(node.nodeId());
      if (node instanceof SemanticNode.ServiceCall call) {
        known.add(call.serviceCallId());
        known.add(call.operation());
      } else if (node instanceof SemanticNode.Trigger trigger) {
        known.add(trigger.capabilityKey());
      } else if (node instanceof SemanticNode.Operation operation) {
        known.add(operation.elementType());
      }
    }
    known.addAll(revision.constraints());
    known.addAll(revision.assumptions());
    return known;
  }

  private static List<String> mapParticipantRefs(List<String> hints) {
    return hints == null ? List.of() : List.copyOf(hints);
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
      return List.of(Kind.CHAIN_SEMANTIC_REVISION.name());
    }
    LinkedHashSet<String> required = new LinkedHashSet<>();
    for (String skillId : step.owningSkillIds()) {
      ResolvedCompilerNode node = nodesBySkill.get(skillId);
      if (node != null) {
        required.addAll(node.consumes());
      }
    }
    if (required.isEmpty()) {
      required.add(Kind.CHAIN_SEMANTIC_REVISION.name());
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
