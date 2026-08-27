package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One field copy, constant, default, or expression inside a {@link MappingIntent}. Status is
 * assigned by runtime validation, not by the LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingIntentRule(
    String sourcePath, String targetPath, String expression, MappingRuleStatus status) {

  public MappingIntentRule {
    sourcePath = sourcePath == null ? "" : sourcePath.trim();
    targetPath = targetPath == null ? "" : targetPath.trim();
    expression = expression == null || expression.isBlank() ? null : expression.trim();
    status = status == null ? MappingRuleStatus.PROPOSED : status;
  }

  public MappingIntentRule(String sourcePath, String targetPath, String expression) {
    this(sourcePath, targetPath, expression, MappingRuleStatus.PROPOSED);
  }

  public static MappingIntentRule fromLegacy(
      RequirementDataMapping.Rule rule, MappingRuleStatus status) {
    if (rule == null) {
      return new MappingIntentRule("", "", null, status);
    }
    return new MappingIntentRule(rule.sourcePath(), rule.targetPath(), rule.expression(), status);
  }

  public MappingIntentRule withStatus(MappingRuleStatus newStatus) {
    return new MappingIntentRule(sourcePath, targetPath, expression, newStatus);
  }

  public RequirementDataMapping.Rule toLegacy() {
    return new RequirementDataMapping.Rule(sourcePath, targetPath, expression);
  }

  public boolean identityCopy() {
    return expression == null && !sourcePath.isBlank() && sourcePath.equals(targetPath);
  }
}
