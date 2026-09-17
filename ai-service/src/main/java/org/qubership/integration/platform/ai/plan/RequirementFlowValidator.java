package org.qubership.integration.platform.ai.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationDirection;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

/**
 * Structural contract for {@link RequirementFlow}. Returns the first deterministic violation so
 * repair messages stay stable.
 */
public final class RequirementFlowValidator {

  private static final Set<String> NATIVE_INBOUND_TRIGGER_KEYS =
      Set.of("http-trigger", "chain-trigger-2", "kafka-trigger-2", "quartz-scheduler");
  private static final Set<String> NATIVE_DIRECT_OUTBOUND_CAPABILITY_KEYS =
      Set.of("http-sender", "kafka-sender-2");
  private static final Set<String> SUPPORTED_INBOUND_CAPABILITY_KEYS =
      Set.of(
          "http-trigger",
          "chain-trigger-2",
          "kafka-trigger-2",
          "quartz-scheduler",
          "async-api-trigger");

  private RequirementFlowValidator() {}

  static Set<String> supportedInboundCapabilityKeys() {
    return SUPPORTED_INBOUND_CAPABILITY_KEYS;
  }

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

    for (Interaction interaction : interactions) {
      if (interaction.direction() == Direction.OUTBOUND
          && interaction.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE
          && adjacency.getOrDefault(interaction.interactionId(), List.of()).isEmpty()) {
        return Optional.of(
            "requirement flow interaction "
                + interaction.interactionId()
                + " uses INLINE_RESPONSE but has no successor response interaction");
      }
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

    for (Transition transition : checked.transitions()) {
      String targetId = transition.targetInteractionId();
      if (checked.interaction(targetId).orElseThrow().direction() == Direction.INBOUND) {
        return Optional.of(
            "requirement flow inbound interaction "
                + targetId
                + " has a predecessor and cannot be an entry point");
      }
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
    Optional<String> capabilityError = validateInboundCapabilities(flow, factList);
    if (capabilityError.isPresent()) {
      return capabilityError;
    }
    for (RequirementFact fact : factList) {
      if (fact == null
          || fact.polarity() != RequirementFactPolarity.POSITIVE
          || fact.kind() != RequirementFactKind.CAPABILITY
          || !NATIVE_INBOUND_TRIGGER_KEYS.contains(fact.capabilityKey())) {
        continue;
      }
      Optional<Interaction> owner = flow.interaction(fact.sourceFactId());
      if (owner.isEmpty() || owner.get().direction() != Direction.INBOUND) {
        return Optional.of(
            "native trigger fact sourceFactId="
                + fact.sourceFactId()
                + " has no matching inbound interaction in the requirement flow");
      }
    }
    for (Interaction interaction : flow.interactions()) {
      String interactionId = interaction.interactionId();
      boolean requiresBinding = requiresCatalogBinding(interaction, factList);
      CatalogBindingHint hint = byInteraction.get(interactionId);
      if (isNativeDirectInteraction(interaction, factList) && hint != null) {
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
      if (interaction.direction() == Direction.INBOUND
          && catalogDirection.get() != CatalogOperationDirection.PRODUCED_BY_SYSTEM) {
        return Optional.of(
            "requirement flow interaction "
                + interactionId
                + " direction="
                + interaction.direction()
                + " conflicts with catalog direction "
                + catalogDirection.get());
      }
    }

    for (Interaction interaction : flow.interactions()) {
      String interactionId = interaction.interactionId();
      if (interaction.direction() != Direction.INBOUND
          || byInteraction.containsKey(interactionId)
          || hasEntryPointCapabilityFact(interactionId, factList)) {
        continue;
      }
      return Optional.of(
          "entry point "
              + interactionId
              + " has neither a catalog binding nor a capability fact ("
              + interaction.participant()
              + " "
              + interaction.operation()
              + "). The brief would carry an empty capability key and design-input rejects that."
              + " Call resolveApiOperation with interactionId="
              + interactionId
              + ", or capture a CAPABILITY fact with sourceFactId="
              + interactionId
              + " and capabilityKey=http-trigger, chain-trigger-2, or kafka-trigger-2.");
    }
    return Optional.empty();
  }

  public static Optional<String> validateInboundCapabilities(
      RequirementFlow flow, List<RequirementFact> facts) {
    RequirementFlow checked = flow == null ? RequirementFlow.EMPTY : flow;
    List<RequirementFact> factList = facts == null ? List.of() : facts;
    for (Interaction interaction : checked.interactions()) {
      Optional<String> inboundCapability = inboundCapabilityKey(interaction, factList);
      if (inboundCapability.isPresent()
          && !SUPPORTED_INBOUND_CAPABILITY_KEYS.contains(inboundCapability.get())) {
        return Optional.of(
            "requirement flow entry point "
                + interaction.interactionId()
                + " uses unsupported capabilityKey="
                + inboundCapability.get()
                + ". Allowed inbound capability keys: "
                + String.join(", ", SUPPORTED_INBOUND_CAPABILITY_KEYS.stream().sorted().toList()));
      }
      if (inboundCapability.filter("http-trigger"::equals).isPresent()
          && factList.stream()
              .filter(fact -> fact != null)
              .filter(fact -> interaction.interactionId().equals(fact.sourceFactId()))
              .filter(fact -> "http-trigger".equals(fact.capabilityKey()))
              .allMatch(fact -> fact.httpMethod().isBlank())) {
        return Optional.of(
            "HTTP trigger "
                + interaction.interactionId()
                + " has no HTTP method. Specify GET, POST, or another supported method.");
      }
    }
    return Optional.empty();
  }

  /** Returns true for outbound interactions except explicitly direct native endpoints. */
  static boolean requiresCatalogBinding(Interaction interaction, List<RequirementFact> facts) {
    return interaction.direction() == Direction.OUTBOUND && !isNativeDirectInteraction(interaction, facts);
  }

  /** Returns true when an interaction is configured without a catalog operation. */
  static boolean isNativeDirectInteraction(Interaction interaction, List<RequirementFact> facts) {
    return (interaction.direction() == Direction.INBOUND
            && hasNativeInboundTriggerFact(interaction, facts))
        || hasNativeDirectOutboundCapabilityFact(interaction, facts);
  }

  /**
   * Mirrors the brief projector: the entry point takes its capability key from the first fact that
   * carries the interaction id. A blank key there leaves the approved brief unusable downstream.
   */
  static boolean hasEntryPointCapabilityFact(String interactionId, List<RequirementFact> facts) {
    for (RequirementFact fact : facts) {
      if (fact != null && interactionId.equals(fact.sourceFactId())) {
        return fact.capabilityKey() != null && !fact.capabilityKey().isBlank();
      }
    }
    return false;
  }

  private static Optional<String> inboundCapabilityKey(
      Interaction interaction, List<RequirementFact> facts) {
    if (interaction.direction() != Direction.INBOUND) {
      return Optional.empty();
    }
    for (RequirementFact fact : facts) {
      if (fact != null
          && interaction.interactionId().equals(fact.sourceFactId())
          && fact.capabilityKey() != null
          && !fact.capabilityKey().isBlank()) {
        return Optional.of(fact.capabilityKey());
      }
    }
    return Optional.empty();
  }

  public static boolean hasNativeInboundTriggerFact(
      Interaction interaction, List<RequirementFact> facts) {
    return facts.stream()
        .anyMatch(
            fact ->
                fact != null
                    && interaction.interactionId().equals(fact.sourceFactId())
                    && NATIVE_INBOUND_TRIGGER_KEYS.contains(fact.capabilityKey()));
  }

  private static boolean hasNativeDirectOutboundCapabilityFact(
      Interaction interaction, List<RequirementFact> facts) {
    return interaction.direction() == Direction.OUTBOUND
        && hasNativeDirectOutboundCapabilityFact(interaction.interactionId(), facts);
  }

  public static boolean hasNativeDirectOutboundCapabilityFact(
      String interactionId, List<RequirementFact> facts) {
    return nativeDirectOutboundCapabilityKey(interactionId, facts).isPresent();
  }

  public static Optional<String> nativeDirectOutboundCapabilityKey(
      String interactionId, List<RequirementFact> facts) {
    return facts.stream()
        .filter(Objects::nonNull)
        .filter(fact -> interactionId.equals(fact.sourceFactId()))
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == RequirementFactKind.CAPABILITY)
        .map(RequirementFact::capabilityKey)
        .filter(NATIVE_DIRECT_OUTBOUND_CAPABILITY_KEYS::contains)
        .findFirst();
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
