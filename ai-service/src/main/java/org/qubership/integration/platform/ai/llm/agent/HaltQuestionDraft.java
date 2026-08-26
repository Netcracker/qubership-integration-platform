package org.qubership.integration.platform.ai.llm.agent;

/**
 * Structured reply from the turns that read a typed message at a pause, whether the run is halted
 * or waiting for approval. Both pauses face the same two-way decision, so they share one shape.
 *
 * <p>{@code verdict} is {@code QUESTION} when the message asks about the run and {@code
 * INSTRUCTION} when it tells the run what to do. The token stays a raw string, the way {@code
 * ownerStageId} does on {@link OwnerDiagnosisDraft}, so the runtime can drop one it does not
 * recognize instead of failing the whole turn over it.
 *
 * <p>{@code answer} carries the reply to a question, written from the pause evidence alone and in
 * the pinned response locale. It is empty for an instruction, and it says that the evidence does
 * not cover the question rather than guessing at one the evidence cannot support.
 */
public record HaltQuestionDraft(String verdict, String answer) {

  public HaltQuestionDraft {
    verdict = verdict == null ? "" : verdict;
    answer = answer == null ? "" : answer;
  }
}
