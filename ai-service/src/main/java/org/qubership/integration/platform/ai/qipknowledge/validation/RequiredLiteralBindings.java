package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/** Checks destinations in the typed requirement contract, never incidental graph text. */
public final class RequiredLiteralBindings {
  private static final ObjectMapper JSON = new ObjectMapper();
  private RequiredLiteralBindings() {}

  public static List<String> unbound(RequirementFact fact, List<RequirementFact> facts) {
    return unbound(fact, facts, List.of());
  }

  public static List<String> unbound(RequirementFact fact, List<RequirementFact> facts,
      List<RequirementFlow.Interaction> interactions) {
    var intent = fact.elementIntent();
    if (intent == null || fact.polarity() != RequirementFactPolarity.POSITIVE) return List.of();
    if (ownedScript(fact)) return List.of(); // Its owned body is generated later and checked on the assembled graph.
    if (intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.BEFORE_HOOK
        || intent.placement() == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.AFTER_HOOK)
      return List.of(); // Hook placement binds the owned artifact; materialization validates that artifact.
    var unbound = new ArrayList<String>();
    for (String literal : intent.exactValues()) {
      if (literal == null || literal.isBlank()) continue;
      if (catalogIdentity(fact, interactions, literal)) continue;
      boolean bound = intent.placement()
          == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.ANCHOR
          && facts.stream().filter(candidate -> candidate.polarity() == RequirementFactPolarity.POSITIVE)
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

  /**
   * A source-backed literal stays covered when it sits in the requested name, a property, the
   * element body, a mapping text, or {@code exactValues}. Leaving {@code exactValues} is allowed.
   * A service-call display name is not an implementation binding for {@link #unbound}.
   */
  public static boolean covered(RequirementElementIntent intent, String literal, List<String> mappingTexts) {
    if (literal == null || literal.isBlank() || intent == null) {
      return false;
    }
    if (literal.equals(intent.requestedName())) {
      return true;
    }
    if (intent.exactValues() != null && intent.exactValues().contains(literal)) {
      return true;
    }
    if (intent.requestedProperties() != null) {
      for (var property : intent.requestedProperties()) {
        if (property != null && boundValue(property.key(), property.value(), literal)) {
          return true;
        }
      }
    }
    if (mappingTexts == null) {
      return false;
    }
    for (String text : mappingTexts) {
      if (literal.equals(text)) {
        return true;
      }
    }
    return false;
  }

  /** A catalog participant or operation is owned by that interaction, not by a runtime property. */
  private static boolean catalogIdentity(RequirementFact fact, List<RequirementFlow.Interaction> interactions,
      String literal) {
    if (interactions == null) return false;
    return interactions.stream().filter(interaction -> interaction != null
        && fact.targetsInteraction(interaction.interactionId()))
        .anyMatch(interaction -> literal.equals(interaction.participant())
            || literal.equals(interaction.operation()));
  }

  public static List<String> missingScriptLiterals(RequirementFact fact,
      org.qubership.integration.platform.ai.plan.model.ChainPlanGraph graph) {
    if (!ownedScript(fact)) return List.of();
    var intent = fact.elementIntent();
    var bodies = graph.nodes().stream().filter(node -> intent.elementRef().equals(node.nodeId())
        && intent.elementType().equals(node.type())).flatMap(node -> node.properties() == null
            ? java.util.stream.Stream.empty() : node.properties().stream())
        .filter(property -> property != null && "script".equals(property.key()))
        .map(property -> property.value()).toList();
    return intent.exactValues().stream().filter(literal -> literal != null && !literal.isBlank())
        .filter(literal -> bodies.stream().noneMatch(body -> body != null && scriptConstant(body, literal))).toList();
  }

  private static boolean ownedScript(RequirementFact fact) {
    return fact.polarity() == RequirementFactPolarity.POSITIVE && fact.elementIntent() != null
        && "script".equals(fact.elementIntent().elementType())
        && fact.elementIntent().placement()
            == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE;
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
