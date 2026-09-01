package org.qubership.integration.platform.ai.plan.mapping;

import java.util.Locale;
import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

/**
 * Chooses mapper-2 for supported declarative copies and quoted constants, and SCRIPT when the user
 * requests it or a rule needs generated Groovy. Does not silently substitute a different
 * mechanism when a preference cannot express the approved rules.
 */
public final class MappingMechanismSelector {

  private MappingMechanismSelector() {}

  /**
   * Mapper-2 is off. Copy, constant, and other rule sets use SCRIPT unless a MAPPER_2 or SCRIPT
   * preference cannot express the rules.
   */
  static boolean mapper2Enabled() {
    return false;
  }

  /** False while mapper-2 is off. */
  public static boolean transformationGeneratorAllowed() {
    return mapper2Enabled();
  }

  /**
   * Element type for a transform shell. Mapper-2 captures become {@code script} while mapper-2 is
   * off so configuration and generation stay on cip-script-generator.
   */
  public static String canonicalTransformElementType(String elementType) {
    if (elementType == null) {
      return null;
    }
    String trimmed = elementType.trim();
    if (!MappingExecutionSite.ELEMENT_TYPE.equals(trimmed)) {
      return trimmed;
    }
    return mapper2Enabled()
        ? MappingExecutionSite.ELEMENT_TYPE
        : MappingExecutionSite.SCRIPT_ELEMENT_TYPE;
  }

  public static Optional<MappingMechanism> select(MappingIntent intent) {
    if (intent == null) {
      return Optional.empty();
    }
    if (intent.rules().isEmpty()) {
      return mapper2Enabled() ? Optional.empty() : Optional.of(MappingMechanism.SCRIPT);
    }
    Optional<MappingMechanism> preference = preference(intent);
    boolean mapper2Compatible = isMapper2Compatible(intent);
    boolean scriptCompatible = isScriptCompatible(intent);
    if (preference.isPresent()) {
      MappingMechanism wanted = preference.get();
      if (wanted == MappingMechanism.SCRIPT && scriptCompatible) {
        return Optional.of(MappingMechanism.SCRIPT);
      }
      if (wanted == MappingMechanism.MAPPER_2 && mapper2Compatible) {
        return Optional.of(
            mapper2Enabled() ? MappingMechanism.MAPPER_2 : MappingMechanism.SCRIPT);
      }
      return Optional.empty();
    }
    if (scriptRequired(intent) && scriptCompatible) {
      return Optional.of(MappingMechanism.SCRIPT);
    }
    if (mapper2Compatible) {
      return Optional.of(mapper2Enabled() ? MappingMechanism.MAPPER_2 : MappingMechanism.SCRIPT);
    }
    if (!mapper2Enabled()) {
      return Optional.of(MappingMechanism.SCRIPT);
    }
    return Optional.empty();
  }

  /**
   * Explains why a user preference cannot express the approved rules. Empty when {@link #select}
   * returned a mechanism.
   */
  public static Optional<String> clarification(MappingIntent intent) {
    if (select(intent).isPresent()) {
      return Optional.empty();
    }
    if (intent == null || intent.rules().isEmpty()) {
      return Optional.empty();
    }
    Optional<MappingMechanism> preference = preference(intent);
    MappingIntentRule blocking = blockingRule(intent, preference.orElse(null));
    if (preference.isEmpty()) {
      if (blocking == null) {
        return Optional.empty();
      }
      return Optional.of(
          "Mapping intent '"
              + intent.mappingIntentId()
              + "' cannot use mapper-2: rule "
              + blocking.targetPath()
              + " uses expression '"
              + blocking.expression()
              + "', which requires SCRIPT. Choose SCRIPT or replace the expression with a copy or"
              + " constant.");
    }
    MappingMechanism wanted = preference.get();
    if (wanted == MappingMechanism.MAPPER_2 && blocking != null) {
      return Optional.of(
          "Cannot use MAPPER_2 for mapping intent '"
              + intent.mappingIntentId()
              + "': rule "
              + blocking.targetPath()
              + " uses expression '"
              + blocking.expression()
              + "', which requires SCRIPT. Keep the brief open and choose SCRIPT, or replace the"
              + " expression with a copy or constant.");
    }
    if (wanted == MappingMechanism.SCRIPT && blocking != null) {
      return Optional.of(
          "Cannot use SCRIPT for mapping intent '"
              + intent.mappingIntentId()
              + "': rule "
              + blocking.targetPath()
              + " uses expression '"
              + blocking.expression()
              + "', which SCRIPT generation does not support. Keep the brief open and describe"
              + " uppercase, lowercase, or trim behavior, or remove the preference.");
    }
    return Optional.of(
        "Implementation preference '"
            + intent.implementationPreference()
            + "' cannot express the approved rules for mapping intent '"
            + intent.mappingIntentId()
            + "'. Choose SCRIPT or MAPPER_2, or remove the preference.");
  }

  static Optional<MappingMechanism> preference(MappingIntent intent) {
    if (intent == null) {
      return Optional.empty();
    }
    return parsePreference(intent.implementationPreference());
  }

  static Optional<MappingMechanism> parsePreference(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if ("SCRIPT".equals(normalized)) {
      return Optional.of(MappingMechanism.SCRIPT);
    }
    if ("MAPPER_2".equals(normalized) || "MAPPER2".equals(normalized)) {
      return Optional.of(MappingMechanism.MAPPER_2);
    }
    return Optional.empty();
  }

  public static boolean isScriptPreference(String raw) {
    return parsePreference(raw).orElse(null) == MappingMechanism.SCRIPT;
  }

  public static boolean isSupportedScriptExpression(String expression) {
    if (expression == null || expression.isBlank()) {
      return true;
    }
    String normalized = expression.toLowerCase(Locale.ROOT);
    return normalized.contains("uppercase")
        || normalized.contains("tolowercase")
        || normalized.contains("lowercase")
        || normalized.contains("touppercase")
        || normalized.contains("trim");
  }

  /**
   * Script generation can write the mapping expression. Mapper-2 still only accepts uppercase,
   * lowercase, and trim.
   */
  public static boolean scriptAcceptsExpression(String expression) {
    if (expression == null || expression.isBlank()) {
      return true;
    }
    if (!mapper2Enabled()) {
      return true;
    }
    return isSupportedScriptExpression(expression);
  }

  static boolean isConstantLiteral(String sourcePath) {
    return sourcePath != null
        && sourcePath.length() >= 2
        && sourcePath.startsWith("\"")
        && sourcePath.endsWith("\"");
  }

  static boolean isCopyPath(String sourcePath) {
    return sourcePath != null && !sourcePath.isBlank() && !isConstantLiteral(sourcePath);
  }

  static String constantValue(String sourcePath) {
    if (!isConstantLiteral(sourcePath)) {
      return sourcePath;
    }
    return sourcePath.substring(1, sourcePath.length() - 1);
  }

  private static boolean isMapper2Compatible(MappingIntent intent) {
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.expression() != null) {
        return false;
      }
      if (!isCopyPath(rule.sourcePath()) && !isConstantLiteral(rule.sourcePath())) {
        return false;
      }
    }
    return true;
  }

  private static boolean isScriptCompatible(MappingIntent intent) {
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.targetPath() == null || rule.targetPath().isBlank()) {
        return false;
      }
      if (rule.expression() != null) {
        if (!isSupportedScriptExpression(rule.expression())) {
          return false;
        }
        continue;
      }
      if (!isCopyPath(rule.sourcePath()) && !isConstantLiteral(rule.sourcePath())) {
        return false;
      }
    }
    return true;
  }

  private static boolean scriptRequired(MappingIntent intent) {
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.expression() != null) {
        return true;
      }
    }
    return false;
  }

  private static MappingIntentRule blockingRule(
      MappingIntent intent, MappingMechanism preference) {
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.expression() == null) {
        continue;
      }
      if (preference == MappingMechanism.MAPPER_2) {
        return rule;
      }
      if (preference == MappingMechanism.SCRIPT && !isSupportedScriptExpression(rule.expression())) {
        return rule;
      }
      if (preference == null && !isSupportedScriptExpression(rule.expression())) {
        return rule;
      }
      if (preference == null) {
        return rule;
      }
    }
    return null;
  }
}
