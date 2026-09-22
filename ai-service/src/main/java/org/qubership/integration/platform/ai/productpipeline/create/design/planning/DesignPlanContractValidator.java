package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFlowValidator;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanism;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Claim;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Validates one typed plan against the approved semantic revision and pinned compiler catalog. */
public final class DesignPlanContractValidator {

  public enum BindingPolicy {
    CATALOG_FIRST,
    CATALOG_ONLY
  }

  private static final Set<String> BINDING_PRODUCERS =
      Set.of("get_rest_api_operations_specification", "get_api_operation_specification");
  private static final Set<String> APIHUB_OPERATIONS =
      Set.of(
          "search_rest_api_operations",
          "search_api_operations",
          "get_rest_api_operations_specification",
          "get_api_operation_specification");

  public List<DesignPlanContractFinding> findings(
      DesignPlanContract contract,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin) {
    return findings(contract, revision, brief, pin, BindingPolicy.CATALOG_FIRST);
  }

  public List<DesignPlanContractFinding> findings(
      DesignPlanContract contract,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin,
      BindingPolicy bindingPolicy) {
    Objects.requireNonNull(bindingPolicy, "bindingPolicy");
    List<DesignPlanContractFinding> findings = new ArrayList<>();
    Map<TargetKey, String> expectedOwners = expectedOwners(revision, brief, bindingPolicy);
    Map<String, Set<TargetKind>> expectedKindsById = expectedKindsById(expectedOwners.keySet());
    Set<String> knownSkillIds = pin.resolvedDag().nodes().stream()
        .map(node -> node.skillId())
        .collect(java.util.stream.Collectors.toSet());
    Map<String, DesignPlanContract.Step> stepsById = new LinkedHashMap<>();
    Map<TargetKey, List<String>> producers = new LinkedHashMap<>();

    for (DesignPlanContract.Step step : contract.steps()) {
      if (stepsById.putIfAbsent(step.stepId(), step) != null) {
        findings.add(finding(DesignPlanContractFinding.Code.DUPLICATE_STEP_ID, null, "", step.stepId(),
            "Duplicate stepId=" + step.stepId()));
      }
      if (step.owner().kind() == OwnerKind.SKILL
          && !knownSkillIds.contains(step.owner().id())
          && !DesignPlanProjector.CHAIN_VALIDATOR_SKILL_ID.equals(step.owner().id())) {
        findings.add(finding(DesignPlanContractFinding.Code.UNKNOWN_OWNER, null, "", step.stepId(),
            "Unknown planner skill owner " + step.owner().id()));
      }
      if (step.owner().kind() == OwnerKind.APIHUB_TOOL
          && !APIHUB_OPERATIONS.contains(step.owner().id())) {
        findings.add(finding(DesignPlanContractFinding.Code.UNKNOWN_OWNER, null, "", step.stepId(),
            "Unknown APIHub operation owner " + step.owner().id()));
      }
      if (step.owner().kind() == OwnerKind.APIHUB_TOOL
          && bindingPolicy == BindingPolicy.CATALOG_ONLY) {
        findings.add(
            finding(
                DesignPlanContractFinding.Code.OWNER_TARGET_MISMATCH,
                null,
                "",
                step.stepId(),
                "CATALOG_ONLY forbids APIHub tool owners"));
      }
      Set<Claim> unique = new HashSet<>();
      for (Claim claim : step.claims()) {
        TargetKey key = new TargetKey(claim.targetKind(), claim.targetId());
        if (!unique.add(claim)) {
          findings.add(finding(DesignPlanContractFinding.Code.DUPLICATE_STEP_CLAIM,
              key.kind(), key.id(), step.stepId(), "Duplicate claim for " + key));
          continue;
        }
        String expectedOwner = expectedOwners.get(key);
        if (expectedOwner == null) {
          DesignPlanContractFinding.Code code =
              expectedKindsById.containsKey(key.id())
                  ? DesignPlanContractFinding.Code.TARGET_KIND_MISMATCH
                  : DesignPlanContractFinding.Code.UNKNOWN_TARGET;
          findings.add(
              finding(
                  code,
                  key.kind(),
                  key.id(),
                  step.stepId(),
                  code == DesignPlanContractFinding.Code.TARGET_KIND_MISMATCH
                      ? "Target id "
                          + key.id()
                          + " belongs to "
                          + expectedKindsById.get(key.id())
                          + ", not "
                          + key.kind()
                      : "Unknown " + key));
          continue;
        }
        if (claim.role() == ClaimRole.PRODUCER) {
          if (!ownerMatches(step, key, expectedOwner)) {
            findings.add(finding(DesignPlanContractFinding.Code.OWNER_TARGET_MISMATCH,
                key.kind(), key.id(), step.stepId(),
                "Target " + key + " requires owner " + expectedOwner));
          }
          producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(step.stepId());
        }
      }
    }

    for (TargetKey key : expectedOwners.keySet()) {
      List<String> owners = producers.getOrDefault(key, List.of());
      if (owners.isEmpty()) {
        findings.add(finding(DesignPlanContractFinding.Code.MISSING_TARGET_PRODUCER,
            key.kind(), key.id(), "", "Missing producer for " + key));
      } else if (owners.size() > 1) {
        findings.add(finding(DesignPlanContractFinding.Code.DUPLICATE_TARGET_PRODUCER,
            key.kind(), key.id(), String.join(",", owners), "More than one producer for " + key));
      }
    }
    validateRequiredSupportOwners(contract, pin, findings);
    validateDependencies(contract, stepsById.keySet(), findings);
    validatePinnedDependencies(contract, pin, findings);
    return List.copyOf(findings);
  }

  public void validate(
      DesignPlanContract contract,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin) {
    List<DesignPlanContractFinding> findings = findings(contract, revision, brief, pin);
    if (!findings.isEmpty()) {
      throw new PlannerContractException(format(findings), findings);
    }
  }

  public void validate(
      DesignPlanContract contract,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin,
      BindingPolicy bindingPolicy) {
    List<DesignPlanContractFinding> findings =
        findings(contract, revision, brief, pin, bindingPolicy);
    if (!findings.isEmpty()) {
      throw new PlannerContractException(format(findings), findings);
    }
  }

  public static String format(List<DesignPlanContractFinding> findings) {
    return findings.stream()
        .map(finding -> finding.evidenceIdentity() + " " + finding.message())
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static Map<TargetKey, String> expectedOwners(
      ChainSemanticRevision revision, RequirementBrief brief, BindingPolicy bindingPolicy) {
    Map<TargetKey, String> owners = new LinkedHashMap<>();
    revision.entryPoints().forEach(entry -> {
      String owner = ownerForEntryPoint(entry, revision, brief);
      if (owner != null) {
        owners.put(new TargetKey(TargetKind.ENTRY_POINT, entry.entryPointId()), owner);
      }
    });
    revision.nodes().stream()
        .filter(SemanticNode.ServiceCall.class::isInstance)
        .map(SemanticNode.ServiceCall.class::cast)
        .forEach(call -> owners.put(
            new TargetKey(TargetKind.SERVICE_CALL, call.serviceCallId()),
            DesignPlanProjector.SERVICE_CALL_GENERATOR_SKILL_ID));
    revision.nodes().stream()
        .filter(SemanticNode.Operation.class::isInstance)
        .map(SemanticNode.Operation.class::cast)
        .filter(
            operation ->
                ChainElementFamilies.isSender(operation.elementType())
                    || ChainElementFamilies.isFileTransfer(operation.elementType()))
        .forEach(
            operation ->
                owners.put(
                    new TargetKey(TargetKind.ELEMENT_NODE, operation.nodeId()),
                    ownerForDirectOperation(operation.elementType())));
    revision.nodes().stream()
        .filter(SemanticNode.Operation.class::isInstance)
        .map(SemanticNode.Operation.class::cast)
        .filter(operation -> ChainElementFamilies.CHAIN_CALL.contains(operation.elementType()))
        .forEach(
            operation ->
                owners.put(
                    new TargetKey(TargetKind.ELEMENT_NODE, operation.nodeId()),
                    DesignPlanProjector.COMPOSITION_GENERATOR_SKILL_ID));
    if (brief != null) {
      Set<String> occurrenceIds =
          revision.nodes().stream()
              .filter(SemanticNode.ServiceCall.class::isInstance)
              .map(SemanticNode.ServiceCall.class::cast)
              .map(SemanticNode.ServiceCall::serviceCallId)
              .collect(java.util.stream.Collectors.toSet());
      for (RequirementServiceCall call : brief.serviceCalls()) {
        if (bindingPolicy == BindingPolicy.CATALOG_FIRST
            && occurrenceIds.contains(call.serviceCallId())
            && call.catalogBinding() == null) {
          owners.put(
              new TargetKey(TargetKind.CATALOG_BINDING, call.serviceCallId()),
              "APIHUB_BINDING_PRODUCER");
        }
      }
    }
    for (MappingIntent intent : revision.mappingBodies(brief)) {
      MappingMechanism mechanism = MappingMechanismSelector.select(intent)
          .orElseThrow(() -> new PlannerContractException(
              "mapping intent " + intent.mappingIntentId() + " has no selected mechanism"));
      owners.put(
          new TargetKey(TargetKind.MAPPING_INTENT, intent.mappingIntentId()),
          mechanism == MappingMechanism.SCRIPT
              ? DesignPlanProjector.SCRIPT_GENERATOR_SKILL_ID
              : DesignPlanProjector.TRANSFORMATION_GENERATOR_SKILL_ID);
    }
    for (SemanticRegion region : revision.regions()) {
      owners.put(new TargetKey(TargetKind.REGION, region.regionId()), ownerForRegion(region));
    }
    for (String nodeId : DefaultChainSemanticRevisionValidator.behaviorOwnedScriptNodeIds(revision)) {
      owners.put(
          new TargetKey(TargetKind.BEHAVIOR_NODE, nodeId),
          DesignPlanProjector.SCRIPT_GENERATOR_SKILL_ID);
    }
    return owners;
  }

  private static Map<String, Set<TargetKind>> expectedKindsById(Set<TargetKey> targets) {
    Map<String, Set<TargetKind>> kindsById = new HashMap<>();
    for (TargetKey target : targets) {
      kindsById
          .computeIfAbsent(target.id(), ignored -> new LinkedHashSet<>())
          .add(target.kind());
    }
    return kindsById;
  }

  static String ownerForRegion(SemanticRegion region) {
    return switch (region) {
      case SemanticRegion.Sequence ignored -> "cip-structure-generator";
      case SemanticRegion.Condition ignored -> "cip-structure-generator";
      case SemanticRegion.Split ignored -> "cip-structure-generator";
      case SemanticRegion.Loop ignored -> "cip-loop-generator";
      case SemanticRegion.Retry ignored -> "cip-retry-generator";
      case SemanticRegion.ErrorScope ignored -> DesignPlanProjector.ERROR_HANDLING_GENERATOR_SKILL_ID;
    };
  }

  static String ownerForEntryPoint(
      SemanticEntryPoint entry, ChainSemanticRevision revision, RequirementBrief brief) {
    String capabilityKey = triggerCapabilityKey(entry, revision);
    if ("http-trigger".equals(capabilityKey)) {
      if (brief == null) {
        return null;
      }
      Interaction interaction =
          RequirementFlowValidator.interactionForEntryPoint(brief, entry.entryPointId());
      return switch (RequirementFlowValidator.catalogLookupAction(interaction, brief.facts())) {
        case SKIP -> "cip-http-trigger-endpoint-generator";
        case REQUIRE -> DesignPlanProjector.SERVICE_CALL_GENERATOR_SKILL_ID;
        case ASK, REJECT_UNSUPPORTED -> null;
      };
    }
    return ownerForTriggerCapability(capabilityKey);
  }

  /**
   * Skill that writes this element type. Empty when the type has no owner in the create-chain
   * table.
   */
  public static Optional<String> ownerSkillId(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return Optional.empty();
    }
    String type = elementType.trim();
    if (ChainElementFamilies.isFileTransfer(type) || ChainElementFamilies.isSender(type)) {
      return Optional.of(ownerForDirectOperation(type));
    }
    if (ChainElementFamilies.isTrigger(type)) {
      return Optional.of(ownerForTriggerCapability(type));
    }
    if ("script".equals(type)) {
      return Optional.of(DesignPlanProjector.SCRIPT_GENERATOR_SKILL_ID);
    }
    if (ChainElementFamilies.LOOP.contains(type)) {
      return Optional.of("cip-loop-generator");
    }
    return Optional.empty();
  }

  static String ownerForDirectOperation(String elementType) {
    if (ChainElementFamilies.isFileTransfer(elementType)) {
      return "cip-file-operations-generator";
    }
    return ownerForSender(elementType);
  }

  static String ownerForSender(String elementType) {
    return switch (elementType) {
      case "jms-sender", "pubsub-sender" -> "cip-messaging-generator";
      case "http-sender",
          "kafka-sender-2",
          "graphql-sender",
          "rabbitmq-sender-2",
          "scs-sender",
          "mail-sender" ->
          DesignPlanProjector.SERVICE_CALL_GENERATOR_SKILL_ID;
      default ->
          throw new IllegalArgumentException("Unsupported sender element type: " + elementType);
    };
  }

  static String ownerForTriggerCapability(String capabilityKey) {
    return switch (capabilityKey) {
      case "chain-trigger-2" -> "cip-trigger-generator";
      case "kafka-trigger-2",
          "jms-trigger",
          "pubsub-trigger",
          "rabbitmq-trigger-2" ->
          "cip-messaging-generator";
      case "quartz-scheduler" -> "cip-quartz-scheduler-generator";
      case "sds-trigger" -> "cip-sds-trigger-generator";
      case "sftp-trigger-2" -> "cip-sftp-trigger-generator";
      case "mcp-trigger" -> "cip-mcp-trigger-generator";
      case "async-api-trigger" -> DesignPlanProjector.SERVICE_CALL_GENERATOR_SKILL_ID;
      case "http-trigger" -> "cip-http-trigger-endpoint-generator";
      default -> "cip-trigger-generator";
    };
  }

  static String producerForOperation(SemanticNode.Operation operation) {
    if (ChainElementFamilies.isSender(operation.elementType())
        || ChainElementFamilies.isFileTransfer(operation.elementType())) {
      return ownerForDirectOperation(operation.elementType());
    }
    return null;
  }

  private static String triggerCapabilityKey(
      SemanticEntryPoint entry, ChainSemanticRevision revision) {
    for (SemanticNode node : revision.nodes()) {
      if (node.nodeId().equals(entry.triggerNodeId())
          && node instanceof SemanticNode.Trigger trigger) {
        return trigger.capabilityKey();
      }
    }
    return "";
  }

  private static boolean ownerMatches(
      DesignPlanContract.Step step, TargetKey key, String expectedOwner) {
    if (key.kind() == TargetKind.CATALOG_BINDING) {
      return step.owner().kind() == OwnerKind.APIHUB_TOOL
          && BINDING_PRODUCERS.contains(step.owner().id());
    }
    return step.owner().kind() == OwnerKind.SKILL && expectedOwner.equals(step.owner().id());
  }

  private static void validateRequiredSupportOwners(
      DesignPlanContract contract,
      CompilerRunPin pin,
      List<DesignPlanContractFinding> findings) {
    Set<String> selectedOwners =
        contract.steps().stream()
            .filter(step -> step.owner().kind() == OwnerKind.SKILL)
            .map(step -> step.owner().id())
            .collect(java.util.stream.Collectors.toSet());
    Set<String> pinnedOwners =
        pin.resolvedDag().nodes().stream()
            .map(ResolvedCompilerNode::skillId)
            .collect(java.util.stream.Collectors.toSet());
    for (String required : List.of("cip-structure-generator", "cip-chain-assembler")) {
      if (pinnedOwners.contains(required) && !selectedOwners.contains(required)) {
        findings.add(
            finding(
                DesignPlanContractFinding.Code.MISSING_REQUIRED_OWNER,
                null,
                "",
                "",
                "Missing required planning owner " + required));
      }
    }
    Set<String> validationOwners =
        pin.resolvedDag().nodes().stream()
            .filter(
                node ->
                    node.produces().contains("COMPILER_VALIDATION_BUNDLE")
                        || (node.compilerPhase() != null
                            && node.compilerPhase().toLowerCase(java.util.Locale.ROOT)
                                .startsWith("validation")))
            .map(ResolvedCompilerNode::skillId)
            .collect(java.util.stream.Collectors.toSet());
    if (!validationOwners.isEmpty()
        && !selectedOwners.contains(DesignPlanProjector.CHAIN_VALIDATOR_SKILL_ID)
        && selectedOwners.stream().noneMatch(validationOwners::contains)) {
      findings.add(
          finding(
              DesignPlanContractFinding.Code.MISSING_REQUIRED_OWNER,
              null,
              "",
              "",
              "Missing required validation owner"));
    }
  }

  private static void validateDependencies(
      DesignPlanContract contract,
      Set<String> knownSteps,
      List<DesignPlanContractFinding> findings) {
    Map<String, List<String>> graph = new LinkedHashMap<>();
    for (DesignPlanContract.Step step : contract.steps()) {
      graph.putIfAbsent(step.stepId(), step.dependsOnStepIds());
      for (String dependency : new LinkedHashSet<>(step.dependsOnStepIds())) {
        if (step.stepId().equals(dependency)) {
          findings.add(finding(DesignPlanContractFinding.Code.SELF_STEP_DEPENDENCY,
              null, "", step.stepId(), "Step depends on itself"));
        } else if (!knownSteps.contains(dependency)) {
          findings.add(finding(DesignPlanContractFinding.Code.UNKNOWN_STEP_DEPENDENCY,
              null, "", step.stepId(), "Unknown dependency " + dependency));
        }
      }
    }
    Set<String> visited = new HashSet<>();
    Set<String> visiting = new HashSet<>();
    for (String stepId : graph.keySet()) {
      if (cycle(stepId, graph, visiting, visited)) {
        findings.add(finding(DesignPlanContractFinding.Code.STEP_DEPENDENCY_CYCLE,
            null, "", stepId, "Dependency graph contains a cycle involving " + stepId));
        break;
      }
    }
  }

  private static boolean cycle(
      String stepId,
      Map<String, List<String>> graph,
      Set<String> visiting,
      Set<String> visited) {
    if (visited.contains(stepId) || !graph.containsKey(stepId)) {
      return false;
    }
    if (!visiting.add(stepId)) {
      return true;
    }
    for (String dependency : graph.getOrDefault(stepId, List.of())) {
      if (cycle(dependency, graph, visiting, visited)) {
        return true;
      }
    }
    visiting.remove(stepId);
    visited.add(stepId);
    return false;
  }

  private static void validatePinnedDependencies(
      DesignPlanContract contract,
      CompilerRunPin pin,
      List<DesignPlanContractFinding> findings) {
    Map<String, ResolvedCompilerNode> nodes =
        pin.resolvedDag().nodes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ResolvedCompilerNode::skillId, node -> node));
    Map<String, List<String>> stepsByOwner = new HashMap<>();
    Map<String, List<String>> stepDependencies = new HashMap<>();
    for (DesignPlanContract.Step step : contract.steps()) {
      stepDependencies.put(step.stepId(), step.dependsOnStepIds());
      if (step.owner().kind() == OwnerKind.SKILL) {
        stepsByOwner
            .computeIfAbsent(step.owner().id(), ignored -> new ArrayList<>())
            .add(step.stepId());
      }
    }
    for (DesignPlanContract.Step step : contract.steps()) {
      if (step.owner().kind() != OwnerKind.SKILL) {
        continue;
      }
      ResolvedCompilerNode node = nodes.get(step.owner().id());
      if (DesignPlanProjector.CHAIN_VALIDATOR_SKILL_ID.equals(step.owner().id())) {
        requireOwnerAncestor(
            step,
            "cip-chain-assembler",
            stepsByOwner,
            stepDependencies,
            findings);
      }
      if (node == null) {
        continue;
      }
      for (String requiredOwner : node.dependsOn()) {
        requireOwnerAncestor(
            step, requiredOwner, stepsByOwner, stepDependencies, findings);
      }
    }
  }

  private static void requireOwnerAncestor(
      DesignPlanContract.Step step,
      String requiredOwner,
      Map<String, List<String>> stepsByOwner,
      Map<String, List<String>> stepDependencies,
      List<DesignPlanContractFinding> findings) {
    List<String> producerSteps = stepsByOwner.getOrDefault(requiredOwner, List.of());
    Set<String> ancestors = ancestors(step.stepId(), stepDependencies, new HashSet<>());
    if (!producerSteps.isEmpty() && producerSteps.stream().noneMatch(ancestors::contains)) {
      findings.add(
          finding(
              DesignPlanContractFinding.Code.COMPILER_DEPENDENCY_MISSING,
              null,
              "",
              step.stepId(),
              "Step " + step.stepId() + " must depend on a step owned by " + requiredOwner));
    }
  }

  private static Set<String> ancestors(
      String stepId, Map<String, List<String>> graph, Set<String> visited) {
    for (String dependency : graph.getOrDefault(stepId, List.of())) {
      if (visited.add(dependency)) {
        ancestors(dependency, graph, visited);
      }
    }
    return visited;
  }

  private static DesignPlanContractFinding finding(
      DesignPlanContractFinding.Code code,
      TargetKind kind,
      String targetId,
      String stepId,
      String message) {
    return new DesignPlanContractFinding(code, kind, targetId, stepId, true, message);
  }

  private record TargetKey(TargetKind kind, String id) {
    @Override
    public String toString() {
      return kind + " targetId=" + id;
    }
  }
}
