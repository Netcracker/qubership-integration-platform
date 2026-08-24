package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

class FailureNarrativeTest {

  @Test
  void returnsTheModelTextAndPassesOnlyStructuredEvidence() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates(
            "The catalog call timed out while looking up the service.");

    Optional<String> narrated =
        new FailureNarrative(agent)
            .narrate(
                "en",
                "work",
                StageOutcomeClass.DOMAIN_FAILURE,
                "bad domain",
                "PLAN_BLOCKER: missing quartz",
                "use a different service");

    assertEquals("The catalog call timed out while looking up the service.", narrated.orElseThrow());
    assertEquals("DOMAIN_FAILURE", agent.lastOutcome.get());
    assertEquals("use a different service", agent.lastFollowUp.get());
  }

  @Test
  void emptyWhenTheTurnFailsSoTheCallerKeepsRawEvidence() {
    assertTrue(
        new FailureNarrative(FakeFailureNarrativeAgent.boom())
            .narrate("en", "work", StageOutcomeClass.DOMAIN_FAILURE, "bad domain", "")
            .isEmpty());
    assertTrue(
        new FailureNarrative()
            .narrate("en", "work", StageOutcomeClass.DOMAIN_FAILURE, "bad domain", "")
            .isEmpty());
  }

  @Test
  void emptyWhenTheModelReturnsBlank() {
    assertTrue(
        new FailureNarrative(FakeFailureNarrativeAgent.narrates("  "))
            .narrate("en", "work", StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, "timeout", "")
            .isEmpty());
  }

  @Test
  void diagnosePassesTheCandidateListAndFollowUpAndReturnsTheFakeOwner() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("planning", "plan-validation-result"),
            new OwnerCandidate("analysis", "requirement-brief"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "en",
                "planning",
                StageOutcomeClass.VALIDATION_FAILURE,
                "planning validation failed",
                "PLAN_BLOCKER: missing quartz",
                candidates,
                "the quartz job is required");

    assertEquals("The brief omitted the scheduler.", diagnosis.narrative());
    assertEquals("analysis", diagnosis.owner().orElseThrow());
    assertFalse(diagnosis.ambiguous());
    assertEquals(
        "planning:plan-validation-result,analysis:requirement-brief", agent.lastCandidateSet.get());
    assertEquals("the quartz job is required", agent.lastFollowUp.get());
    assertEquals("VALIDATION_FAILURE", agent.lastOutcome.get());
  }

  @Test
  void diagnoseDropsAnOwnerOutsideTheCandidateSet() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Blaming compiler.", "compiler");
    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "en",
                "planning",
                StageOutcomeClass.VALIDATION_FAILURE,
                "failed",
                "",
                List.of(new OwnerCandidate("planning", "plan-validation-result")),
                "");

    assertEquals("Blaming compiler.", diagnosis.narrative());
    assertTrue(diagnosis.owner().isEmpty());
    assertFalse(diagnosis.ambiguous());
  }

  @Test
  void diagnoseAskWhenTheFakeMarksAmbiguous() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.ask("Either artifact could be wrong.");
    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "en",
                "planning",
                StageOutcomeClass.DOMAIN_FAILURE,
                "failed",
                "",
                List.of(
                    new OwnerCandidate("planning", "plan-validation-result"),
                    new OwnerCandidate("analysis", "requirement-brief")),
                "");

    assertTrue(diagnosis.ambiguous());
    assertTrue(diagnosis.owner().isEmpty());
    assertEquals("Either artifact could be wrong.", diagnosis.narrative());
  }

  @Test
  void diagnoseEmptyWhenTheTurnFails() {
    OwnerDiagnosis diagnosis =
        new FailureNarrative(FakeFailureNarrativeAgent.boom())
            .diagnose(
                "en",
                "work",
                StageOutcomeClass.DOMAIN_FAILURE,
                "bad domain",
                "",
                List.of(new OwnerCandidate("work", "")),
                "");
    assertEquals("", diagnosis.narrative());
    assertTrue(diagnosis.owner().isEmpty());
  }

  @Test
  void findingsTextFormatsValidationCandidates() {
    String text =
        FailureNarrative.findingsText(
            List.of(
                new ArtifactCandidate(
                    Kind.PLAN_VALIDATION_RESULT,
                    new PlanValidationResult(
                        List.of(new PlanValidationFinding("PLAN_BLOCKER", "missing quartz", true))),
                    List.of())));
    assertEquals("PLAN_BLOCKER: missing quartz (blocker)", text);
  }
}
