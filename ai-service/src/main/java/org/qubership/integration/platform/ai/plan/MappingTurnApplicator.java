package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Applies typed mapping operations to {@code mappingIntents}. A structural or schema validation
 * failure leaves the previous requirement brief unchanged.
 */
public final class MappingTurnApplicator {

  private MappingTurnApplicator() {}

  public static MappingTurnApplication apply(RequirementBrief brief, MappingTurnResult result) {
    return apply(brief, result, MappingContract.unknown(), MappingContract.unknown());
  }

  public static MappingTurnApplication apply(
      RequirementBrief brief,
      MappingTurnResult result,
      MappingContract sourceContract,
      MappingContract targetContract) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(sourceContract, "sourceContract");
    Objects.requireNonNull(targetContract, "targetContract");
    if (!(result instanceof MappingTurnResult.Changes(var operations))
        || operations.isEmpty()) {
      return MappingTurnApplication.rejected(brief);
    }
    try {
      List<MappingIntent> working = new ArrayList<>(brief.mappingIntents());
      Map<String, MappingIntent> originalById = indexById(brief.mappingIntents());
      for (MappingTurnResult.Operation operation : operations) {
        applyOne(working, brief.flow(), operation);
      }
      List<MappingIntent> classified = classify(working, sourceContract, targetContract);
      requireImmutableBoundaries(originalById, classified);
      RequirementBrief candidate = brief.withMappingIntents(classified);
      if (RequirementBriefCoverageValidator.validateMappingStructure(candidate).isPresent()) {
        return MappingTurnApplication.rejected(brief);
      }
      return MappingTurnApplication.applied(candidate);
    } catch (IllegalArgumentException ex) {
      return MappingTurnApplication.rejected(brief);
    }
  }

  /**
   * Applies each operation on its own. An invalid hop is omitted; valid siblings persist. Used on
   * the mapping-gap card, where whole-list rollback is the wrong product rule.
   */
  public static MappingTurnApplication applyValid(RequirementBrief brief, MappingTurnResult result) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(result, "result");
    if (!(result instanceof MappingTurnResult.Changes(var operations)) || operations.isEmpty()) {
      return MappingTurnApplication.rejected(brief, result);
    }
    RequirementBrief current = brief;
    boolean any = false;
    for (MappingTurnResult.Operation operation : operations) {
      MappingTurnApplication one =
          apply(current, new MappingTurnResult.Changes(List.of(operation)));
      if (one.applied()) {
        current = one.brief();
        any = true;
      }
    }
    return any
        ? new MappingTurnApplication(current, true, null, result)
        : MappingTurnApplication.rejected(brief, result);
  }

  private static void applyOne(
      List<MappingIntent> working, RequirementFlow flow, MappingTurnResult.Operation operation) {
    switch (operation) {
      case AddIntent add -> addIntent(working, flow, add);
      case AddRule add -> addRule(working, add);
      case UpdateRule update -> updateRule(working, update);
      case DeleteRule delete -> deleteRule(working, delete);
      case DeleteIntent delete -> deleteIntent(working, delete);
    }
  }

  private static void addIntent(
      List<MappingIntent> working, RequirementFlow flow, AddIntent add) {
    if (!approvedTransition(flow, add.sourceRef(), add.targetRef())) {
      throw new IllegalArgumentException(
          "Mapping intent uses "
              + add.sourceRef()
              + " -> "
              + add.targetRef()
              + ", which is not an approved flow transition.");
    }
    if (intentAt(working, add.sourceRef(), add.targetRef()) != null) {
      throw new IllegalArgumentException(
          "A mapping intent already covers " + add.sourceRef() + " -> " + add.targetRef());
    }
    if (add.rules().isEmpty()) {
      throw new IllegalArgumentException("add intent requires at least one mapping rule");
    }
    List<MappingIntentRule> rules = userDefined(add.rules());
    requireUniqueTargets(rules);
    MappingIntent drafted =
        new MappingIntent(
            "",
            add.sourceRef(),
            null,
            add.targetRef(),
            null,
            rules,
            add.implementationPreference());
    MappingIntent withPorts =
        RequirementBriefProjector.assignPorts(List.of(drafted), flow).getFirst();
    if (withPorts.sourcePort() == null || withPorts.targetPort() == null) {
      throw new IllegalArgumentException(
          "Runtime could not assign mapping ports for "
              + add.sourceRef()
              + " -> "
              + add.targetRef());
    }
    String mappingIntentId = newIntentId(add.sourceRef(), add.targetRef());
    if (byId(working, mappingIntentId) != null) {
      throw new IllegalArgumentException("mapping intent id already exists: " + mappingIntentId);
    }
    working.add(
        new MappingIntent(
            mappingIntentId,
            withPorts.sourceRef(),
            withPorts.sourcePort(),
            withPorts.targetRef(),
            withPorts.targetPort(),
            withPorts.rules(),
            withPorts.implementationPreference()));
  }

  private static void addRule(List<MappingIntent> working, AddRule add) {
    int index = indexOfId(working, add.mappingIntentId());
    if (index < 0) {
      throw new IllegalArgumentException("unknown mapping intent " + add.mappingIntentId());
    }
    if (add.targetPath().isBlank()) {
      throw new IllegalArgumentException("add rule requires targetPath");
    }
    MappingIntent intent = working.get(index);
    if (!matchingRuleIndexes(intent, add.targetPath()).isEmpty()) {
      throw new IllegalArgumentException(
          "Mapping intent '"
              + add.mappingIntentId()
              + "' already writes "
              + add.targetPath());
    }
    List<MappingIntentRule> rules = new ArrayList<>(intent.rules());
    rules.add(
        new MappingIntentRule(
            add.sourcePath(), add.targetPath(), add.expression(), MappingRuleStatus.USER_DEFINED));
    working.set(index, intent.withRules(rules));
  }

  private static void updateRule(List<MappingIntent> working, UpdateRule update) {
    int index = indexOfId(working, update.mappingIntentId());
    if (index < 0) {
      throw new IllegalArgumentException("unknown mapping intent " + update.mappingIntentId());
    }
    MappingIntent intent = working.get(index);
    List<Integer> matches = matchingRuleIndexes(intent, update.targetPath());
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Mapping intent '"
              + update.mappingIntentId()
              + "' must have exactly one rule for target "
              + update.targetPath());
    }
    MappingIntentRule current = intent.rules().get(matches.getFirst());
    String newTargetPath =
        update.newTargetPath() == null ? current.targetPath() : update.newTargetPath();
    for (int i = 0; i < intent.rules().size(); i++) {
      if (i != matches.getFirst() && sameTarget(intent.rules().get(i).targetPath(), newTargetPath)) {
        throw new IllegalArgumentException(
            "Mapping intent '"
                + update.mappingIntentId()
                + "' already writes "
                + newTargetPath);
      }
    }
    List<MappingIntentRule> rules = new ArrayList<>(intent.rules());
    rules.set(
        matches.getFirst(),
        new MappingIntentRule(
            update.sourcePath(),
            newTargetPath,
            update.expression(),
            MappingRuleStatus.USER_DEFINED));
    working.set(index, intent.withRules(rules));
  }

  private static void deleteRule(List<MappingIntent> working, DeleteRule delete) {
    int index = indexOfId(working, delete.mappingIntentId());
    if (index < 0) {
      throw new IllegalArgumentException("unknown mapping intent " + delete.mappingIntentId());
    }
    MappingIntent intent = working.get(index);
    List<Integer> matches = matchingRuleIndexes(intent, delete.targetPath());
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Mapping intent '"
              + delete.mappingIntentId()
              + "' must have exactly one rule for target "
              + delete.targetPath());
    }
    if (intent.rules().size() <= 1) {
      throw new IllegalArgumentException(
          "Deleting the last rule does not convert the transition to pass-through");
    }
    List<MappingIntentRule> rules = new ArrayList<>(intent.rules());
    rules.remove((int) matches.getFirst());
    working.set(index, intent.withRules(rules));
  }

  private static void deleteIntent(List<MappingIntent> working, DeleteIntent delete) {
    int index = indexOfId(working, delete.mappingIntentId());
    if (index < 0) {
      throw new IllegalArgumentException("unknown mapping intent " + delete.mappingIntentId());
    }
    working.remove(index);
  }

  private static List<MappingIntent> classify(
      List<MappingIntent> intents,
      MappingContract sourceContract,
      MappingContract targetContract) {
    List<MappingIntent> classified = new ArrayList<>();
    for (MappingIntent intent : intents) {
      Optional<MappingIntent> validated =
          BriefMappingValidator.validateBoundary(
              intent.mappingIntentId(),
              intent.sourceRef(),
              intent.sourcePort(),
              intent.targetRef(),
              intent.targetPort(),
              intent.rules(),
              sourceContract,
              targetContract,
              intent.implementationPreference());
      if (validated.isEmpty()) {
        continue;
      }
      MappingIntent next = validated.get();
      requireUniqueTargets(next.rules());
      classified.add(next);
    }
    return List.copyOf(classified);
  }

  private static void requireImmutableBoundaries(
      Map<String, MappingIntent> originalById, List<MappingIntent> current) {
    for (MappingIntent intent : current) {
      MappingIntent original = originalById.get(intent.mappingIntentId());
      if (original == null) {
        continue;
      }
      if (!original.sourceRef().equals(intent.sourceRef())
          || original.sourcePort() != intent.sourcePort()
          || !original.targetRef().equals(intent.targetRef())
          || original.targetPort() != intent.targetPort()) {
        throw new IllegalArgumentException(
            "Source occurrence, source port, target occurrence, and target port are immutable for"
                + " mapping intent "
                + intent.mappingIntentId());
      }
    }
  }

  private static List<MappingIntentRule> userDefined(List<MappingIntentRule> rules) {
    List<MappingIntentRule> userRules = new ArrayList<>(rules.size());
    for (MappingIntentRule rule : rules) {
      if (rule == null || rule.targetPath().isBlank()) {
        throw new IllegalArgumentException("mapping rule is missing targetPath");
      }
      userRules.add(
          new MappingIntentRule(
              rule.sourcePath(),
              rule.targetPath(),
              rule.expression(),
              MappingRuleStatus.USER_DEFINED));
    }
    return List.copyOf(userRules);
  }

  private static void requireUniqueTargets(List<MappingIntentRule> rules) {
    Set<String> seen = new LinkedHashSet<>();
    for (MappingIntentRule rule : rules) {
      if (rule != null && rule.status() != MappingRuleStatus.UNRESOLVED) {
        String target = MappingContract.canonicalPath(rule.targetPath());
        if (!target.isBlank() && !seen.add(target)) {
          throw new IllegalArgumentException("Two active rules write target path " + target);
        }
      }
    }
  }

  private static boolean approvedTransition(
      RequirementFlow flow, String sourceRef, String targetRef) {
    if (flow == null || sourceRef.isBlank() || targetRef.isBlank()) {
      return false;
    }
    if (flow.interaction(sourceRef).isEmpty() || flow.interaction(targetRef).isEmpty()) {
      return false;
    }
    for (Transition transition : flow.transitions()) {
      if (sourceRef.equals(transition.sourceInteractionId())
          && targetRef.equals(transition.targetInteractionId())) {
        return true;
      }
    }
    return false;
  }

  private static String newIntentId(String sourceRef, String targetRef) {
    return "map-" + sourceRef + "-to-" + targetRef;
  }

  private static Map<String, MappingIntent> indexById(List<MappingIntent> intents) {
    Map<String, MappingIntent> byId = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      if (intent != null && !intent.mappingIntentId().isBlank()) {
        byId.put(intent.mappingIntentId(), intent);
      }
    }
    return byId;
  }

  private static MappingIntent intentAt(
      List<MappingIntent> intents, String sourceRef, String targetRef) {
    for (MappingIntent intent : intents) {
      if (sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef())) {
        return intent;
      }
    }
    return null;
  }

  private static MappingIntent byId(List<MappingIntent> intents, String mappingIntentId) {
    int index = indexOfId(intents, mappingIntentId);
    return index < 0 ? null : intents.get(index);
  }

  private static int indexOfId(List<MappingIntent> intents, String mappingIntentId) {
    for (int i = 0; i < intents.size(); i++) {
      if (intents.get(i).mappingIntentId().equals(mappingIntentId)) {
        return i;
      }
    }
    return -1;
  }

  private static List<Integer> matchingRuleIndexes(MappingIntent intent, String targetPath) {
    List<Integer> matches = new ArrayList<>();
    for (int i = 0; i < intent.rules().size(); i++) {
      if (sameTarget(intent.rules().get(i).targetPath(), targetPath)) {
        matches.add(i);
      }
    }
    return matches;
  }

  private static boolean sameTarget(String left, String right) {
    return MappingContract.canonicalPath(left).equals(MappingContract.canonicalPath(right));
  }
}
