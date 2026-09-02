package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Finds flow transitions that have no mapping intent, and decides whether to show the mapping-gap
 * card.
 */
public final class MappingGapCoverage {

  private MappingGapCoverage() {}

  /**
   * Transitions whose source and target are not covered by any mapping intent. Pass-through is the
   * absence of an intent, so those edges stay in this list.
   */
  public static List<Transition> uncovered(RequirementBrief brief) {
    List<MappingIntent> intents = brief.mappingIntents();
    return brief.flow().transitions().stream()
        .filter(transition -> !covered(transition, intents))
        .toList();
  }

  /**
   * False when nothing is uncovered, or when a stored pass-through still covers the current
   * uncovered set. True otherwise.
   */
  public static boolean shouldAsk(
      List<Transition> uncovered,
      MappingGapPassThroughConfirmation confirmation,
      String briefSha) {
    if (uncovered.isEmpty()) {
      return false;
    }
    return confirmation == null || !confirmation.matches(briefSha, uncovered);
  }

  /** One {@code sourceId -> targetId} line per uncovered transition, in list order. */
  public static List<String> readableEdges(List<Transition> uncovered) {
    return uncovered.stream()
        .map(
            transition ->
                transition.sourceInteractionId() + " -> " + transition.targetInteractionId())
        .toList();
  }

  private static boolean covered(Transition transition, List<MappingIntent> intents) {
    return intents.stream()
        .anyMatch(
            intent ->
                intent.sourceRef().equals(transition.sourceInteractionId())
                    && intent.targetRef().equals(transition.targetInteractionId()));
  }
}
