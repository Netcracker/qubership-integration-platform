package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.RetryInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureProjectionTest {

  @Test
  void reviewDistinguishesRepeatedCallsWithoutExposingCaptureIds() {
    DraftInput draft = new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("entry-secret-id", Direction.INBOUND, "Client", "POST /tasks",
                null, null, null),
            new InteractionInput("first-secret-id", Direction.OUTBOUND, "Task API", "submit",
                null, null, new RetryInput(0, null)),
            new InteractionInput("second-secret-id", Direction.OUTBOUND, "Task API", "submit",
                null, null, null)),
            List.of(new TransitionInput("entry-secret-id", "first-secret-id"),
                new TransitionInput("first-secret-id", "second-secret-id"))),
        List.of(new FactInput("private-fact-id", List.of("first-secret-id", "second-secret-id"),
            FactKind.CONSTRAINT, Polarity.NEGATIVE, "Do not log the task body.")),
        List.of(new CapabilityInput("entry-secret-id", "http-trigger", HttpMode.CUSTOM,
            HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput("first-secret-id", "http-sender", null, HttpMethod.POST,
                "https://example.com/tasks", null, null, null),
            new CapabilityInput("second-secret-id", "http-sender", null, HttpMethod.POST,
                "https://example.com/tasks", null, null, null)),
        List.of(), new DraftSettings(null, null));

    String review = RequirementCaptureProjection.render(draft, List.of());

    assertTrue(review.contains("Call 1: Task API submit"));
    assertTrue(review.contains("Call 2: Task API submit"));
    assertTrue(review.contains("After Call 1, run Call 2."));
    assertTrue(review.contains("Do not log the task body. (for Call 1, Call 2)"));
    assertFalse(review.contains("secret-id"));
    assertFalse(review.contains("NEGATIVE CONSTRAINT"));
    assertEquals(RequirementFactPolarity.NEGATIVE,
        RequirementCaptureProjection.facts(draft).stream()
            .filter(fact -> "first-secret-id-retry".equals(fact.sourceFactId()))
            .findFirst().orElseThrow().polarity());
  }
}
