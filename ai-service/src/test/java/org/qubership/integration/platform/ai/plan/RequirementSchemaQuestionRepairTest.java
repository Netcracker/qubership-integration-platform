package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementSchemaQuestionRepairTest {
  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"apis", "inventedTargetProperty", "roles", "name"})
  void onlyUnsupportedMissingTargetsCanBeWithdrawnWithoutInventingAnAnswer(String property) {
    var question = new QuestionInput("choice", List.of("entry"), "Which value?",
        new AuthorQuestionEvidence("entry", property, AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of("author-turn")));
    var base = schemaDraft(List.of(), List.of(question));
    var candidate = RequirementCaptureProjection.toDraft(schemaDraft(List.of(), List.of()), null,
        List.of(), "v1", "hash", Map.of());
    var findings = new java.util.ArrayList<String>();
    AuthorQuestionAdmission.validate(question.evidence(), RequirementCaptureProjection.facts(base),
        RequirementCaptureProjection.flow(base), List.of(), Set.of("author-turn"), Set.of(), true, findings);
    var issues = RequirementRevisionAdmission.unappliedRemovals(base, candidate, ignored -> Set.of());
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(Set.of("roles", "name").contains(property), findings.isEmpty(), findings.toString()),
        () -> assertEquals(Set.of("roles", "name").contains(property) ? List.of("UNAPPLIED_AUTHOR_ANSWER") : List.of(),
            issues.stream().map(RequirementCaptureEditor.Issue::code).toList(), issues.toString()),
        () -> assertEquals(base.facts(), candidate.authoredDraft().facts()),
        () -> assertEquals(base.capabilities(), candidate.authoredDraft().capabilities()));
  }
  @ParameterizedTest
  @MethodSource("schemaRequirements")
  void removingASchemaQuestionKeepsOnlyTheActualBodyFieldRequirement(String mapping, List<String> expectedIssues) {
    var facts = new java.util.ArrayList<FactInput>();
    facts.add(new FactInput("forward", List.of("entry", "call"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Forward the query parameter and return the response unchanged."));
    if (!"none".equals(mapping)) {
      String path = "whole-body".equals(mapping) ? "$" : "$.customer";
      facts.add(new FactInput("copy", List.of("entry", "call"), FactKind.FIELD_MAPPING, Polarity.POSITIVE,
          "Copy the requested body value.", "retained-body-field".equals(mapping)
              ? new FieldMappingInput("middle", null, "call", path, null,
                  new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue.Copy(
                      new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource.Message("entry",
                          org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.OUTPUT, path)))
              : new FieldMappingInput("entry", path, "middle", path, null)));
    }
    var flow = new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /forward", null, null, null),
        new InteractionInput("middle", Direction.OUTBOUND, "First target", "GET /first", null, null, null),
        new InteractionInput("call", Direction.OUTBOUND, "Target", "GET /target", null, null, null)),
        List.of(new TransitionInput("entry", "middle"), new TransitionInput("middle", "call")));
    var question = new QuestionInput("body-schema", List.of("entry"), "Which request body schema?",
        new AuthorQuestionEvidence("entry", "requestSchema", AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of("author-turn")));
    var base = new DraftInput(flow, facts, List.of(), List.of(question), new DraftSettings(false, null));
    var next = new DraftInput(flow, facts, List.of(), List.of(), base.settings());
    var candidate = RequirementCaptureProjection.toDraft(next, null, List.of(), "v1", "hash", Map.of());
    var issues = RequirementRevisionAdmission.unappliedRemovals(base, candidate, ignored -> Set.of());
    assertEquals(expectedIssues, issues.stream().map(RequirementCaptureEditor.Issue::code).toList(), issues.toString());
    assertEquals(facts, candidate.authoredDraft().facts());
  }
  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}",
      "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", "{\"type\":\"string\"}",
      "{\"type\":\"object\",\"additionalProperties\":true}"})
  void aSavedStructuredSchemaAnswerCannotBeDroppedWhenItsQuestionCloses(String answer) {
    var question = new QuestionInput("schema", List.of("entry"), "Which request schema?",
        new AuthorQuestionEvidence("entry", "requestSchema", AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("turn")));
    var base = schemaDraft(List.of(), List.of(question));
    var pinned = RequirementCaptureProjection.toDraft(base, null, List.of(), "v1", "hash", Map.of());
    var candidate = RequirementCaptureProjection.toDraft(schemaDraft(List.of(), List.of()), pinned,
        List.of(), "v1", "hash", Map.of());
    var context = new RequirementDraftStore.ClarificationAdmissionContext(null, "turn", pinned, question,
        null, null, false, answer, Set.of("turn"));
    var issues = RequirementRevisionAdmission.unappliedRemovals(base, candidate, ignored -> Set.of(), context);
    assertEquals(List.of("UNAPPLIED_AUTHOR_ANSWER"), issues.stream().map(RequirementCaptureEditor.Issue::code).toList());
  }

  @org.junit.jupiter.api.Test
  void anAlreadyAcceptedSchemaCannotBeRemovedAsAnUnnecessaryQuestion() {
    var question = new QuestionInput("schema", List.of("entry"), "Which request schema?",
        new AuthorQuestionEvidence("entry", "requestSchema", AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("turn")));
    var schema = new FactInput("schema-fact", List.of("entry"), FactKind.MESSAGE_SCHEMA, Polarity.POSITIVE,
        "{\"type\":\"object\",\"properties\":{}}");
    var base = schemaDraft(List.of(schema), List.of(question));
    var removed = RequirementCaptureProjection.toDraft(schemaDraft(List.of(), List.of()), null,
        List.of(), "v1", "hash", Map.of());
    var kept = RequirementCaptureProjection.toDraft(schemaDraft(List.of(schema), List.of()), null,
        List.of(), "v1", "hash", Map.of());
    assertEquals(List.of("UNAPPLIED_AUTHOR_ANSWER"), RequirementRevisionAdmission.unappliedRemovals(
        base, removed, ignored -> Set.of()).stream().map(RequirementCaptureEditor.Issue::code).toList());
    assertEquals(List.of(), RequirementRevisionAdmission.unappliedRemovals(base, kept, ignored -> Set.of()));
  }

  @org.junit.jupiter.api.Test
  void policyQuestionsCannotUseTheMissingSchemaWithdrawalException() {
    var question = new QuestionInput("policy", List.of("entry"), "Confirm policy?",
        new AuthorQuestionEvidence("entry", "requestSchema", AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of("turn")));
    var base = schemaDraft(List.of(), List.of(question));
    var candidate = RequirementCaptureProjection.toDraft(schemaDraft(List.of(), List.of()), null,
        List.of(), "v1", "hash", Map.of());
    assertEquals(List.of("UNAPPLIED_AUTHOR_ANSWER"), RequirementRevisionAdmission.unappliedRemovals(
        base, candidate, ignored -> Set.of()).stream().map(RequirementCaptureEditor.Issue::code).toList());
  }

  @org.junit.jupiter.api.Test
  void aSavedSchemaAnswerCanBeAppliedWithoutInventingABodyFieldOperation() {
    String answer = "{\"type\":\"object\",\"properties\":{}}";
    var question = new QuestionInput("schema", List.of("entry"), "Which request schema?",
        new AuthorQuestionEvidence("entry", "requestSchema", AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("turn")));
    var base = schemaDraft(List.of(), List.of(question));
    var pinned = RequirementCaptureProjection.toDraft(base, null, List.of(), "v1", "hash", Map.of());
    var fact = new FactInput("schema-fact", List.of("entry"), FactKind.MESSAGE_SCHEMA, Polarity.POSITIVE,
        answer, null, null, null, new ClaimEvidenceInput("turn", answer));
    var candidate = RequirementCaptureProjection.toDraft(schemaDraft(List.of(fact), List.of()), pinned,
        List.of(), "v1", "hash", Map.of());
    var context = new RequirementDraftStore.ClarificationAdmissionContext(null, "turn", pinned, question,
        null, null, false, answer, Set.of("turn"));
    assertEquals(List.of(), RequirementRevisionAdmission.unappliedRemovals(
        base, candidate, ignored -> Set.of(), context));
    assertEquals(List.of(fact), candidate.authoredDraft().facts());
  }

  private static DraftInput schemaDraft(List<FactInput> facts, List<QuestionInput> questions) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "GET /forward", null, null, null)), List.of()), facts,
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
            "/forward", null, null, null)), questions, new DraftSettings(false, null));
  }

  private static java.util.stream.Stream<Arguments> schemaRequirements() {
    return java.util.stream.Stream.of(Arguments.of("none", List.of()), Arguments.of("whole-body", List.of()),
        Arguments.of("body-field", List.of("UNAPPLIED_AUTHOR_ANSWER")),
        Arguments.of("retained-body-field", List.of("UNAPPLIED_AUTHOR_ANSWER")));
  }

}
