package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Review and approval for mapping intents on a requirement brief. One brief approval confirms
 * remaining {@link MappingRuleStatus#PROPOSED} rules; there is no per-rule approval card.
 */
public final class BriefMappingReview {

  public static final String REOPEN_MESSAGE =
      "The requirement brief reopened because an approved mapping changed. Dependent plan steps"
          + " must be rebuilt.";

  private BriefMappingReview() {}

  public static RequirementBrief editRule(
      RequirementBrief brief,
      String mappingIntentId,
      String targetPath,
      String sourcePath,
      String expression) {
    Objects.requireNonNull(brief, "brief");
    List<MappingIntent> updated = new ArrayList<>();
    boolean found = false;
    for (MappingIntent intent : brief.mappingIntents()) {
      if (!intent.mappingIntentId().equals(mappingIntentId)) {
        updated.add(intent);
        continue;
      }
      List<MappingIntentRule> rules = new ArrayList<>();
      for (MappingIntentRule rule : intent.rules()) {
        if (rule.targetPath().equals(targetPath == null ? "" : targetPath.trim())) {
          found = true;
          rules.add(
              new MappingIntentRule(
                  sourcePath, targetPath, expression, MappingRuleStatus.USER_DEFINED));
        } else {
          rules.add(rule);
        }
      }
      if (!found) {
        found = true;
        rules.add(
            new MappingIntentRule(
                sourcePath, targetPath, expression, MappingRuleStatus.USER_DEFINED));
      }
      updated.add(intent.withRules(rules));
    }
    if (!found) {
      throw new IllegalArgumentException(
          "Mapping intent '" + mappingIntentId + "' has no rule for target " + targetPath);
    }
    return brief.withMappingIntents(updated);
  }

  /**
   * Confirms remaining PROPOSED rules as part of brief approval. Status stays PROPOSED (origin);
   * UNRESOLVED required targets keep the brief blocked.
   */
  public static RequirementBrief confirmProposedOnApproval(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    BriefMappingValidator.unresolvedRequiredMessage(brief)
        .ifPresent(
            message -> {
              throw new IllegalStateException(message);
            });
    return brief;
  }

  public static MappingChangeImpact afterApprovedMappingChange(
      RequirementBrief approved, RequirementBrief updated, DesignExecutionPlan plan) {
    Objects.requireNonNull(approved, "approved");
    Objects.requireNonNull(updated, "updated");
    if (approved.mappingIntents().equals(updated.mappingIntents())) {
      return new MappingChangeImpact(updated, false, List.of());
    }
    Set<String> changedIds = changedIntentIds(approved, updated);
    List<String> invalidated = invalidatedPlanStepIds(plan, changedIds);
    return new MappingChangeImpact(updated, true, invalidated);
  }

  public static List<String> invalidatedPlanStepIds(
      DesignExecutionPlan plan, Set<String> changedMappingIntentIds) {
    if (plan == null || changedMappingIntentIds == null || changedMappingIntentIds.isEmpty()) {
      return List.of();
    }
    List<String> stepIds = new ArrayList<>();
    for (DesignExecutionPlan.Step step : plan.steps()) {
      if (dependsOnChangedIntent(step, changedMappingIntentIds)) {
        stepIds.add(step.stepId());
      }
    }
    return List.copyOf(stepIds);
  }

  private static Set<String> changedIntentIds(RequirementBrief approved, RequirementBrief updated) {
    Set<String> changed = new LinkedHashSet<>();
    for (MappingIntent intent : updated.mappingIntents()) {
      MappingIntent previous = intentById(approved, intent.mappingIntentId());
      if (previous == null || !previous.equals(intent)) {
        changed.add(intent.mappingIntentId());
      }
    }
    for (MappingIntent intent : approved.mappingIntents()) {
      if (intentById(updated, intent.mappingIntentId()) == null) {
        changed.add(intent.mappingIntentId());
      }
    }
    return changed;
  }

  private static MappingIntent intentById(RequirementBrief brief, String mappingIntentId) {
    for (MappingIntent intent : brief.mappingIntents()) {
      if (intent.mappingIntentId().equals(mappingIntentId)) {
        return intent;
      }
    }
    return null;
  }

  private static boolean dependsOnChangedIntent(
      DesignExecutionPlan.Step step, Set<String> changedMappingIntentIds) {
    String report = step.reportText() == null ? "" : step.reportText();
    String stepId = step.stepId() == null ? "" : step.stepId();
    for (String mappingIntentId : changedMappingIntentIds) {
      if (report.contains(mappingIntentId) || stepId.contains(mappingIntentId)) {
        return true;
      }
    }
    return ownsTransformForChangedIntent(step, changedMappingIntentIds);
  }

  /**
   * Transform and script plan steps depend on every mapping intent until ticket 05 attaches
   * {@code mappingIntentId} to the execution site.
   */
  private static boolean ownsTransformForChangedIntent(
      DesignExecutionPlan.Step step, Set<String> changedMappingIntentIds) {
    if (changedMappingIntentIds.isEmpty()) {
      return false;
    }
    List<String> owners = step.owningSkillIds();
    return owners.contains("cip-script-generator")
        || owners.contains("cip-transformation-generator");
  }

  public record MappingChangeImpact(
      RequirementBrief brief, boolean briefReopened, List<String> invalidatedPlanStepIds) {

    public MappingChangeImpact {
      invalidatedPlanStepIds =
          invalidatedPlanStepIds == null ? List.of() : List.copyOf(invalidatedPlanStepIds);
    }
  }
}
