package org.qubership.integration.platform.ai.productpipeline.runtime;

/**
 * Named recovery stop. The durable wait records {@link #name()} and the card shows {@link
 * #cardSentence()} in plain language.
 */
public enum HaltRecoveryGuard {
  MAX_CAUSAL_REOPENS("Automatic reopen budget is spent."),
  OWNER_ALREADY_REOPENED("This owner has already been reopened for this defect."),
  CATALOG_ALREADY_WRITTEN("The catalog has already been written, so this run cannot reopen an earlier stage."),
  NARRATIVE_EXPLANATION_BUDGET("Explanation budget is spent."),
  MAX_SEMANTIC_REPAIRS("Repair attempts for this defect are spent."),
  TECHNICAL_RETRY("Retrying the same attempt cannot succeed."),
  REPEATED_FAILURE_THRESHOLD("The same failure has repeated enough times that retry is no longer offered."),
  BLANK_OR_UNAPPROVED_OWNER("No approved earlier owner can take this defect."),
  MISSING_PROFILE_OR_PRIOR_CANDIDATE("The owning stage has no profile or prior candidate to reopen."),
  BARE_GO_BACK_AT_OWNER_CHOICE("This card already lists the owners; pick one rather than going back."),
  NAMED_STAGE_OUTSIDE_CANDIDATE_SET("That stage is not a candidate for this defect.");

  private final String cardSentence;

  HaltRecoveryGuard(String cardSentence) {
    this.cardSentence = cardSentence;
  }

  /** Author-visible sentence naming this guard. */
  public String cardSentence() {
    return cardSentence;
  }

  /** Remaining-attempt line appended to a refusal card. */
  public static String remainingLine(SemanticRecoveryState.RemainingAttempts remaining) {
    SemanticRecoveryState.RemainingAttempts value =
        remaining == null ? SemanticRecoveryState.RemainingAttempts.none() : remaining;
    return " Repairs remaining: "
        + value.semanticRepairsRemaining()
        + ". Automatic reopens remaining: "
        + value.causalReopensRemaining()
        + ".";
  }
}
