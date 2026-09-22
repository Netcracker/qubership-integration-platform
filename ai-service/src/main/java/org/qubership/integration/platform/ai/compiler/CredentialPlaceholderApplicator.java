package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

/**
 * Replaces literal credential property values with secured references before validation.
 *
 * <p>Uses the same key check as {@link CompilerSecurityValidator}. A value that is already {@code
 * #{NAME}} stays as-is. The placeholder name is the element type plus the property key, so two
 * element types do not share one reference.
 */
public final class CredentialPlaceholderApplicator {

  private CredentialPlaceholderApplicator() {}

  public static ChainPlanGraph apply(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return graph;
    }
    ChainPlanGraph updated = graph;
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.properties() == null || node.nodeId() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (property == null || !CompilerSecurityValidator.isCredentialKey(property.key())) {
          continue;
        }
        String value = property.value();
        if (value == null || value.isBlank() || CompilerSecurityValidator.isSecuredVariableReference(value)) {
          continue;
        }
        updated =
            updated.withNodeProperty(
                node.nodeId(), property.key(), placeholder(node.type(), property.key()));
      }
    }
    return updated;
  }

  static String placeholder(String elementType, String propertyKey) {
    return "#{" + token(elementType) + "_" + token(propertyKey) + "}";
  }

  private static String token(String value) {
    if (value == null || value.isBlank()) {
      return "VALUE";
    }
    StringBuilder token = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == '-' || current == '.' || current == ' ') {
        appendUnderscore(token);
        continue;
      }
      if (Character.isUpperCase(current)) {
        appendUnderscore(token);
      }
      if (Character.isLetterOrDigit(current)) {
        token.append(Character.toUpperCase(current));
      }
    }
    return token.isEmpty() ? "VALUE" : token.toString();
  }

  private static void appendUnderscore(StringBuilder token) {
    if (token.isEmpty() || token.charAt(token.length() - 1) == '_') {
      return;
    }
    token.append('_');
  }
}
