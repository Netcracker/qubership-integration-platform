package org.qubership.integration.platform.ai.llm.agent;

/**
 * Structured reply from the turns that read a typed message at a pause, whether the run is halted
 * or waiting for approval.
 *
 * <p>{@code verdict} is {@code QUESTION} when the message asks about the run. Halt turns may split
 * instructions into {@code CORRECTION}, which carries new repair information, and {@code RETRY},
 * which only asks to repeat. Approval turns use {@code INSTRUCTION}. The token stays a raw string
 * so the runtime can drop one it does not recognize instead of failing the whole turn over it.
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
