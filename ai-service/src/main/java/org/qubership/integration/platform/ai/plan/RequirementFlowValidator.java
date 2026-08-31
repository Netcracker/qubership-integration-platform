package org.qubership.integration.platform.ai.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationDirection;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Structural contract for {@link RequirementFlow}. Returns the first deterministic violation so
 * repair messages stay stable.
 */
public final class RequirementFlowValidator {

  private static final Set<String> NATIVE_INBOUND_TRIGGER_KEYS =
      Set.of("http-trigger", "kafka-trigger-2");

  private RequirementFlowValidator() {}

  public static Optional<String> validateStructure(RequirementFlow flow) {
    RequirementFlow checked = flow == null ? RequirementFlow.EMPTY : flow;
    List<Interaction> interactions = checked.interactions();
    if (interactions.isEmpty()) {
      return Optional.of("requirement flow has no interactions");
    }

    Set<String> interactionIds = new LinkedHashSet<>();
    for (Interaction interaction : interactions) {
      String interactionId = interaction.interactionId();
      if (interactionId.isBlank()) {
        return Optional.of("requirement flow interactionId is blank");
      }
      if (!interactionIds.add(interactionId)) {
        return Optional.of("requirement flow contains duplicate interactionId: " + interactionId);
      }
    }

    for (Interaction interaction : interactions) {
      if (interaction.direction() == null) {
        return Optional.of(
            "requirement flow interaction " + interaction.interactionId() + " has no direction");
      }
      if (interaction.participant().isBlank()) {
        return Optional.of(
            "requirement flow interaction "
                + interaction.interactionId()
                + " has a blank participant");
      }
      if (interaction.operation().isBlank()) {
        return Optional.of(
            "requirement flow interaction "
                + interaction.interactionId()
                + " has a blank operation");
      }
    }

    Map<String, List<String>> adjacency = new LinkedHashMap<>();
    for (String interactionId : interactionIds) {
      adjacency.put(interactionId, new ArrayList<>());
    }
    Set<String> seenTransitions = new LinkedHashSet<>();
    for (Transition transition : checked.transitions()) {
      String sourceId = transition.sourceInteractionId();
      String targetId = transition.targetInteractionId();
      if (sourceId.isBlank()) {
        return Optional.of("requirement flow transition has a blank sourceInteractionId");
      }
      if (targetId.isBlank()) {
        return Optional.of("requirement flow transition has a blank targetInteractionId");
      }
      if (sourceId.equals(targetId)) {
        return Optional.of("requirement flow transition is a self-loop: " + edge(sourceId, targetId));
      }
      if (!interactionIds.contains(sourceId)) {
        return Optional.of(
            "requirement flow transition references unknown sourceInteractionId: " + sourceId);
      }
      if (!interactionIds.contains(targetId)) {
        return Optional.of(
            "requirement flow transition references unknown targetInteractionId: " + targetId);
      }
      if (!seenTransitions.add(edge(sourceId, targetId))) {
        return Optional.of("requirement flow contains duplicate transition: " + edge(sourceId, targetId));
      }
      adjacency.get(sourceId).add(targetId);
    }

    boolean hasInbound =
        interactions.stream().anyMatch(interaction -> interaction.direction() == Direction.INBOUND);
    if (!hasInbound) {
      return Optional.of("requirement flow has no inbound interaction");
    }

    Optional<String> cycle = detectCycle(interactionIds, adjacency);
    if (cycle.isPresent()) {
      return cycle;
    }

    Set<String> reachable = reachableFromInbound(interactions, adjacency);
    for (Interaction interaction : interactions) {
      if (interaction.direction() == Direction.OUTBOUND
          && !reachable.contains(interaction.interactionId())) {
        return Optional.of(
            "requirement flow outbound interaction "
                + interaction.interactionId()
                + " is unreachable from any inbound interaction");
      }
    }
    return Optional.empty();
  }

  public static Optional<String> validateBindings(
      RequirementFlow flow,
      List<RequirementFact> facts,
      List<CatalogBindingHint> bindings) {
    Optional<String> structure = validateStructure(flow);
    if (structure.isPresent()) {
      return structure;
    }
    List<CatalogBindingHint> hintList = bindings == null ? List.of() : bindings;
    Map<String, CatalogBindingHint> byInteraction = new LinkedHashMap<>();
    for (CatalogBindingHint hint : hintList) {
      if (hint == null) {
        continue;
      }
      if (!CatalogBindingHint.SCHEMA_VERSION.equals(hint.schemaVersion())) {
        return Optional.of(
            "catalog binding hint must use schemaVersion=3, got " + hint.schemaVersion());
      }
      String interactionId = hint.interactionId();
      if (flow.interaction(interactionId).isEmpty()) {
        return Optional.of(
            "catalog binding interactionId=" + interactionId + " is not in the requirement flow");
      }
      if (byInteraction.putIfAbsent(interactionId, hint) != null) {
        return Optional.of(
            "requirement flow contains duplicate catalog binding for interactionId="
                + interactionId);
      }
    }

    List<RequirementFact> factList = facts == null ? List.of() : facts;
    for (Interaction interaction : flow.interactions()) {
      String interactionId = interaction.interactionId();
      boolean requiresBinding = requiresCatalogBinding(interaction, factList);
      CatalogBindingHint hint = byInteraction.get(interactionId);
      if (hasNativeInboundTriggerFact(interaction, factList) && hint != null) {
        return Optional.of(
            "requirement flow interaction "
                + interactionId
                + " has an unexpected catalog binding");
      }
      if (requiresBinding && hint == null) {
        return Optional.of(
            "business interaction "
                + interactionId
                + " has no catalog binding ("
                + interaction.participant()
                + " "
                + interaction.operation()
                + ", "
                + interaction.direction()
                + "). Call resolveApiOperation with interactionId="
                + interactionId
                + ".");
      }
      if (hint == null) {
        continue;
      }
      Optional<CatalogOperationDirection> catalogDirection = hint.operationDirection();
      if (catalogDirection.isEmpty()) {
        return Optional.of(
            "requirement flow interaction "
                + interactionId
                + " has unknown catalog operation direction");
      }
      CatalogOperationDirection expected =
          interaction.direction() == Direction.INBOUND
              ? CatalogOperationDirection.PRODUCED_BY_SYSTEM
              : CatalogOperationDirection.CONSUMED_BY_SYSTEM;
      if (catalogDirection.get() != expected) {
        return Optional.of(
            "requirement flow interaction "
                + interactionId
                + " direction="
                + interaction.direction()
                + " conflicts with catalog direction "
                + catalogDirection.get());
      }
    }
    return Optional.empty();
  }

  /**
   * Returns true only for outbound interactions. {@code facts} stays on the signature so existing
   * callers keep compiling; inbound catalog need is not derived from facts.
   */
  @SuppressWarnings("java:S1172")
  static boolean requiresCatalogBinding(Interaction interaction, List<RequirementFact> facts) {
    return interaction.direction() == Direction.OUTBOUND;
  }

  static boolean hasNativeInboundTriggerFact(
      Interaction interaction, List<RequirementFact> facts) {
    return facts.stream()
        .anyMatch(
            fact ->
                fact != null
                    && interaction.interactionId().equals(fact.sourceFactId())
                    && NATIVE_INBOUND_TRIGGER_KEYS.contains(fact.capabilityKey()));
  }

  private static Optional<String> detectCycle(
      Set<String> interactionIds, Map<String, List<String>> adjacency) {
    Map<String, VisitState> state = new LinkedHashMap<>();
    for (String interactionId : interactionIds) {
      state.put(interactionId, VisitState.UNVISITED);
    }
    for (String interactionId : interactionIds) {
      if (state.get(interactionId) == VisitState.UNVISITED) {
        Optional<String> cycle = visit(interactionId, adjacency, state);
        if (cycle.isPresent()) {
          return cycle;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<String> visit(
      String interactionId,
      Map<String, List<String>> adjacency,
      Map<String, VisitState> state) {
    state.put(interactionId, VisitState.VISITING);
    for (String nextId : adjacency.getOrDefault(interactionId, List.of())) {
      VisitState nextState = state.get(nextId);
      if (nextState == VisitState.VISITING) {
        return Optional.of("requirement flow contains a cycle: " + edge(interactionId, nextId));
      }
      if (nextState == VisitState.UNVISITED) {
        Optional<String> nested = visit(nextId, adjacency, state);
        if (nested.isPresent()) {
          return nested;
        }
      }
    }
    state.put(interactionId, VisitState.VISITED);
    return Optional.empty();
  }

  private static Set<String> reachableFromInbound(
      List<Interaction> interactions, Map<String, List<String>> adjacency) {
    Set<String> reachable = new LinkedHashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    for (Interaction interaction : interactions) {
      if (interaction.direction() == Direction.INBOUND) {
        String interactionId = interaction.interactionId();
        if (reachable.add(interactionId)) {
          pending.add(interactionId);
        }
      }
    }
    while (!pending.isEmpty()) {
      String currentId = pending.removeFirst();
      for (String nextId : adjacency.getOrDefault(currentId, List.of())) {
        if (reachable.add(nextId)) {
          pending.add(nextId);
        }
      }
    }
    return reachable;
  }

  private static String edge(String sourceInteractionId, String targetInteractionId) {
    return sourceInteractionId + " -> " + targetInteractionId;
  }

  private enum VisitState {
    UNVISITED,
    VISITING,
    VISITED
  }
}
