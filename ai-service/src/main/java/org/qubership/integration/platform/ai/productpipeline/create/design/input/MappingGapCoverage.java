package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Coverage of requested field adaptations. Execution edges without an adaptation request do not
 * establish missing author input.
 */
public final class MappingGapCoverage {

  public enum State {
    MAPPED,
    SKIPPED,
    NOT_REQUESTED,
    UNCOVERED
  }

  private MappingGapCoverage() {}

  /**
   * Requested adaptations that are neither mapped nor explicitly skipped. Body construction in a
   * script or hook remains owned by its approved element intent.
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
    if (!requested(brief, transition)) {
      return State.NOT_REQUESTED;
    }
    return State.UNCOVERED;
  }

  private static boolean requested(RequirementBrief brief, Transition transition) {
    return brief.mappingIntents().stream().anyMatch(intent ->
        sameHop(transition, intent.sourceRef(), intent.targetRef()))
        || brief.facts().stream().anyMatch(fact ->
            fact.kind() == RequirementFactKind.FIELD_MAPPING
                && fact.polarity() == RequirementFactPolarity.POSITIVE
                && fact.interactionIds().contains(transition.sourceInteractionId())
                && fact.interactionIds().contains(transition.targetInteractionId()));
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
