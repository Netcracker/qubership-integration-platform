package org.qubership.integration.platform.ai.plan.mapping;

import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;

/**
 * Chooses mapper-2 for supported declarative copies and quoted constants. Ticket 06 owns SCRIPT
 * selection when a rule carries an expression.
 */
public final class MappingMechanismSelector {

  private MappingMechanismSelector() {}

  public static Optional<MappingMechanism> select(MappingIntent intent) {
    if (intent == null || intent.rules().isEmpty()) {
      return Optional.empty();
    }
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.expression() != null) {
        return Optional.empty();
      }
      if (!isCopyPath(rule.sourcePath()) && !isConstantLiteral(rule.sourcePath())) {
        return Optional.empty();
      }
    }
    return Optional.of(MappingMechanism.MAPPER_2);
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
}
