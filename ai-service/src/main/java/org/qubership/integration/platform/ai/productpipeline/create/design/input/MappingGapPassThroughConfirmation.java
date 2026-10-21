package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Durable pass-through answer for uncovered mapping edges. Matches when the current uncovered set
 * is a subset of the stored pairs and the brief SHA is unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingGapPassThroughConfirmation(String briefSha, List<TransitionRef> uncovered) {

  private static final String ACTION = "pass_through";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public MappingGapPassThroughConfirmation {
    uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
  }

  public static Optional<MappingGapPassThroughConfirmation> parse(String text) {
    try {
      JsonNode node = MAPPER.readTree(text);
      JsonNode action = node.path("action");
      if (!ACTION.equals(action.asText())) {
        return Optional.empty();
      }
      return Optional.of(MAPPER.treeToValue(node, MappingGapPassThroughConfirmation.class));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public String toJson() {
    List<TransitionRef> sorted =
        uncovered.stream()
            .sorted(
                (left, right) -> {
                  int bySource = left.sourceRef().compareTo(right.sourceRef());
                  if (bySource != 0) {
                    return bySource;
                  }
                  return left.targetRef().compareTo(right.targetRef());
                })
            .toList();
    ObjectNode node = MAPPER.createObjectNode();
    node.put("action", ACTION);
    node.put("briefSha", briefSha);
    node.set("uncovered", MAPPER.valueToTree(sorted));
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize mapping-gap confirmation", e);
    }
  }

  /**
   * True when {@code briefSha} matches and every current uncovered pair is already stored. The
   * stored list may be a superset.
   */
  public boolean matches(String briefSha, List<Transition> currentUncovered) {
    if (!Objects.equals(this.briefSha, briefSha)) {
      return false;
    }
    return currentUncovered.stream().allMatch(this::contains);
  }

  private boolean contains(Transition transition) {
    return uncovered.stream()
        .anyMatch(
            stored ->
                stored.sourceRef().equals(transition.sourceInteractionId())
                    && stored.targetRef().equals(transition.targetInteractionId()));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TransitionRef(String sourceRef, String targetRef) {}
}
