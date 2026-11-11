package org.qubership.integration.platform.ai.plan.workdocument;

/** Unresolved choice with the record fields an acceptance driver can match. */
public record QuestionSubject(
    QuestionChoiceKind choiceKind, QuestionFieldRef source, QuestionFieldRef target) {

  public QuestionSubject {
    choiceKind = choiceKind == null ? QuestionChoiceKind.UNSPECIFIED : choiceKind;
    source = source == null ? QuestionFieldRef.empty() : source;
    target = target == null ? QuestionFieldRef.empty() : target;
    if (choiceKind == QuestionChoiceKind.FIELD_RELATIONSHIP
        && (source.fieldPath().isBlank() || target.fieldPath().isBlank())) {
      throw new IllegalArgumentException(
          "A field relationship needs source and target field paths. Name both fields.");
    }
  }

  public static QuestionSubject unspecified() {
    return new QuestionSubject(QuestionChoiceKind.UNSPECIFIED, QuestionFieldRef.empty(), QuestionFieldRef.empty());
  }

  public static QuestionSubject fieldRelationship(QuestionFieldRef source, QuestionFieldRef target) {
    return new QuestionSubject(QuestionChoiceKind.FIELD_RELATIONSHIP, source, target);
  }
}
