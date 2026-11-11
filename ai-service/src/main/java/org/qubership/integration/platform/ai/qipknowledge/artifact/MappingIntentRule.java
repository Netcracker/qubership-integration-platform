package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;

/**
 * An interpreted requirement. A rule uses either typed operands or the descriptive form,
 * never both. Validation owns its status.
 */
public record MappingIntentRule(
    String id,
    List<String> requirementIds,
    String targetPath,
    @JsonInclude(JsonInclude.Include.NON_NULL) MappingValue value,
    MappingCondition condition,
    MappingRuleStatus status,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MappingFieldRef> fieldRefs,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MappingConstant> constants,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String behavior,
    @JsonInclude(JsonInclude.Include.NON_NULL) String missingSourcePolicy,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> schemaPins) {

  public MappingIntentRule {
    id = id == null ? "" : id.trim();
    requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
    targetPath = targetPath == null ? "" : targetPath.trim();
    condition = condition == null ? MappingCondition.always() : condition;
    status = status == null ? MappingRuleStatus.PROPOSED : status;
    fieldRefs = fieldRefs == null ? List.of() : List.copyOf(fieldRefs);
    constants = constants == null ? List.of() : List.copyOf(constants);
    behavior = behavior == null ? "" : behavior;
    schemaPins = schemaPins == null ? null : Collections.unmodifiableMap(new TreeMap<>(schemaPins));
    if (missingSourcePolicy != null && !"ERROR".equals(missingSourcePolicy)) {
      throw new IllegalArgumentException("Unsupported missing source policy: " + missingSourcePolicy);
    }
    if (value != null && (!fieldRefs.isEmpty() || !constants.isEmpty() || !behavior.isBlank())) {
      throw new IllegalArgumentException(
          "A mapping rule has one representation. Remove the typed value or the descriptive sources, constants, and behavior.");
    }
  }

  public MappingIntentRule(String id, List<String> requirementIds, String targetPath, MappingValue value,
      MappingCondition condition, MappingRuleStatus status, List<MappingFieldRef> fieldRefs,
      List<MappingConstant> constants, String behavior, String missingSourcePolicy) {
    this(id, requirementIds, targetPath, value, condition, status, fieldRefs, constants, behavior,
        missingSourcePolicy, null);
  }

  public MappingIntentRule(String id, List<String> requirementIds, String targetPath, MappingValue value,
      MappingCondition condition, MappingRuleStatus status, List<MappingFieldRef> fieldRefs,
      List<MappingConstant> constants, String behavior) {
    this(id, requirementIds, targetPath, value, condition, status, fieldRefs, constants, behavior, null);
  }

  public MappingIntentRule(
      String id,
      List<String> requirementIds,
      String targetPath,
      MappingValue value,
      MappingCondition condition,
      MappingRuleStatus status) {
    this(id, requirementIds, targetPath, value, condition, status, List.of(), List.of(), "");
  }

  /** Blank expression stays a direct copy. Prose is behavior plus structured field references. */
  public MappingIntentRule(String sourcePath, String targetPath, String expression,
      MappingRuleStatus status) {
    this(
        "",
        List.of(),
        targetPath,
        directCopy(sourcePath, expression),
        MappingCondition.always(),
        status,
        describedFields(sourcePath, expression),
        List.of(),
        describedBehavior(expression));
  }

  private static MappingValue directCopy(String sourcePath, String expression) {
    if (expression != null && !expression.isBlank()) {
      return null;
    }
    if (sourcePath == null || sourcePath.isBlank()) {
      return null;
    }
    String trimmed = sourcePath.trim();
    if (quotedConstant(trimmed)) {
      return new MappingValue.Copy(
          new MappingSource.Constant(
              com.fasterxml.jackson.databind.node.TextNode.valueOf(
                  trimmed.substring(1, trimmed.length() - 1))));
    }
    return new MappingValue.Copy(new MappingSource.Message("", null, trimmed));
  }

  private static List<MappingFieldRef> describedFields(String sourcePath, String expression) {
    if (expression == null || expression.isBlank() || sourcePath == null || sourcePath.isBlank()) {
      return List.of();
    }
    if (quotedConstant(sourcePath.trim())) {
      return List.of();
    }
    return List.of(new MappingFieldRef("", "", MappingContract.canonicalPath(sourcePath), ""));
  }

  private static boolean quotedConstant(String sourcePath) {
    return sourcePath.length() >= 2 && sourcePath.startsWith("\"") && sourcePath.endsWith("\"");
  }

  private static String describedBehavior(String expression) {
    if (expression == null || expression.isBlank()) {
      return "";
    }
    return expression.trim();
  }

  public MappingIntentRule(String sourcePath, String targetPath, String expression) {
    this(sourcePath, targetPath, expression, MappingRuleStatus.PROPOSED);
  }

  public static MappingIntentRule descriptive(
      String id,
      List<String> requirementIds,
      String targetPath,
      List<MappingFieldRef> sources,
      List<MappingConstant> constants,
      String behavior) {
    return new MappingIntentRule(
        id,
        requirementIds,
        targetPath,
        null,
        MappingCondition.always(),
        MappingRuleStatus.PROPOSED,
        sources,
        constants,
        behavior);
  }

  public MappingIntentRule withStatus(MappingRuleStatus newStatus) {
    return new MappingIntentRule(
        id, requirementIds, targetPath, value, condition, newStatus, fieldRefs, constants, behavior, missingSourcePolicy, schemaPins);
  }

  public MappingIntentRule withRequirementIds(List<String> ids) {
    return new MappingIntentRule(
        id, ids, targetPath, value, condition, status, fieldRefs, constants, behavior, missingSourcePolicy, schemaPins);
  }

  public MappingIntentRule withTargetPath(String path) {
    return new MappingIntentRule(
        id, requirementIds, path, value, condition, status, fieldRefs, constants, behavior, missingSourcePolicy,
        java.util.Objects.equals(MappingContract.canonicalPath(path), MappingContract.canonicalPath(targetPath))
            ? schemaPins : null);
  }

  public MappingIntentRule withBehavior(String nextBehavior) {
    return new MappingIntentRule(
        id, requirementIds, targetPath, value, condition, status, fieldRefs, constants, nextBehavior, missingSourcePolicy, schemaPins);
  }

  public MappingIntentRule withMissingSourcePolicy(String policy) {
    return new MappingIntentRule(id, requirementIds, targetPath, value, condition, status,
        fieldRefs, constants, behavior, policy, schemaPins);
  }

  /** Preserves the message origin and missing policy; changing its path invalidates schema pins. */
  public MappingIntentRule withMessageSourcePath(String path) {
    if (!(value instanceof MappingValue.Copy copy) || !(copy.source() instanceof MappingSource.Message message)) {
      throw new IllegalArgumentException("A message COPY is required to edit its source path.");
    }
    return new MappingIntentRule(id, requirementIds, targetPath,
        new MappingValue.Copy(new MappingSource.Message(message.interactionId(), message.port(), path)),
        condition, status, fieldRefs, constants, behavior, missingSourcePolicy, null);
  }

  /** Keeps legacy message references when adding behavior; a pinned COPY policy requires recapture. */
  public MappingIntentRule withLegacyMessageBehavior(String path, String expression) {
    if (missingSourcePolicy != null || !(value instanceof MappingValue.Copy copy)
        || !(copy.source() instanceof MappingSource.Message message)) {
      throw new IllegalArgumentException("Recapture the typed mapping before replacing its value with an expression.");
    }
    return new MappingIntentRule(id, requirementIds, targetPath, null, condition, status,
        List.of(new MappingFieldRef(message.interactionId(),
            message.port() == null ? "" : message.port().name(), MappingContract.canonicalPath(path), "")),
        List.of(), expression);
  }

  public MappingIntentRule withSchemaPins(Map<String, String> pins) {
    return new MappingIntentRule(id, requirementIds, targetPath, value, condition, status,
        fieldRefs, constants, behavior, missingSourcePolicy, pins);
  }

  @JsonIgnore
  public boolean descriptive() {
    return value == null && (!fieldRefs.isEmpty() || !constants.isEmpty() || !behavior.isBlank());
  }

  @JsonIgnore
  public List<MappingSource> sources() {
    return value == null ? List.of() : value.sources();
  }

  /** Presentation only. Composite operations retain their operands in {@link #value()}. */
  @JsonIgnore
  public String sourcePath() {
    if (descriptive()) {
      for (MappingFieldRef source : fieldRefs) {
        if (!source.fieldPath().isBlank()) {
          return source.fieldPath();
        }
      }
      return "";
    }
    return value instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message message
        ? message.path() : "";
  }

  /** Presentation only; no compiler parses this description. */
  @JsonIgnore
  public String expression() {
    if (descriptive()) {
      return behavior.isBlank() ? null : behavior;
    }
    return value == null || value instanceof MappingValue.Copy copy
        && copy.source() instanceof MappingSource.Message ? null : MappingValue.describe(value);
  }

  @JsonIgnore
  public boolean identityCopy() {
    return condition.when() == MappingCondition.When.ALWAYS
        && value instanceof MappingValue.Copy copy
        && copy.source() instanceof MappingSource.Message message
        && (message.interactionId() == null || message.interactionId().isBlank())
        && message.path() != null && message.path().equals(targetPath);
  }
}
