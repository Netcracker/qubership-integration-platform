package org.qubership.integration.platform.ai.productpipeline.create;

/**
 * Outcome of one pause-question turn. A non-question may carry a concrete correction or only ask
 * the run to repeat. The distinction lets a halted run consume new repair information without
 * spending a recovery attempt on a rephrased retry.
 */
public record PauseQuestionResult(Kind kind, String answer) {

  public enum Kind {
    ANSWER,
    NOT_A_QUESTION,
    REPAIR_INSTRUCTION,
    UNANSWERABLE
  }

  public PauseQuestionResult {
    kind = kind == null ? Kind.UNANSWERABLE : kind;
    answer = answer == null ? "" : answer;
  }

  public static PauseQuestionResult answer(String text) {
    String value = text == null ? "" : text.trim();
    if (value.isBlank()) {
      return unanswerable();
    }
    return new PauseQuestionResult(Kind.ANSWER, value);
  }

  public static PauseQuestionResult notAQuestion() {
    return new PauseQuestionResult(Kind.NOT_A_QUESTION, "");
  }

  public static PauseQuestionResult repairInstruction() {
    return new PauseQuestionResult(Kind.REPAIR_INSTRUCTION, "");
  }

  public static PauseQuestionResult unanswerable() {
    return new PauseQuestionResult(Kind.UNANSWERABLE, "");
  }

  public boolean isAnswer() {
    return kind == Kind.ANSWER;
  }

  public boolean isNotAQuestion() {
    return kind == Kind.NOT_A_QUESTION || kind == Kind.REPAIR_INSTRUCTION;
  }

  public boolean isRepairInstruction() {
    return kind == Kind.REPAIR_INSTRUCTION;
  }

  public boolean isUnanswerable() {
    return kind == Kind.UNANSWERABLE;
  }
}
