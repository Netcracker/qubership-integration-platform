package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.MappingContractEvaluation;
import org.qubership.integration.platform.ai.plan.mapping.MappingFindingCode;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;
import org.qubership.integration.platform.ai.plan.mapping.MappingRuleFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Validates one source-port to target-port mapping boundary. Pass-through is the absence of a
 * {@link MappingIntent}; this module never synthesizes pass-through rows.
 */
public final class BriefMappingValidator {

  public static final String UNRESOLVED_REQUIRED_PREFIX = "Unresolved required target field ";

  private BriefMappingValidator() {}

  /**
   * Mapping may sit only on a site with exactly one incoming execution edge. Reconvergence is
   * not a mapping endpoint while generic aggregate is unsupported.
   */
  public static boolean isMappingEndpoint(int incomingExecutionEdges, boolean reconvergence) {
    return incomingExecutionEdges == 1 && !reconvergence;
  }

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
    return validateBoundary(
        mappingIntentId,
        sourceRef,
        sourcePort,
        targetRef,
        targetPort,
        candidates,
        sourceContract,
        targetContract,
        null);
  }

  public static Optional<MappingIntent> validateBoundary(
      String mappingIntentId,
      String sourceRef,
      MappingPort sourcePort,
      String targetRef,
      MappingPort targetPort,
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract,
      String implementationPreference) {
    Objects.requireNonNull(sourcePort, "sourcePort");
    Objects.requireNonNull(targetPort, "targetPort");
    MappingContractEvaluation evaluated =
        evaluateBoundary(
            mappingIntentId,
            sourceRef,
            sourcePort,
            targetRef,
            targetPort,
            candidates,
            sourceContract,
            targetContract,
            implementationPreference);
    return evaluated.intent();
  }

  /**
   * Classifies candidate rules and returns structured findings from that same evaluation. Empty
   * intent means identity-only AUTO pass-through.
   */
  public static MappingContractEvaluation evaluateBoundary(
      String mappingIntentId,
      String sourceRef,
      MappingPort sourcePort,
      String targetRef,
      MappingPort targetPort,
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract,
      String implementationPreference) {
    Objects.requireNonNull(sourcePort, "sourcePort");
    Objects.requireNonNull(targetPort, "targetPort");
    MappingContract source = sourceContract == null ? MappingContract.unknown() : sourceContract;
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    List<MappingIntentRule> classified =
        classify(candidates, source, target, implementationPreference);
    if (isIdentityOnlyAuto(classified)) {
      return MappingContractEvaluation.passThrough();
    }
    MappingIntent intent =
        new MappingIntent(
            mappingIntentId,
            sourceRef,
            sourcePort,
            targetRef,
            targetPort,
            classified,
            implementationPreference);
    return new MappingContractEvaluation(
        Optional.of(intent),
        MappingContractEvaluation.sorted(
            findingsFor(intent, source, target, implementationPreference)));
  }

  public static List<MappingIntentRule> classify(
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract) {
    return classify(candidates, sourceContract, targetContract, null);
  }

  private static List<MappingIntentRule> classify(
      List<MappingIntentRule> candidates,
      MappingContract sourceContract,
      MappingContract targetContract,
      String implementationPreference) {
    MappingContract source = sourceContract == null ? MappingContract.unknown() : sourceContract;
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    boolean scriptPreferred = MappingMechanismSelector.isScriptPreference(implementationPreference);
    boolean allowOffHopSource =
        MappingMechanismSelector.allowsOffHopSource(implementationPreference);
    List<MappingIntentRule> input = expandCommaSeparatedRules(candidates);
    Map<String, MappingIntentRule> byTarget = new LinkedHashMap<>();
    for (MappingIntentRule candidate : input) {
      if (candidate == null || candidate.targetPath().isBlank()) {
        continue;
      }
      MappingIntentRule normalized = canonicalRule(candidate);
      byTarget.put(
          normalized.targetPath(),
          classifyOne(normalized, source, target, scriptPreferred, allowOffHopSource));
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

  /**
   * Structured findings for a classified intent against the contracts used in that evaluation.
   * Callers that already hold schema sides should go through {@link
   * org.qubership.integration.platform.ai.plan.mapping.MappingContractGate}.
   */
  public static List<MappingRuleFinding> findingsFor(
      MappingIntent intent,
      MappingContract sourceContract,
      MappingContract targetContract,
      String implementationPreference) {
    if (intent == null) {
      return List.of();
    }
    MappingContract source = sourceContract == null ? MappingContract.unknown() : sourceContract;
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    boolean scriptPreferred = MappingMechanismSelector.isScriptPreference(implementationPreference);
    boolean allowOffHopSource =
        MappingMechanismSelector.allowsOffHopSource(implementationPreference);
    List<MappingRuleFinding> findings = new ArrayList<>();
    for (MappingIntentRule rule : intent.rules()) {
      if (rule == null || rule.targetPath().isBlank()) {
        continue;
      }
      MappingFindingCode reason =
          reasonFor(rule, source, target, scriptPreferred, allowOffHopSource);
      if (reason != null) {
        findings.add(finding(reason, intent, rule, source, target));
      }
    }
    return findings;
  }

  private static MappingFindingCode reasonFor(
      MappingIntentRule rule,
      MappingContract source,
      MappingContract target,
      boolean scriptPreferred,
      boolean allowOffHopSource) {
    if (rule.status() != MappingRuleStatus.UNRESOLVED) {
      return null;
    }
    if (source.known()
        && !allowOffHopSource
        && source.field(rule.sourcePath()).isEmpty()
        && !rule.sourcePath().isBlank()) {
      return MappingFindingCode.MAPPING_INVALID_SOURCE;
    }
    if (target.known() && target.field(rule.targetPath()).isEmpty()) {
      return MappingFindingCode.MAPPING_UNKNOWN_TARGET;
    }
    if (rule.expression() != null && !expressionSupported(rule.expression(), scriptPreferred)) {
      return MappingFindingCode.MAPPING_UNSUPPORTED_EXPRESSION;
    }
    if (target.known()
        && target.field(rule.targetPath()).filter(MappingContract.Field::required).isPresent()) {
      return MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET;
    }
    return MappingFindingCode.MAPPING_UNRESOLVED_RULE;
  }

  private static MappingRuleFinding finding(
      MappingFindingCode code,
      MappingIntent intent,
      MappingIntentRule rule,
      MappingContract source,
      MappingContract target) {
    String targetPath = MappingContract.canonicalPath(rule.targetPath());
    String sourcePath = MappingContract.canonicalPath(rule.sourcePath());
    String expected = expectedContract(code, target, source, targetPath);
    String observed = observed(code, rule, targetPath, sourcePath);
    return new MappingRuleFinding(
        code,
        messageFor(code, targetPath, intent.mappingIntentId()),
        true,
        intent.mappingIntentId(),
        intent.sourceRef(),
        intent.sourcePort(),
        intent.targetRef(),
        intent.targetPort(),
        sourcePath,
        targetPath,
        rule.expression() == null ? "" : rule.expression(),
        rule.status() == null ? "" : rule.status().name(),
        expected,
        observed);
  }

  private static String expectedContract(
      MappingFindingCode code, MappingContract target, MappingContract source, String targetPath) {
    if (code == MappingFindingCode.MAPPING_UNKNOWN_TARGET && target.known()) {
      return "Target contract does not declare " + targetPath + ". Known fields: " + fieldList(target);
    }
    if (code == MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET && target.known()) {
      return "Required target field " + targetPath + " must have a supplying rule.";
    }
    if (code == MappingFindingCode.MAPPING_INVALID_SOURCE && source.known()) {
      return "Source must be a field on this hop or a supported context read. Known fields: "
          + fieldList(source);
    }
    if (code == MappingFindingCode.MAPPING_UNSUPPORTED_EXPRESSION) {
      return "Expression must stay within the selected mapping mechanism policy.";
    }
    return "";
  }

  private static String observed(
      MappingFindingCode code, MappingIntentRule rule, String targetPath, String sourcePath) {
    if (code == MappingFindingCode.MAPPING_UNKNOWN_TARGET) {
      return "Rule writes " + targetPath;
    }
    if (code == MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET) {
      return "No supplying rule for " + targetPath;
    }
    if (code == MappingFindingCode.MAPPING_INVALID_SOURCE) {
      return "Source path " + sourcePath;
    }
    if (code == MappingFindingCode.MAPPING_UNSUPPORTED_EXPRESSION) {
      return "Expression " + (rule.expression() == null ? "" : rule.expression());
    }
    return "Unresolved rule for " + targetPath;
  }

  private static String fieldList(MappingContract contract) {
    if (!contract.known() || contract.fields().isEmpty()) {
      return "(none)";
    }
    StringBuilder text = new StringBuilder();
    int count = 0;
    for (MappingContract.Field field : contract.fields()) {
      if (count >= 12) {
        text.append(", …");
        break;
      }
      if (count > 0) {
        text.append(", ");
      }
      text.append(MappingContract.canonicalPath(field.path()));
      count++;
    }
    return text.toString();
  }

  private static String messageFor(MappingFindingCode code, String targetPath, String mappingIntentId) {
    String intent = mappingIntentId == null || mappingIntentId.isBlank() ? "" : mappingIntentId;
    return switch (code) {
      case MAPPING_UNKNOWN_TARGET ->
          "Target path "
              + targetPath
              + " is absent from the target contract"
              + (intent.isBlank() ? "." : " of mapping intent '" + intent + "'.")
              + " Correct the target or represent context with exchange properties, not as an API"
              + " field.";
      case MAPPING_MISSING_REQUIRED_TARGET ->
          UNRESOLVED_REQUIRED_PREFIX
              + targetPath
              + ". Map each required target from a source field, constant, or default before"
              + " approving the brief.";
      case MAPPING_INVALID_SOURCE ->
          "Source path is not valid for this mapping mechanism"
              + (intent.isBlank() ? "." : " in mapping intent '" + intent + "'.")
              + " Correct the source or use the supported context mechanism.";
      case MAPPING_UNSUPPORTED_EXPRESSION ->
          "Expression is not supported by the selected mapping mechanism"
              + (intent.isBlank() ? "." : " in mapping intent '" + intent + "'.")
              + " Rewrite the expression within that policy.";
      case MAPPING_UNRESOLVED_RULE ->
          "Mapping rule for "
              + targetPath
              + " is unresolved"
              + (intent.isBlank() ? "." : " in mapping intent '" + intent + "'.")
              + " Preserve the uncertainty; do not invent a missing required field.";
    };
  }

  /** Inverse of {@link #messageFor} for {@link MappingFindingCode#MAPPING_UNKNOWN_TARGET}. */
  public static Optional<String> unknownTargetPathFromMessage(String message) {
    if (message == null) {
      return Optional.empty();
    }
    String prefix = "Target path ";
    String marker = " is absent from the target contract";
    int start = message.indexOf(prefix);
    int end = message.indexOf(marker);
    if (start < 0 || end < 0 || end <= start) {
      return Optional.empty();
    }
    String path = message.substring(start + prefix.length(), end).trim();
    return path.isBlank() ? Optional.empty() : Optional.of(path);
  }

  private static MappingIntentRule classifyOne(
      MappingIntentRule candidate,
      MappingContract source,
      MappingContract target,
      boolean scriptPreferred,
      boolean allowOffHopSource) {
    if (candidate.status() == MappingRuleStatus.USER_DEFINED) {
        return validateKnownPaths(candidate, scriptPreferred);
    }
    if (candidate.status() == MappingRuleStatus.UNRESOLVED) {
      return candidate;
    }
    if (!source.known() && !target.known()) {
      return candidate.withStatus(inferStatus(candidate));
    }
    if (source.known()
        && !allowOffHopSource
        && source.field(candidate.sourcePath()).isEmpty()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (target.known() && !target.field(candidate.targetPath()).isPresent()) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    if (candidate.expression() != null && !expressionSupported(candidate.expression(), scriptPreferred)) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    MappingRuleStatus status = inferStatus(candidate);
    if (status == MappingRuleStatus.AUTO && !typesCompatible(candidate, source, target)) {
      return candidate.withStatus(MappingRuleStatus.PROPOSED);
    }
    return candidate.withStatus(status);
  }

  private static MappingIntentRule validateKnownPaths(
      MappingIntentRule candidate, boolean scriptPreferred) {
    if (candidate.expression() != null && !expressionSupported(candidate.expression(), scriptPreferred)) {
      return candidate.withStatus(MappingRuleStatus.UNRESOLVED);
    }
    return candidate;
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
   * One captured rule may echo several fields as a comma-separated list. Equal-arity lists become
   * one rule per target so required coverage matches JSON Schema field paths.
   */
  private static List<MappingIntentRule> expandCommaSeparatedRules(
      List<MappingIntentRule> candidates) {
    List<MappingIntentRule> input = candidates == null ? List.of() : candidates;
    List<MappingIntentRule> expanded = new ArrayList<>();
    for (MappingIntentRule candidate : input) {
      if (candidate == null) {
        continue;
      }
      List<String> sources = MappingContract.commaSeparatedFieldNames(candidate.sourcePath());
      List<String> targets = MappingContract.commaSeparatedFieldNames(candidate.targetPath());
      if (sources.size() >= 2 && sources.size() == targets.size()) {
        for (int i = 0; i < targets.size(); i++) {
          expanded.add(
              new MappingIntentRule(
                  sources.get(i), targets.get(i), candidate.expression(), candidate.status()));
        }
      } else {
        expanded.add(candidate);
      }
    }
    return expanded;
  }

  private static MappingIntentRule canonicalRule(MappingIntentRule candidate) {
    return new MappingIntentRule(
        MappingContract.canonicalPath(candidate.sourcePath()),
        MappingContract.canonicalPath(candidate.targetPath()),
        candidate.expression(),
        candidate.status());
  }

  /**
   * Non-blank expressions stay unresolved only when mapper-2 is on and SCRIPT generation cannot
   * write that expression.
   */
  private static boolean expressionSupported(String expression, boolean scriptPreferred) {
    if (MappingMechanismSelector.scriptAcceptsExpression(expression)) {
      return true;
    }
    return scriptPreferred && MappingMechanismSelector.isSupportedScriptExpression(expression);
  }
}
