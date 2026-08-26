package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet.FindingOwnerCategory;

/**
 * Deterministic recovery route for a create-chain stage failure. Selects the
 * producer of the rejected artifact from provenance and typed evidence. LLM
 * owner diagnosis is a fallback when the finding does not already name a
 * producer.
 */
public final class ProducerOwnedRecovery {

  /** What the runtime should do with a rejected artifact. */
  public enum Action {
    /** Retry the observing producer with the rejection evidence. */
    REPAIR_CURRENT,
    /** Reopen an upstream producer and require a new approval. */
    REOPEN_UPSTREAM,
    /** Ask the author for one missing fact, then resume the producer. */
    ASK_CLARIFICATION,
    /** Show Retry/Revise after automatic repair is exhausted. */
    PARK
  }

  /**
   * Inputs the router needs to pick a producer-owned recovery action.
   *
   * @param failedStageId observing stage
   * @param outcomeClass capability outcome
   * @param findings formatted validation findings
   * @param evidence raw error text
   * @param candidates closed owner set
   * @param catalogWritten true after the first catalog write
   * @param semanticRepairsUsed repairs already spent on this rejection
   * @param maxSemanticRepairs automatic repair budget
   * @param diagnosedOwner advisory owner from the narrative turn
   */
  public record Request(
      String failedStageId,
      StageOutcomeClass outcomeClass,
      String findings,
      String evidence,
      List<OwnerCandidate> candidates,
      boolean catalogWritten,
      int semanticRepairsUsed,
      int maxSemanticRepairs,
      Optional<String> diagnosedOwner) {

    public Request {
      failedStageId = failedStageId == null ? "" : failedStageId;
      findings = findings == null ? "" : findings;
      evidence = evidence == null ? "" : evidence;
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      diagnosedOwner = diagnosedOwner == null ? Optional.empty() : diagnosedOwner;
    }
  }

  /**
   * Selected recovery action and the producer that owns it.
   *
   * @param action recovery action
   * @param producerStageId stage that must repair or reopen
   * @param requestedFact missing fact when asking the author
   */
  public record Route(Action action, String producerStageId, String requestedFact) {

    public Route {
      action = action == null ? Action.PARK : action;
      producerStageId = producerStageId == null ? "" : producerStageId;
      requestedFact = requestedFact == null ? "" : requestedFact;
    }

    public Route(final Action action, final String producerStageId) {
      this(action, producerStageId, "");
    }
  }

  private ProducerOwnedRecovery() { }

  /** Picks the producer-owned recovery action for this rejection. */
  public static Route route(final Request request) {
    String failed = request.failedStageId();
    if (request.outcomeClass() == StageOutcomeClass.INTERNAL_FAILURE) {
      return new Route(Action.PARK, failed);
    }
    if (asksForMissingFact(request.findings(), request.evidence())) {
      return new Route(
          Action.ASK_CLARIFICATION,
          failed,
          requestedFact(request.findings(), request.evidence()));
    }
    FindingOwnerCategory category =
        OwnerCandidateSet.classifyFinding(request.findings(), request.evidence());
    String diagnosed = request.diagnosedOwner().orElse("");
    String producer =
        producerFor(category, request.candidates(), failed, diagnosed)
            .orElse(failed);
    if (request.catalogWritten() && !producer.equals(failed)) {
      return new Route(Action.PARK, producer);
    }
    Action action = actionFor(failed, producer, category, diagnosed, request.outcomeClass());
    if (action == Action.REPAIR_CURRENT
        && request.semanticRepairsUsed()
            >= Math.max(request.maxSemanticRepairs(), 0)) {
      return new Route(Action.PARK, producer);
    }
    if (action == Action.REOPEN_UPSTREAM
        && (producer.isBlank() || producer.equals(failed))) {
      return new Route(Action.PARK, failed);
    }
    return new Route(action, producer.isBlank() ? failed : producer);
  }

  private static Action actionFor(
      final String failedStageId,
      final String producer,
      final FindingOwnerCategory category,
      final String diagnosedOwner,
      final StageOutcomeClass outcomeClass) {
    if (category == FindingOwnerCategory.EXECUTION) {
      return Action.REPAIR_CURRENT;
    }
    if (!producer.isBlank() && !producer.equals(failedStageId)) {
      return Action.REOPEN_UPSTREAM;
    }
    if (producer.equals(failedStageId)
        && (category != FindingOwnerCategory.UNSPECIFIED
            || diagnosedOwner.equals(failedStageId)
            || outcomeClass == StageOutcomeClass.CONTRACT_FAILURE)) {
      return Action.REPAIR_CURRENT;
    }
    return Action.PARK;
  }

  private static Optional<String> producerFor(
      final FindingOwnerCategory category,
      final List<OwnerCandidate> candidates,
      final String failedStageId,
      final String diagnosedOwner) {
    Optional<String> fromFinding =
        switch (category) {
          case EXECUTION -> Optional.of(failedStageId);
          case POLICY_OR_BRIEF ->
              OwnerCandidateSet.briefProducerStageId(candidates, failedStageId)
                  .or(
                      () ->
                          OwnerCandidateSet.planProducerStageId(
                              candidates, failedStageId));
          case PLAN_FILL ->
              OwnerCandidateSet.planProducerStageId(candidates, failedStageId);
          case UNSPECIFIED -> Optional.empty();
        };
    if (fromFinding.isPresent()) {
      return fromFinding;
    }
    if (!diagnosedOwner.isBlank()
        && OwnerCandidateSet.containsStage(candidates, diagnosedOwner)) {
      return Optional.of(diagnosedOwner);
    }
    return Optional.empty();
  }

  private static boolean asksForMissingFact(
      final String findings, final String evidence) {
    String haystack = normalize(findings) + " " + normalize(evidence);
    return haystack.contains("catalog service")
        || haystack.contains("catalog operation")
        || haystack.contains("no matching")
        || (haystack.contains("catalog")
            && (haystack.contains("missing")
                || haystack.contains("not found")
                || haystack.contains("ambiguous")));
  }

  private static String requestedFact(
      final String findings, final String evidence) {
    String haystack = normalize(findings) + " " + normalize(evidence);
    if (haystack.contains("operation")) {
      return "catalog operation";
    }
    if (haystack.contains("topic")) {
      return "topic";
    }
    return "catalog service";
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
