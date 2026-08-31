package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;

/**
 * Requirements-level actor-to-actor interactions and causal transitions. List position has no
 * meaning; only {@link Transition} values define order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementFlow(List<Interaction> interactions, List<Transition> transitions) {

  public static final RequirementFlow EMPTY = new RequirementFlow(List.of(), List.of());

  public RequirementFlow {
    interactions = interactions == null ? List.of() : List.copyOf(interactions);
    transitions = transitions == null ? List.of() : List.copyOf(transitions);
  }

  public Optional<Interaction> interaction(String interactionId) {
    String id = trim(interactionId);
    if (id.isEmpty()) {
      return Optional.empty();
    }
    return interactions.stream()
        .filter(interaction -> id.equals(interaction.interactionId()))
        .findFirst();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Interaction(
      String interactionId,
      Direction direction,
      String participant,
      String operation,
      String description) {

    public Interaction {
      interactionId = trim(interactionId);
      participant = trim(participant);
      operation = trim(operation);
      description = trim(description);
    }
  }

  public enum Direction {
    INBOUND,
    OUTBOUND
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Transition(String sourceInteractionId, String targetInteractionId) {
    public Transition {
      sourceInteractionId = trim(sourceInteractionId);
      targetInteractionId = trim(targetInteractionId);
    }
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
