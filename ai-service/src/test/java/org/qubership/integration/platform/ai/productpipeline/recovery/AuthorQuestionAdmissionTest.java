package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClarificationDispositionInput;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

class AuthorQuestionAdmissionTest {
  @Test
  void aSourceReviewTypoReportsTheFieldAndBothIdsWithoutChangingAdmission() {
    var flow = new RequirementFlow(List.of(new RequirementFlow.Interaction(
        "entry", RequirementFlow.Direction.INBOUND, "Caller", "POST /work", "")), List.of());
    for (String reviewed : List.of("source-actual", "source-typo")) {
      var findings = new ArrayList<String>();
      AuthorQuestionAdmission.validate(new AuthorQuestionEvidence("entry", "roles",
          AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of(reviewed)), List.of(), flow, List.of(),
          Set.of("source-actual"), Set.of(), true, findings);
      assertEquals(reviewed.equals("source-actual"), findings.isEmpty());
      if (!findings.isEmpty()) {
        assertTrue(findings.getFirst().contains("reviewedInputIds"));
        assertTrue(findings.getFirst().contains("source-actual"));
        assertTrue(findings.getFirst().contains("source-typo"));
      }
    }
  }

  @Test
  void anUntouchedQuestionMayKeepItsRecordedInputIds() {
    var flow = new RequirementFlow(List.of(new RequirementFlow.Interaction(
        "entry", RequirementFlow.Direction.INBOUND, "Caller", "POST /work", "")), List.of());
    var findings = new ArrayList<String>();
    AuthorQuestionAdmission.validate(new AuthorQuestionEvidence("entry", "roles",
        AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("source-actual")), List.of(), flow, List.of(),
        Set.of("source-actual", "source-answer"), null, Set.of(), true, findings);
    assertTrue(findings.isEmpty(), findings.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"http-trigger", "chain-trigger-2", "kafka-trigger-2", "async-api-trigger"})
  void aSelectedDeliveryCapabilityCannotBeAskedAsMissing(String capabilityKey) {
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("entry",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.CAPABILITY, capabilityKey, "Accept the payload.");
    var findings = new ArrayList<String>();

    AuthorQuestionAdmission.validate(new AuthorQuestionEvidence("entry", "capabilityKey",
        AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("source")), List.of(fact), RequirementFlow.EMPTY,
        List.of(), Set.of("source"), Set.of(), true, findings);

    assertEquals(1, findings.size(), findings.toString());
    assertTrue(findings.getFirst().contains("supplied value"), findings.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode.class)
  void aSelectedHttpModeCannotBeAskedAsMissing(
      org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode mode) {
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput("entry",
                RequirementFlow.Direction.INBOUND, null, null, null, null, null)), List.of()),
        List.of(), List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput(
            "entry", "http-trigger", mode, null, null, null, null, null)), List.of(),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(false, null));
    var facts = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.facts(input, java.util.Map.of());
    var findings = new ArrayList<String>();

    AuthorQuestionAdmission.validate(new AuthorQuestionEvidence("entry", "httpMode",
        AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("source")), facts, RequirementFlow.EMPTY,
        List.of(), Set.of("source"), Set.of(), true, findings);

    assertEquals(1, findings.size(), findings.toString());
  }

  @Test
  void ambiguityRequiresThePinnedTurnQuoteAndTwoDistinctCandidates() {
    String answer = "Use either https://one.example or https://two.example";
    var input = new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
        "answer-turn", "Use either", List.of("https://one.example", "https://two.example"));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(input, "answer-turn", answer, value -> true).isEmpty());
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(input, "answer-turn", answer, null).stream()
        .anyMatch(finding -> finding.contains("pinned target schema was not checked")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(input, "answer-turn", "Use either host", value -> true)
        .stream().anyMatch(finding -> finding.contains("must appear in the pinned answer")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "other-turn", "Use either", List.of("https://one.example", "https://two.example")),
        "answer-turn", answer, null).stream().anyMatch(finding -> finding.contains("pinned answer turn")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "answer-turn", "not in the turn", List.of("https://one.example", "https://two.example")),
        "answer-turn", answer, null).stream().anyMatch(finding -> finding.contains("exact quote")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "answer-turn", "Use https://one.example", List.of("https://one.example")),
        "answer-turn", "Use https://one.example", value -> true).stream()
        .anyMatch(finding -> finding.contains("One valid candidate")));
  }
}
