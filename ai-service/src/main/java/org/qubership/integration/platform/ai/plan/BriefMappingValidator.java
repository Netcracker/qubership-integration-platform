package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Validates one source-port to target-port mapping boundary. Pass-through is the absence of a
 * {@link MappingIntent}; this module never synthesizes pass-through rows.
 */
public final class BriefMappingValidator {

  public static final String UNRESOLVED_REQUIRED_PREFIX = "Unresolved required target field ";

  private BriefMappingValidator() {}

  /**
   * Classifies candidate rules against known contracts. Returns empty when the boundary is
   * identity-only AUTO and has no other rules.
   */
  public static Optional<MappingIntent> validateBoundary(
      String mappingIntentId,
      String sourceRef,
      MappingPort sourcePort,
      String targetRef,
      MappingPort targetPort,
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract) {
    Objects.requireNonNull(sourcePort, "sourcePort");
    Objects.requireNonNull(targetPort, "targetPort");
    MappingContract source = sourceContract == null ? MappingContract.unknown() : sourceContract;
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    List<MappingIntentRule> classified = classify(candidates, source, target);
    if (isIdentityOnlyAuto(classified)) {
      return Optional.empty();
    }
    return Optional.of(
        new MappingIntent(
            mappingIntentId, sourceRef, sourcePort, targetRef, targetPort, classified));
  }

  public static List<MappingIntentRule> classifyFromLegacy(
      List<RequirementDataMapping.Rule> rules) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    List<MappingIntentRule> candidates = new ArrayList<>();
    for (RequirementDataMapping.Rule rule : rules) {
      if (rule == null) {
        continue;
      }
      candidates.add(MappingIntentRule.fromLegacy(rule, inferStatus(rule)));
    }
    return classify(candidates, MappingContract.unknown(), MappingContract.unknown());
  }

  public static List<MappingIntentRule> classify(
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract) {
    MappingContract source = sourceContract == null ? MappingContract.unknown() : sourceContract;
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    List<MappingIntentRule> input = candidates == null ? List.of() : candidates;
    Map<String, MappingIntentRule> byTarget = new LinkedHashMap<>();
    for (MappingIntentRule candidate : input) {
      if (candidate == null || candidate.targetPath().isBlank()) {
        continue;
      }
      byTarget.put(candidate.targetPath(), classifyOne(candidate, source, target));
    }
    if (target.known()) {
      for (MappingContract.Field field : target.fields()) {
        if (!field.required() || byTarget.containsKey(field.path())) {
          continue;
        }
        byTarget.put(
            field.path(),
            new MappingIntentRule("", field.path(), null, MappingRuleStatus.UNRESOLVED));
      }
    }
    return List.copyOf(byTarget.values());
  }

  public static boolean isIdentityOnlyAuto(List<MappingIntentRule> rules) {
    if (rules == null || rules.isEmpty()) {
      return true;
    }
    for (MappingIntentRule rule : rules) {
      if (rule.status() != MappingRuleStatus.AUTO || !rule.identityCopy()) {
        return false;
      }
    }
    return true;
  }

  public static List<String> unresolvedRequiredTargets(RequirementBrief brief) {
    if (brief == null || brief.mappingIntents().isEmpty()) {
      return List.of();
    }
    Set<String> unresolved = new LinkedHashSet<>();
    for (MappingIntent intent : brief.mappingIntents()) {
      for (MappingIntentRule rule : intent.rules()) {
        if (rule.status() == MappingRuleStatus.UNRESOLVED && !rule.targetPath().isBlank()) {
          unresolved.add(rule.targetPath());
        }
      }
    }
    return List.copyOf(unresolved);
  }

  public static Optional<String> unresolvedRequiredMessage(RequirementBrief brief) {
    List<String> targets = unresolvedRequiredTargets(brief);
    if (targets.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        UNRESOLVED_REQUIRED_PREFIX
            + String.join(", ", targets)
            + ". Map each required target from a source field, constant, or default before"
            + " approving the brief.");
  }

  public static boolean blocksApproval(RequirementBrief brief) {
    return unresolvedRequiredMessage(brief).isPresent();
  }

  private static MappingIntentRule classifyOne(
      MappingIntentRule candidate, MappingContract source, MappingContract target) {
    if (candidate.status() == MappingRuleStatus.USER_DEFINED) {
      return validateKnownPaths(candidate, source, target);
    }
    if (candidate.status() == MappingRuleStatus.UNRESOLVED) {
      return candidate;
    }
    if (!source.known() && !target.known()) {
      return candidate.withStatus(inferStatus(candidate));
    }
    if (source.known() && !source.field(candidate.sourcePath()).isPresent()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (target.known() && !target.field(candidate.targetPath()).isPresent()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (candidate.expression() != null && !expressionSupported(candidate.expression())) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    MappingRuleStatus status = inferStatus(candidate);
    if (status == MappingRuleStatus.AUTO && !typesCompatible(candidate, source, target)) {
      return candidate.withStatus(MappingRuleStatus.PROPOSED);
    }
    return candidate.withStatus(status);
  }

  private static MappingIntentRule validateKnownPaths(
      MappingIntentRule candidate, MappingContract source, MappingContract target) {
    if (source.known() && !candidate.sourcePath().isBlank()
        && !source.field(candidate.sourcePath()).isPresent()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (target.known() && !target.field(candidate.targetPath()).isPresent()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (candidate.expression() != null && !expressionSupported(candidate.expression())) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    return candidate;
  }

  private static MappingRuleStatus inferStatus(RequirementDataMapping.Rule rule) {
    if (rule == null) {
      return MappingRuleStatus.PROPOSED;
    }
    return inferStatus(rule.sourcePath(), rule.targetPath(), rule.expression());
  }

  private static MappingRuleStatus inferStatus(MappingIntentRule rule) {
    if (rule.status() == MappingRuleStatus.AUTO
        || rule.status() == MappingRuleStatus.PROPOSED) {
      return inferStatus(rule.sourcePath(), rule.targetPath(), rule.expression());
    }
    return rule.status();
  }

  private static MappingRuleStatus inferStatus(
      String sourcePath, String targetPath, String expression) {
    if (expression == null
        && sourcePath != null
        && !sourcePath.isBlank()
        && sourcePath.equals(targetPath)) {
      return MappingRuleStatus.AUTO;
    }
    return MappingRuleStatus.PROPOSED;
  }

  private static boolean typesCompatible(
      MappingIntentRule rule, MappingContract source, MappingContract target) {
    if (!source.known() || !target.known()) {
      return true;
    }
    Optional<MappingContract.Field> sourceField = source.field(rule.sourcePath());
    Optional<MappingContract.Field> targetField = target.field(rule.targetPath());
    if (sourceField.isEmpty() || targetField.isEmpty()) {
      return false;
    }
    String sourceType = sourceField.get().type();
    String targetType = targetField.get().type();
    return sourceType.isBlank() || targetType.isBlank() || sourceType.equalsIgnoreCase(targetType);
  }

  /**
   * Ticket 04 only proves that unsupported expressions stay unresolved. Ticket 06 owns SCRIPT
   * generation; identity copies have no expression.
   */
  private static boolean expressionSupported(String expression) {
    return expression == null || expression.isBlank();
  }
}
