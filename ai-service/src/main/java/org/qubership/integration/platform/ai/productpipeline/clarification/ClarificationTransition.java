package org.qubership.integration.platform.ai.productpipeline.clarification;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/**
 * Durable clarification identity stored on a run transition.
 *
 * <p>An instance holds an offer, an answer, or both. A missing component stays null.
 */
public record ClarificationTransition(Offer offer, Answer answer) {

  public enum Disposition {
    ANSWER,
    AMBIGUOUS,
    NON_ANSWER
  }

  /** Names the requirement draft and question a run is waiting on. */
  public record Offer(
      String offerId,
      CompilationArtifacts.Reference draftReference,
      String questionId,
      long runRevision,
      String sourceGeneration) {}

  /** Names the offer a later turn answered and that turn's disposition. */
  public record Answer(String offerId, String answerTurnId, Disposition disposition) {}
}
