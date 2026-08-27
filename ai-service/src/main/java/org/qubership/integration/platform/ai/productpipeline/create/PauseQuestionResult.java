package org.qubership.integration.platform.ai.productpipeline.create;

/**
 * Outcome of one pause-question turn. Three values, not two: an answer, a message that was not a
 * question, and an inability to answer. The last is a timeout, a failed call, or a blank question
 * verdict; it is never treated as an instruction.
 */
public record PauseQuestionResult(Kind kind, String answer) {

  public enum Kind {
    ANSWER,
    NOT_A_QUESTION,
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

  public static PauseQuestionResult unanswerable() {
    return new PauseQuestionResult(Kind.UNANSWERABLE, "");
  }

  public boolean isAnswer() {
    return kind == Kind.ANSWER;
  }

  public boolean isNotAQuestion() {
    return kind == Kind.NOT_A_QUESTION;
  }

  public boolean isUnanswerable() {
    return kind == Kind.UNANSWERABLE;
  }
}
