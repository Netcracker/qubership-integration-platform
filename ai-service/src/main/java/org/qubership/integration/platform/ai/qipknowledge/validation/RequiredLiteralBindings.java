package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;

/** Checks destinations in the typed requirement contract, never incidental graph text. */
public final class RequiredLiteralBindings {
  private static final ObjectMapper JSON = new ObjectMapper();
  private RequiredLiteralBindings() {}

  public static List<String> unbound(RequirementFact fact, List<RequirementFact> facts) {
    var intent = fact.elementIntent();
    if (intent == null || fact.polarity() != RequirementFactPolarity.POSITIVE) return List.of();
    if (intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.BEFORE_HOOK
        || intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.AFTER_HOOK)
      return List.of(); // Hook placement binds the owned artifact; materialization validates that artifact.
    var unbound = new ArrayList<String>();
    for (String literal : intent.exactValues()) {
      if (literal == null || literal.isBlank()) continue;
      boolean bound = facts.stream().filter(candidate -> candidate.polarity() == RequirementFactPolarity.POSITIVE)
          .filter(candidate -> candidate.targetsInteraction(intent.ownerInteractionId() == null
              ? intent.elementRef() : intent.ownerInteractionId()))
          .anyMatch(candidate -> List.of(candidate.path(), candidate.httpMethod(), candidate.topic(), candidate.operation())
              .contains(literal))
          || intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.CHAIN
              && literal.equals(intent.requestedName())
          || facts.stream().filter(candidate -> candidate.polarity() == RequirementFactPolarity.POSITIVE
              && candidate.elementIntent() != null
              && intent.elementRef().equals(candidate.elementIntent().elementRef())
              && intent.elementType().equals(candidate.elementIntent().elementType()))
              .flatMap(candidate -> candidate.elementIntent().requestedProperties().stream())
              .anyMatch(property -> property != null && boundValue(property.key(), property.value(), literal));
      if (intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.ANCHOR
          && org.qubership.integration.platform.ai.schema.ChainElementFamilies.classifiedTriggerAndSenderTypes()
              .contains(intent.elementType())
          && org.qubership.integration.platform.ai.schema.ChainElementFamilies.bindingMode(intent.elementType())
              == org.qubership.integration.platform.ai.schema.ChainElementFamilies.BindingMode.CATALOG_REQUIRED) {
        continue; // The catalog resolver validates operation-owned literals, not patch properties.
      }
      if (!bound) unbound.add(literal);
    }
    return List.copyOf(unbound);
  }

  private static boolean boundValue(String key, String value, String literal) {
    if (value == null) return false;
    if (value.equals(literal)) return true;
    if ("script".equals(key)) return scriptConstant(value, literal);
    try { return leaf(JSON.readTree(value), literal); }
    catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) { return false; }
  }

  private static boolean leaf(JsonNode value, String literal) {
    if (value == null) return false;
    if (value.isValueNode()) return value.asText().equals(literal);
    if (value.isObject() && value.has(literal)) return true;
    for (var child : value) if (leaf(child, literal)) return true;
    return false;
  }

  private static boolean scriptConstant(String script, String literal) {
    var found = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      var source = org.codehaus.groovy.control.SourceUnit.create("RequiredLiteral", script);
      source.parse();
      source.completePhase();
      source.nextPhase();
      source.convert();
      var visitor = new org.codehaus.groovy.ast.CodeVisitorSupport() {
        @Override public void visitConstantExpression(org.codehaus.groovy.ast.expr.ConstantExpression expression) {
          if (literal.equals(String.valueOf(expression.getValue()))) found.set(true);
          super.visitConstantExpression(expression);
        }
      };
      source.getAST().getStatementBlock().visit(visitor);
      return found.get();
    } catch (RuntimeException invalidScript) { return false; }
  }
}
