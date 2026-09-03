package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Coverage of approved flow transitions on the mapping-gap card. Each hop is mapped, skipped, or
 * uncovered. The card lists uncovered hops only.
 */
public final class MappingGapCoverage {

  public enum State {
    MAPPED,
    SKIPPED,
    UNCOVERED
  }

  private MappingGapCoverage() {}

  /**
   * Transitions that are neither mapped nor skipped. Mapped requires a mapping intent with at least
   * one rule. Skipped is an explicit skip record on the brief.
   */
  public static List<Transition> uncovered(RequirementBrief brief) {
    return brief.flow().transitions().stream()
        .filter(transition -> state(brief, transition) == State.UNCOVERED)
        .toList();
  }

  public static State state(RequirementBrief brief, Transition transition) {
    if (mapped(transition, brief.mappingIntents())) {
      return State.MAPPED;
    }
    if (skipped(transition, brief.skippedTransitions())) {
      return State.SKIPPED;
    }
    return State.UNCOVERED;
  }

  /** True while any approved hop is still uncovered. Hash confirmation is not coverage. */
  public static boolean shouldAsk(List<Transition> uncovered) {
    return uncovered != null && !uncovered.isEmpty();
  }

  /** Hash confirmation is not coverage. Delegates to {@link #shouldAsk(List)}. */
  public static boolean shouldAsk(
      List<Transition> uncovered,
      MappingGapPassThroughConfirmation confirmation,
      String briefSha) {
    return shouldAsk(uncovered);
  }

  /** Marks every currently uncovered hop as skipped. Already mapped hops stay mapped. */
  public static RequirementBrief skipUncovered(RequirementBrief brief) {
    List<Transition> remainder = uncovered(brief);
    if (remainder.isEmpty()) {
      return brief;
    }
    List<Transition> skipped = new ArrayList<>(brief.skippedTransitions());
    for (Transition transition : remainder) {
      if (!skipped(transition, skipped)) {
        skipped.add(transition);
      }
    }
    return brief.withSkippedTransitions(List.copyOf(skipped));
  }

  /** One {@code sourceId -> targetId} line per uncovered transition, in list order. */
  public static List<String> readableEdges(List<Transition> uncovered) {
    return uncovered.stream()
        .map(
            transition ->
                transition.sourceInteractionId() + " -> " + transition.targetInteractionId())
        .toList();
  }

  private static boolean mapped(Transition transition, List<MappingIntent> intents) {
    return intents.stream()
        .anyMatch(
            intent ->
                sameHop(transition, intent.sourceRef(), intent.targetRef())
                    && !intent.rules().isEmpty());
  }

  private static boolean skipped(Transition transition, List<Transition> skipped) {
    return skipped.stream().anyMatch(stored -> sameHop(transition, stored));
  }

  private static boolean sameHop(Transition left, Transition right) {
    return sameHop(left, right.sourceInteractionId(), right.targetInteractionId());
  }

  private static boolean sameHop(Transition transition, String sourceRef, String targetRef) {
    return transition.sourceInteractionId().equals(sourceRef)
        && transition.targetInteractionId().equals(targetRef);
  }
}
