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
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanism;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
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
  static final String ERROR_HANDLING_GENERATOR_SKILL_ID = "cip-error-handling-generator";
  static final String COMPOSITION_GENERATOR_SKILL_ID = "cip-composition-generator";
  static final String SCRIPT_GENERATOR_SKILL_ID = "cip-script-generator";
  static final String SERVICE_CALL_GENERATOR_SKILL_ID = "cip-service-call-generator";
  static final String TRANSFORMATION_GENERATOR_SKILL_ID = "cip-transformation-generator";

  private final CipDesignPlannerReportParser parser;

  public DesignPlanProjector() {
    this(new CipDesignPlannerReportParser());
  }

  public DesignPlanProjector(CipDesignPlannerReportParser parser) {
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  public DesignExecutionPlan project(
      DesignPlanReport report, ChainSemanticRevision revision, CompilerRunPin pin) {
    return project(report, revision, pin, null);
  }

  public DesignExecutionPlan project(
      DesignPlanReport report,
      ChainSemanticRevision revision,
      CompilerRunPin pin,
      RequirementBrief brief) {
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
    parsed = projectScriptGeneratorSteps(parsed, revision, brief);
    parsed = projectServiceCallSteps(parsed, revision, brief);
    parsed = projectErrorHandlingSteps(parsed, revision);
    validateDisabledTransformationGenerator(parsed);
    validateNoCatalogCycles(nodesBySkill, selectedSkills(parsed));
    validateTriggerCoverage(parsed);
    validateScriptMappingCoverage(parsed, revision, brief);
    validateBehaviorScriptCoverage(parsed, revision);

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
              produced,
              parsedStep.mappingIntentId(),
              parsedStep.serviceCallId(),
              parsedStep.regionId()));

      if (parsedStep.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL) {
        previousApiHubStepId = stepId;
        for (String tool : parsedStep.toolOperationRefs()) {
          stepsByOwner.computeIfAbsent(tool, key -> new ArrayList<>()).add(stepId);
        }
      } else if (parsedStep.serviceCallRole()
              != ParsedPlannerReport.ServiceCallRole.REFERENCE
          && !isConnectionFollowUp(parsedStep)) {
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

  /** Projects the typed planning contract without reading report wording or presentation order. */
  public DesignExecutionPlan project(
      DesignPlanContract contract,
      DesignPlanReport report,
      ChainSemanticRevision revision,
      CompilerRunPin pin,
      RequirementBrief brief) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(pin, "pin");
    if (!revision.revisionId().equals(contract.semanticRevisionId())) {
      throw new PlannerContractException("design plan contract names a different semantic revision");
    }
    if (!pin.subjectSha256().equals(contract.semanticRevisionHash())) {
      throw new PlannerContractException("design plan contract has a stale semantic revision hash");
    }
    String expectedHash = contractHash(contract);
    if (!contract.contractId().equals(report.contractId())
        || !expectedHash.equals(report.contractHash())) {
      throw new PlannerContractException("design plan report does not match its typed contract");
    }
    new DesignPlanContractValidator().validate(contract, revision, brief, pin);

    Map<String, ResolvedCompilerNode> nodesBySkill = indexNodes(pin.resolvedDag());
    Set<String> selected = new LinkedHashSet<>();
    for (DesignPlanContract.Step step : contract.steps()) {
      if (step.owner().kind() == DesignPlanContract.OwnerKind.SKILL) {
        selected.addAll(resolveSkillOwners(step.owner().id(), nodesBySkill));
      }
    }
    validateNoCatalogCycles(nodesBySkill, selected);

    List<DesignExecutionPlan.Step> steps = new ArrayList<>();
    int ordinal = 1;
    for (DesignPlanContract.Step step : contract.steps()) {
      List<String> skillOwners =
          step.owner().kind() == DesignPlanContract.OwnerKind.SKILL
              ? resolveSkillOwners(step.owner().id(), nodesBySkill)
              : List.of();
      List<String> toolOwners =
          step.owner().kind() == DesignPlanContract.OwnerKind.APIHUB_TOOL
              ? List.of(step.owner().id())
              : List.of();
      ParsedPlannerReport.Step artifactStep =
          new ParsedPlannerReport.Step(
              ordinal,
              step.summary(),
              step.owner().kind() == DesignPlanContract.OwnerKind.APIHUB_TOOL
                  ? ParsedPlannerReport.OwnerKind.APIHUB_TOOL
                  : ParsedPlannerReport.OwnerKind.SKILL,
              skillOwners,
              toolOwners,
              List.of(),
              List.of(),
              firstTarget(step, DesignPlanContract.TargetKind.MAPPING_INTENT),
              firstTarget(step, DesignPlanContract.TargetKind.SERVICE_CALL),
              ParsedPlannerReport.ServiceCallRole.NONE,
              firstTarget(step, DesignPlanContract.TargetKind.REGION));
      steps.add(
          new DesignExecutionPlan.Step(
              step.stepId(),
              ordinal++,
              step.summary(),
              step.owner().kind() == DesignPlanContract.OwnerKind.APIHUB_TOOL
                  ? DesignExecutionPlan.OwnerKind.APIHUB_TOOL
                  : DesignExecutionPlan.OwnerKind.SKILL,
              skillOwners,
              toolOwners,
              List.of(),
              List.of(),
              step.dependsOnStepIds(),
              deriveRequiredArtifacts(artifactStep, nodesBySkill),
              deriveProducedArtifacts(artifactStep, nodesBySkill),
              firstTarget(step, DesignPlanContract.TargetKind.MAPPING_INTENT),
              firstTarget(step, DesignPlanContract.TargetKind.SERVICE_CALL),
              firstTarget(step, DesignPlanContract.TargetKind.REGION),
              step.claims()));
    }
    return new DesignExecutionPlan(
        "2",
        contract.semanticRevisionId(),
        CipDesignPlannerAdapter.SKILL_ID,
        "chain-semantic-revision/" + contract.semanticRevisionId(),
        contract.semanticRevisionHash(),
        contract.apiRelease(),
        BINDING_RESOLUTION_POLICY,
        steps,
        "design-plan-report",
        sha256(report.markdown()),
        pin.skillSha256ById() == null ? Map.of() : pin.skillSha256ById(),
        pin.addonSha256ById() == null ? Map.of() : pin.addonSha256ById(),
        pin.pipelineIndexDigest(),
        BINDING_RESOLUTION_POLICY_HASH,
        contract.contractId(),
        expectedHash);
  }

  private static List<String> resolveSkillOwners(
      String ownerId, Map<String, ResolvedCompilerNode> nodesBySkill) {
    if (CHAIN_VALIDATOR_SKILL_ID.equals(ownerId)
        && !nodesBySkill.containsKey(CHAIN_VALIDATOR_SKILL_ID)) {
      List<String> validation = pinnedValidationSkillIds(nodesBySkill);
      if (!validation.isEmpty()) {
        return validation;
      }
    }
    return List.of(ownerId);
  }

  private static String firstTarget(
      DesignPlanContract.Step step, DesignPlanContract.TargetKind kind) {
    return step.claims().stream()
        .filter(claim -> claim.targetKind() == kind)
        .map(DesignPlanContract.Claim::targetId)
        .findFirst()
        .orElse("");
  }

  public static String contractHash(DesignPlanContract contract) {
    Objects.requireNonNull(contract, "contract");
    StringBuilder canonical = new StringBuilder();
    appendField(canonical, contract.schemaVersion());
    appendField(canonical, contract.contractId());
    appendField(canonical, contract.semanticRevisionId());
    appendField(canonical, contract.semanticRevisionHash());
    appendField(canonical, contract.apiRelease());
    for (DesignPlanContract.Step step : contract.steps()) {
      appendField(canonical, step.stepId());
      appendField(canonical, step.summary());
      appendField(canonical, step.owner().kind().name());
      appendField(canonical, step.owner().id());
      for (DesignPlanContract.Claim claim : step.claims()) {
        appendField(canonical, claim.targetKind().name());
        appendField(canonical, claim.targetId());
        appendField(canonical, claim.role().name());
      }
      canonical.append("claims-end;");
      for (String dependency : step.dependsOnStepIds()) {
        appendField(canonical, dependency);
      }
      canonical.append("dependencies-end;");
    }
    return sha256(canonical.toString());
  }

  private static void appendField(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value).append(';');
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
              step.operationQueryRefs(),
              step.mappingIntentId(),
              step.serviceCallId(),
              step.serviceCallRole(),
              step.regionId()));
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

  private static void validateDisabledTransformationGenerator(ParsedPlannerReport parsed) {
    if (MappingMechanismSelector.transformationGeneratorAllowed()) {
      return;
    }
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (step.owningSkillIds().contains(TRANSFORMATION_GENERATOR_SKILL_ID)
          && !step.mappingIntentId().isBlank()) {
        throw new PlannerContractException(
            "cip-transformation-generator is disabled for mapping because mapper-2 is off. Plan"
                + " mapping with cip-script-generator.");
      }
    }
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

  /**
   * Classifies each {@code cip-script-generator} step as mapping work or behavior work from the
   * approved semantic graph, then binds or drops it in one pass. Unnamed steps bind to remaining
   * mapping intents first. A leftover unnamed script-generator is kept only when a behavior-owned
   * script requires an owner. It is not a post-drop retention pass.
   */
  private static ParsedPlannerReport projectScriptGeneratorSteps(
      ParsedPlannerReport parsed, ChainSemanticRevision revision, RequirementBrief brief) {
    List<String> behaviorOwned =
        DefaultChainSemanticRevisionValidator.behaviorOwnedScriptNodeIds(revision);
    boolean needsBehaviorOwner = !behaviorOwned.isEmpty();
    List<MappingIntent> remaining = new ArrayList<>(revision.mappingBodies(brief));
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (!hasMappingGeneratorSkill(step) || step.mappingIntentId().isBlank()) {
        continue;
      }
      remaining.removeIf(intent -> intent.mappingIntentId().equals(step.mappingIntentId()));
    }
    Set<String> knownIds = new HashSet<>();
    for (MappingIntent intent : revision.mappingBodies(brief)) {
      knownIds.add(intent.mappingIntentId());
    }
    boolean changed = false;
    boolean keptBehaviorOwner = false;
    List<ParsedPlannerReport.Step> steps = new ArrayList<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (!hasMappingGeneratorSkill(step)) {
        steps.add(step);
        continue;
      }
      boolean unnamed = step.mappingIntentId().isBlank();
      boolean unknownNamed = !unnamed && !knownIds.contains(step.mappingIntentId());
      if (!unnamed && !unknownNamed) {
        steps.add(step);
        continue;
      }
      if (unnamed && !remaining.isEmpty()) {
        MappingIntent assigned = takeNextMatchingIntent(remaining, step);
        if (assigned != null) {
          remaining.remove(assigned);
          changed = true;
          steps.add(
              new ParsedPlannerReport.Step(
                  step.reportOrdinal(),
                  step.reportText(),
                  step.ownerKind(),
                  step.owningSkillIds(),
                  step.toolOperationRefs(),
                  step.participantRefs(),
                  step.operationQueryRefs(),
                  assigned.mappingIntentId(),
                  step.serviceCallId(),
                  step.serviceCallRole(),
                  step.regionId()));
          continue;
        }
      }
      if (unnamed
          && remaining.isEmpty()
          && needsBehaviorOwner
          && !keptBehaviorOwner
          && step.owningSkillIds().contains(SCRIPT_GENERATOR_SKILL_ID)) {
        LinkedHashSet<String> owners = new LinkedHashSet<>(step.owningSkillIds());
        owners.remove(TRANSFORMATION_GENERATOR_SKILL_ID);
        keptBehaviorOwner = true;
        changed = true;
        steps.add(
            new ParsedPlannerReport.Step(
                step.reportOrdinal(),
                step.reportText(),
                step.ownerKind(),
                List.copyOf(owners),
                step.toolOperationRefs(),
                step.participantRefs(),
                step.operationQueryRefs(),
                "",
                step.serviceCallId(),
                step.serviceCallRole(),
                step.regionId()));
        continue;
      }
      LinkedHashSet<String> owners = new LinkedHashSet<>(step.owningSkillIds());
      owners.remove(SCRIPT_GENERATOR_SKILL_ID);
      owners.remove(TRANSFORMATION_GENERATOR_SKILL_ID);
      changed = true;
      if (owners.isEmpty()
          && step.toolOperationRefs().isEmpty()
          && step.ownerKind() == ParsedPlannerReport.OwnerKind.SKILL) {
        continue;
      }
      steps.add(
          new ParsedPlannerReport.Step(
              step.reportOrdinal(),
              step.reportText(),
              step.ownerKind(),
              List.copyOf(owners),
              step.toolOperationRefs(),
              step.participantRefs(),
              step.operationQueryRefs(),
              unnamed ? step.mappingIntentId() : "",
              step.serviceCallId(),
              step.serviceCallRole(),
              step.regionId()));
    }
    if (!changed || steps.isEmpty()) {
      return parsed;
    }
    return new ParsedPlannerReport(steps, parsed.apiRelease());
  }

  private static ParsedPlannerReport projectServiceCallSteps(
      ParsedPlannerReport parsed, ChainSemanticRevision revision, RequirementBrief brief) {
    List<SemanticNode.ServiceCall> calls =
        revision.nodes().stream()
            .filter(SemanticNode.ServiceCall.class::isInstance)
            .map(SemanticNode.ServiceCall.class::cast)
            .toList();
    Map<String, SemanticNode.ServiceCall> callsById = new LinkedHashMap<>();
    for (SemanticNode.ServiceCall call : calls) {
      callsById.put(call.serviceCallId(), call);
    }
    Map<String, RequirementServiceCall> approvedById = new LinkedHashMap<>();
    if (brief != null) {
      for (RequirementServiceCall call : brief.serviceCalls()) {
        approvedById.put(call.serviceCallId(), call);
      }
    }
    int defaultProducerOrdinal = defaultServiceCallProducerOrdinal(parsed, calls);

    Set<String> produced = new LinkedHashSet<>();
    List<ParsedPlannerReport.Step> projected = new ArrayList<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      boolean ownsGenerator = step.owningSkillIds().contains(SERVICE_CALL_GENERATOR_SKILL_ID);
      if (!ownsGenerator && step.serviceCallId().isBlank()) {
        projected.add(step);
        continue;
      }
      String serviceCallId = step.serviceCallId();
      if (!ownsGenerator) {
        if (!callsById.containsKey(serviceCallId)) {
          throw new PlannerContractException(
              "planner step names unknown serviceCallId: " + serviceCallId);
        }
        if (step.serviceCallRole() != ParsedPlannerReport.ServiceCallRole.REFERENCE) {
          throw new PlannerContractException(
              "serviceCallId="
                  + serviceCallId
                  + " on a non-generator step must declare serviceCallRole=REFERENCE");
        }
        projected.add(step);
        continue;
      }
      if (serviceCallId.isBlank()) {
        if (calls.size() != 1) {
          throw new PlannerContractException(
              "planner service-call step "
                  + step.reportOrdinal()
                  + " is missing serviceCallId=<id>");
        }
        serviceCallId = calls.getFirst().serviceCallId();
      }
      SemanticNode.ServiceCall semanticCall = callsById.get(serviceCallId);
      if (semanticCall == null) {
        throw new PlannerContractException(
            "planner service-call step names unknown serviceCallId: " + serviceCallId);
      }
      ParsedPlannerReport.ServiceCallRole role = step.serviceCallRole();
      if (role == ParsedPlannerReport.ServiceCallRole.NONE) {
        role =
            step.reportOrdinal() == defaultProducerOrdinal
                ? ParsedPlannerReport.ServiceCallRole.PRODUCER
                : ParsedPlannerReport.ServiceCallRole.REFERENCE;
      }
      if (role == ParsedPlannerReport.ServiceCallRole.PRODUCER && !produced.add(serviceCallId)) {
        throw new PlannerContractException(
            "serviceCallId=" + serviceCallId + " has more than one producing step");
      }
      RequirementServiceCall approved = approvedById.get(serviceCallId);
      projected.add(
          new ParsedPlannerReport.Step(
              step.reportOrdinal(),
              canonicalServiceCallText(
                  step, semanticCall, approved, serviceCallId, role),
              step.ownerKind(),
              step.owningSkillIds(),
              step.toolOperationRefs(),
              approved == null || approved.participant().isBlank()
                  ? List.of()
                  : List.of(approved.participant()),
              List.of(semanticCall.operation()),
              step.mappingIntentId(),
              serviceCallId,
              role,
              step.regionId()));
    }
    for (SemanticNode.ServiceCall call : calls) {
      if (!produced.contains(call.serviceCallId())) {
        throw new PlannerContractException(
            "planner report missing producing step for serviceCallId=" + call.serviceCallId());
      }
    }
    return new ParsedPlannerReport(projected, parsed.apiRelease());
  }

  /**
   * Compatibility for old single-occurrence plans that predate the role token. The last untyped
   * service-call step is the producer; earlier steps are references. Multi-occurrence plans remain
   * strict because their occurrence ids cannot be inferred.
   */
  private static int defaultServiceCallProducerOrdinal(
      ParsedPlannerReport parsed, List<SemanticNode.ServiceCall> calls) {
    if (calls.size() != 1) {
      return -1;
    }
    boolean hasDeclaredProducer =
        parsed.steps().stream()
            .filter(step -> step.owningSkillIds().contains(SERVICE_CALL_GENERATOR_SKILL_ID))
            .anyMatch(
                step ->
                    step.serviceCallRole()
                        == ParsedPlannerReport.ServiceCallRole.PRODUCER);
    if (hasDeclaredProducer) {
      return -1;
    }
    int ordinal = -1;
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (step.owningSkillIds().contains(SERVICE_CALL_GENERATOR_SKILL_ID)
          && step.serviceCallRole() == ParsedPlannerReport.ServiceCallRole.NONE) {
        ordinal = step.reportOrdinal();
      }
    }
    return ordinal;
  }

  private static String canonicalServiceCallText(
      ParsedPlannerReport.Step step,
      SemanticNode.ServiceCall semanticCall,
      RequirementServiceCall approved,
      String serviceCallId,
      ParsedPlannerReport.ServiceCallRole role) {
    if (approved == null || approved.participant().isBlank()) {
      return step.reportText();
    }
    String identity =
        approved.participant()
            + "."
            + semanticCall.operation()
            + " failureMode="
            + semanticCall.failureMode();
    if (role == ParsedPlannerReport.ServiceCallRole.REFERENCE) {
      return "Use the approved catalog binding for "
          + identity
          + " (cip-service-call-generator serviceCallId="
          + serviceCallId
          + " serviceCallRole=REFERENCE"
          + ")";
    }
    return "Generate Service Call element "
        + semanticCall.nodeId()
        + " for "
        + identity
        + " (cip-service-call-generator serviceCallId="
        + serviceCallId
        + " serviceCallRole=PRODUCER"
        + ")";
  }

  private static ParsedPlannerReport projectErrorHandlingSteps(
      ParsedPlannerReport parsed, ChainSemanticRevision revision) {
    Map<String, SemanticRegion.ErrorScope> scopesById = new LinkedHashMap<>();
    for (SemanticRegion region : revision.regions()) {
      if (region instanceof SemanticRegion.ErrorScope scope) {
        scopesById.put(scope.regionId(), scope);
      }
    }
    Set<String> produced = new LinkedHashSet<>();
    List<ParsedPlannerReport.Step> projected = new ArrayList<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      boolean ownsGenerator = step.owningSkillIds().contains(ERROR_HANDLING_GENERATOR_SKILL_ID);
      if (!ownsGenerator && step.regionId().isBlank()) {
        projected.add(step);
        continue;
      }
      String regionId = step.regionId();
      if (!ownsGenerator) {
        if (!scopesById.containsKey(regionId)) {
          throw new PlannerContractException("planner step names unknown regionId: " + regionId);
        }
        if (!isConnectionFollowUp(step)) {
          throw new PlannerContractException(
              "regionId="
                  + regionId
                  + " appears on a step that neither produces nor connects that region");
        }
        projected.add(step);
        continue;
      }
      if (regionId.isBlank()) {
        if (scopesById.size() != 1) {
          throw new PlannerContractException(
              "planner error-handling step " + step.reportOrdinal() + " is missing regionId=<id>");
        }
        regionId = scopesById.keySet().iterator().next();
      }
      SemanticRegion.ErrorScope scope = scopesById.get(regionId);
      if (scope == null) {
        throw new PlannerContractException(
            "planner error-handling step names unknown regionId: " + regionId);
      }
      if (!produced.add(regionId)) {
        throw new PlannerContractException(
            "regionId=" + regionId + " has more than one error-handling producing step");
      }
      projected.add(
          new ParsedPlannerReport.Step(
              step.reportOrdinal(),
              "Generate error handling for regionId="
                  + regionId
                  + " owned by "
                  + scope.ownerNodeId()
                  + " (cip-error-handling-generator regionId="
                  + regionId
                  + ")",
              step.ownerKind(),
              step.owningSkillIds(),
              step.toolOperationRefs(),
              step.participantRefs(),
              step.operationQueryRefs(),
              step.mappingIntentId(),
              step.serviceCallId(),
              step.serviceCallRole(),
              regionId));
    }
    for (String regionId : scopesById.keySet()) {
      if (!produced.contains(regionId)) {
        throw new PlannerContractException(
            "planner report missing error-handling step for regionId=" + regionId);
      }
    }
    return new ParsedPlannerReport(projected, parsed.apiRelease());
  }

  private static MappingIntent takeNextMatchingIntent(
      List<MappingIntent> remaining, ParsedPlannerReport.Step step) {
    for (MappingIntent intent : remaining) {
      Optional<MappingMechanism> selected = MappingMechanismSelector.select(intent);
      if (selected.isPresent() && step.owningSkillIds().contains(skillFor(selected.get()))) {
        return intent;
      }
    }
    return remaining.isEmpty() ? null : remaining.getFirst();
  }

  private static final Set<String> TRIGGER_COVERAGE_OWNERS =
      Set.of(
          "cip-trigger-generator",
          "cip-http-trigger-endpoint-generator",
          "cip-messaging-generator",
          "cip-quartz-scheduler-generator",
          "cip-sds-trigger-generator",
          "cip-sftp-trigger-generator",
          "cip-mcp-trigger-generator");

  private static void validateTriggerCoverage(ParsedPlannerReport parsed) {
    boolean hasTrigger =
        parsed.steps().stream().anyMatch(DesignPlanProjector::coversEntryPointTrigger);
    if (!hasTrigger) {
      throw new PlannerContractException(
          "planner report missing trigger coverage (entry-point producer skill)");
    }
  }

  private static boolean coversEntryPointTrigger(ParsedPlannerReport.Step step) {
    for (String skillId : step.owningSkillIds()) {
      if (TRIGGER_COVERAGE_OWNERS.contains(skillId)) {
        return true;
      }
      if (SERVICE_CALL_GENERATOR_SKILL_ID.equals(skillId)
          && step.reportText() != null
          && step.reportText().toLowerCase(Locale.ROOT).contains("trigger")) {
        return true;
      }
    }
    return false;
  }

  private static void validateScriptMappingCoverage(
      ParsedPlannerReport parsed, ChainSemanticRevision revision, RequirementBrief brief) {
    List<ParsedPlannerReport.Step> mappingSteps = collectMappingGeneratorSteps(parsed);
    List<MappingIntent> intents = revision.mappingBodies(brief);
    if (intents.isEmpty()) {
      rejectUnexpectedMappingSteps(mappingSteps);
      return;
    }
    Map<String, MappingIntent> intentsById = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      intentsById.put(intent.mappingIntentId(), intent);
    }
    Set<String> seen = new HashSet<>();
    for (ParsedPlannerReport.Step step : mappingSteps) {
      coverMappingStep(step, intentsById, seen);
    }
    requireAllIntentsCovered(intents, seen);
  }

  private static void validateBehaviorScriptCoverage(
      ParsedPlannerReport parsed, ChainSemanticRevision revision) {
    List<String> behaviorOwned =
        DefaultChainSemanticRevisionValidator.behaviorOwnedScriptNodeIds(revision);
    if (behaviorOwned.isEmpty()) {
      return;
    }
    boolean hasScriptGenerator =
        parsed.steps().stream()
            .anyMatch(step -> step.owningSkillIds().contains(SCRIPT_GENERATOR_SKILL_ID));
    if (!hasScriptGenerator) {
      throw new PlannerContractException(
          "planner report missing script coverage for behavior-owned node "
              + String.join(", ", behaviorOwned)
              + " (cip-script-generator)");
    }
  }

  private static List<ParsedPlannerReport.Step> collectMappingGeneratorSteps(
      ParsedPlannerReport parsed) {
    List<ParsedPlannerReport.Step> mappingSteps = new ArrayList<>();
    for (ParsedPlannerReport.Step step : parsed.steps()) {
      if (isBoundMappingGeneratorStep(step)) {
        mappingSteps.add(step);
      }
    }
    return mappingSteps;
  }

  private static void rejectUnexpectedMappingSteps(List<ParsedPlannerReport.Step> mappingSteps) {
    if (!mappingSteps.isEmpty()) {
      throw new PlannerContractException(
          "planner report must not include mapping-generator skills for a pass-through revision");
    }
  }

  private static void coverMappingStep(
      ParsedPlannerReport.Step step,
      Map<String, MappingIntent> intentsById,
      Set<String> seen) {
    String intentId = step.mappingIntentId();
    if (intentId.isBlank()) {
      throw new PlannerContractException(
          "planner report mapping-generator step is missing mappingIntentId");
    }
    MappingIntent intent = intentsById.get(intentId);
    if (intent == null) {
      throw new PlannerContractException(
          "planner report mapping-generator step names unknown mappingIntentId: " + intentId);
    }
    if (!seen.add(intentId)) {
      throw new PlannerContractException(
          "planner report mappingIntentId appears more than once: " + intentId);
    }
    requireMatchingSkill(intent, step);
  }

  private static void requireAllIntentsCovered(List<MappingIntent> intents, Set<String> seen) {
    for (MappingIntent intent : intents) {
      requireSelectedMechanism(intent);
      if (!seen.contains(intent.mappingIntentId())) {
        throw new PlannerContractException(
            "planner report missing script coverage for mappingIntentId "
                + intent.mappingIntentId());
      }
    }
  }

  private static boolean hasMappingGeneratorSkill(ParsedPlannerReport.Step step) {
    for (String skillId : step.owningSkillIds()) {
      if (SCRIPT_GENERATOR_SKILL_ID.equals(skillId)) {
        return true;
      }
      if (TRANSFORMATION_GENERATOR_SKILL_ID.equals(skillId) && !step.mappingIntentId().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBoundMappingGeneratorStep(ParsedPlannerReport.Step step) {
    return hasMappingGeneratorSkill(step) && !step.mappingIntentId().isBlank();
  }

  private static void requireMatchingSkill(
      MappingIntent intent, ParsedPlannerReport.Step step) {
    MappingMechanism mechanism = requireSelectedMechanism(intent);
    String expectedSkill = skillFor(mechanism);
    if (!step.owningSkillIds().contains(expectedSkill)) {
      throw new PlannerContractException(
          "planner report mapping intent '"
              + intent.mappingIntentId()
              + "' requires "
              + expectedSkill);
    }
  }

  private static MappingMechanism requireSelectedMechanism(MappingIntent intent) {
    Optional<MappingMechanism> selected = MappingMechanismSelector.select(intent);
    if (selected.isEmpty()) {
      throw new PlannerContractException(
          "planner report mapping intent '"
              + intent.mappingIntentId()
              + "' has no selected mechanism");
    }
    return selected.get();
  }

  private static String skillFor(MappingMechanism mechanism) {
    return mechanism == MappingMechanism.SCRIPT
        ? SCRIPT_GENERATOR_SKILL_ID
        : TRANSFORMATION_GENERATOR_SKILL_ID;
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

    if (step.serviceCallRole() == ParsedPlannerReport.ServiceCallRole.REFERENCE
        && step.owningSkillIds().contains(SERVICE_CALL_GENERATOR_SKILL_ID)) {
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
