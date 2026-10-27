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

  @Test
  void ambiguityRequiresThePinnedTurnQuoteAndTwoDistinctCandidates() {
    var input = new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
        "answer-turn", "Use either host", List.of("https://one.example", "https://two.example"));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(input, "answer-turn", "Use either host", null).isEmpty());
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "other-turn", "Use either host", List.of("https://one.example", "https://two.example")),
        "answer-turn", "Use either host", null).stream().anyMatch(finding -> finding.contains("pinned answer turn")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "answer-turn", "not in the turn", List.of("https://one.example", "https://two.example")),
        "answer-turn", "Use either host", null).stream().anyMatch(finding -> finding.contains("exact quote")));
    assertTrue(AuthorQuestionAdmission.ambiguityFindings(
        new ClarificationDispositionInput("target-uri", ClarificationTransition.Disposition.AMBIGUOUS,
            "answer-turn", "Use either host", List.of("https://one.example")),
        "answer-turn", "Use either host", null).stream().anyMatch(finding -> finding.contains("One valid candidate")));
  }
}
