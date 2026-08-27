package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Observable halt state a reader can act on. Journal revision and self-transitions are excluded: a
 * re-committed wait that changes nothing here has not advanced.
 *
 * <p>Runtime capture fills every field except {@link #cardActions}; the facade fills those via
 * {@link org.qubership.integration.platform.ai.chat.ChatEvent#actionsForClarify}.
 */
public record SemanticRecoveryState(
    RunStatus runStatus,
    String currentStageId,
    StageStatus currentStageStatus,
    String gateId,
    List<String> cardActions,
    String promptIdentity,
    RemainingAttempts remaining) {

  public SemanticRecoveryState {
    runStatus = runStatus == null ? RunStatus.RUNNING : runStatus;
    currentStageId = currentStageId == null ? "" : currentStageId;
    currentStageStatus = currentStageStatus == null ? StageStatus.PENDING : currentStageStatus;
    gateId = gateId == null ? "" : gateId;
    cardActions = cardActions == null ? List.of() : List.copyOf(cardActions);
    promptIdentity = promptIdentity == null ? "" : promptIdentity;
    remaining = remaining == null ? RemainingAttempts.none() : remaining;
  }

  /** Remaining automatic recovery attempts for this defect, sourced from the recovery attempt ledger. */
  public record RemainingAttempts(int semanticRepairsRemaining, int causalReopensRemaining) {

    public static RemainingAttempts none() {
      return new RemainingAttempts(0, 0);
    }
  }

  /** Which tuple component differed when the later capture advanced. */
  public enum Component {
    RUN_STATUS,
    CURRENT_STAGE_ID,
    CURRENT_STAGE_STATUS,
    GATE,
    CARD_ACTIONS,
    PROMPT_IDENTITY,
    REMAINING
  }

  /** Result of comparing two captures. Names the first component that moved. */
  public sealed interface CompareResult {
    record Unchanged() implements CompareResult {}

    record Advanced(Component component) implements CompareResult {}
  }

  /** Runtime seam: everything the orchestrator can see. Card actions stay empty. */
  public static SemanticRecoveryState captureRuntime(
      RunStatus runStatus,
      String currentStageId,
      StageStatus currentStageStatus,
      String gateId,
      String promptIdentity,
      RemainingAttempts remaining) {
    return new SemanticRecoveryState(
        runStatus,
        currentStageId,
        currentStageStatus,
        gateId,
        List.of(),
        promptIdentity,
        remaining);
  }

  /** Facade seam: the same runtime capture with the actions the card actually offers. */
  public SemanticRecoveryState withCardActions(List<String> actions) {
    return new SemanticRecoveryState(
        runStatus,
        currentStageId,
        currentStageStatus,
        gateId,
        actions,
        promptIdentity,
        remaining);
  }

  /** First differing component, or unchanged when every field matches. */
  public CompareResult compareTo(SemanticRecoveryState after) {
    SemanticRecoveryState next = after == null ? this : after;
    if (runStatus != next.runStatus) {
      return new CompareResult.Advanced(Component.RUN_STATUS);
    }
    if (!currentStageId.equals(next.currentStageId)) {
      return new CompareResult.Advanced(Component.CURRENT_STAGE_ID);
    }
    if (currentStageStatus != next.currentStageStatus) {
      return new CompareResult.Advanced(Component.CURRENT_STAGE_STATUS);
    }
    if (!gateId.equals(next.gateId)) {
      return new CompareResult.Advanced(Component.GATE);
    }
    if (!cardActions.equals(next.cardActions)) {
      return new CompareResult.Advanced(Component.CARD_ACTIONS);
    }
    if (!promptIdentity.equals(next.promptIdentity)) {
      return new CompareResult.Advanced(Component.PROMPT_IDENTITY);
    }
    if (!Objects.equals(remaining, next.remaining)) {
      return new CompareResult.Advanced(Component.REMAINING);
    }
    return new CompareResult.Unchanged();
  }
}
